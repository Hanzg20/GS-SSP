-- 2026-10-28: Ourea Scan pay at the table -- after the meal the customer opens
-- the table's bill on their phone (same QR) and pays all of it or a share, plus
-- a tip, online through the store's Nuvei account (Simply Connect).
-- docs/database_design.md §5.
--
-- * The customer never says how much tax or what the check is: the server works
--   out the balance, the payment's tax part (same rule as Ourea's
--   DineRepository.taxOfPayment) and checks the tip (Tipping.kt's limits).
-- * dine.online_payments is the attempt: OPEN when the Nuvei order is opened,
--   PAID only once Nuvei confirmed it (getPaymentStatus or a signed DMN, both in
--   supabase/functions/scan-pay*), then it is a row in dine.payments (method
--   ONLINE) like any card payment, and the check closes when it is paid in full.
--   (As first applied it was also a row in public.transactions; replaced the
--   same day by 2026-10-28_dine_payments_device.sql -- dine keeps its own
--   transactions in dine.payments, device_sn = the store's Ourea.)
-- * Only the edge functions (service_role) open / settle / fail an attempt; the
--   phone only reads the bill (scan_bill).
-- * A store takes online payments only with an ACTIVE nuvei row in
--   merchant_acquirer_accounts; otherwise scan_bill says online_pay = false and
--   the page shows "pay your server" as before.

BEGIN;

CREATE TABLE IF NOT EXISTS dine.online_payments (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id              UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  check_id            UUID NOT NULL REFERENCES dine.checks(id) ON DELETE CASCADE,
  amount_cents        INTEGER NOT NULL CHECK (amount_cents > 0),   -- food + tax, without tip
  tax_cents           INTEGER NOT NULL DEFAULT 0 CHECK (tax_cents >= 0),
  tip_cents           INTEGER NOT NULL DEFAULT 0 CHECK (tip_cents >= 0),
  currency            TEXT NOT NULL DEFAULT 'CAD',
  status              TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PAID', 'FAILED')),
  acquirer_account_id UUID REFERENCES merchant_acquirer_accounts(id),
  session_token       TEXT,          -- Nuvei openOrder
  order_id            TEXT,          -- Nuvei openOrder
  status_checked_at   TIMESTAMPTZ,   -- getPaymentStatus is called once per session (Nuvei blocks pollers)
  transaction_id      TEXT,          -- Nuvei TransactionID once paid
  auth_code           TEXT,
  card                TEXT,          -- brand / last 4 as Nuvei gives them
  reason              TEXT,          -- why it failed
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  paid_at             TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS online_payments_check_idx ON dine.online_payments (check_id);
COMMENT ON TABLE dine.online_payments IS
  'Ourea Scan pay-at-table attempts (Nuvei): OPEN -> PAID (then also a dine.payments row, method ONLINE) / FAILED. Written only by the scan-pay edge functions.';
ALTER TABLE dine.online_payments ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON dine.online_payments FROM public, anon, authenticated;

-- ---------------------------------------------------------------- customer (anon)

-- The table's live check for the bill page: lines, totals, what is left to pay,
-- and whether the store takes online payment. check null when nothing is open.
CREATE OR REPLACE FUNCTION public.scan_bill(p_token TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  WITH t AS (SELECT * FROM dine.tables WHERE qr_token = p_token AND is_active),
  c AS (SELECT c.* FROM dine.checks c JOIN t ON c.table_id = t.id WHERE c.status IN ('OPEN', 'BILLED')),
  tot AS (
    SELECT coalesce(sum(s), 0)::INT AS subtotal, coalesce(sum(round(s * b / 10000.0)), 0)::INT AS tax
      FROM (SELECT i.tax_rate_bps b, sum(i.quantity * i.unit_price_cents) s
              FROM dine.check_items i JOIN c ON i.check_id = c.id WHERE i.status <> 'VOID' GROUP BY 1) r),
  paid AS (SELECT coalesce(sum(p.amount_cents), 0)::INT AS amt, coalesce(sum(p.tax_cents), 0)::INT AS tax
             FROM dine.payments p JOIN c ON p.check_id = c.id)
  SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM t) THEN NULL ELSE jsonb_build_object(
    'online_pay', EXISTS (SELECT 1 FROM t JOIN merchant_acquirer_accounts a ON a.org_id = t.org_id
                           WHERE a.acquirer = 'nuvei' AND a.status = 'ACTIVE'),
    'check', (SELECT jsonb_build_object(
        'check_no', c.check_no,
        'lines', coalesce((SELECT jsonb_agg(jsonb_build_object('name', i.name, 'options', i.options, 'quantity', i.quantity,
                                                               'amount_cents', i.quantity * i.unit_price_cents)
                                            ORDER BY i.round, i.sent_at)
                             FROM dine.check_items i WHERE i.check_id = c.id AND i.status <> 'VOID'), '[]'::jsonb),
        'subtotal_cents', tot.subtotal, 'tax_cents', tot.tax, 'total_cents', tot.subtotal + tot.tax,
        'paid_cents', paid.amt, 'balance_cents', greatest(tot.subtotal + tot.tax - paid.amt, 0),
        'tax_left_cents', greatest(tot.tax - paid.tax, 0))
      FROM c, tot, paid)) END
$$;

-- ---------------------------------------------------------------- scan-pay edge functions (service_role)

-- A payment of p_amount_cents (food + tax; at most what is left) plus p_tip_cents
-- on the table's live check. Returns the attempt and the store's Nuvei account.
CREATE OR REPLACE FUNCTION public.dine_online_pay_open(p_token TEXT, p_amount_cents INT, p_tip_cents INT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE
  v_table dine.tables%ROWTYPE; v_check dine.checks%ROWTYPE; v_account UUID;
  v_total INT; v_tax_total INT; v_paid INT; v_paid_tax INT; v_balance INT; v_reserved INT; v_tax INT; v_basis INT; v_id UUID;
BEGIN
  SELECT * INTO v_table FROM dine.tables WHERE qr_token = p_token AND is_active;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_table'); END IF;
  SELECT * INTO v_check FROM dine.checks WHERE table_id = v_table.id AND status IN ('OPEN', 'BILLED') FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'no_open_check'); END IF;
  SELECT id INTO v_account FROM merchant_acquirer_accounts
   WHERE org_id = v_table.org_id AND acquirer = 'nuvei' AND status = 'ACTIVE';
  IF v_account IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'online_pay_off'); END IF;

  -- Abuse limit: a check can't open Nuvei orders without end.
  IF (SELECT count(*) FROM dine.online_payments WHERE check_id = v_check.id AND created_at > now() - interval '10 minutes') >= 6 THEN
    RETURN jsonb_build_object('success', false, 'message', 'too_many_attempts');
  END IF;

  SELECT coalesce(sum(s), 0)::INT, coalesce(sum(round(s * b / 10000.0)), 0)::INT INTO v_total, v_tax_total
    FROM (SELECT tax_rate_bps b, sum(quantity * unit_price_cents) s FROM dine.check_items
           WHERE check_id = v_check.id AND status <> 'VOID' GROUP BY 1) r;
  v_total := v_total + v_tax_total;
  SELECT coalesce(sum(amount_cents), 0)::INT, coalesce(sum(tax_cents), 0)::INT INTO v_paid, v_paid_tax
    FROM dine.payments WHERE check_id = v_check.id;
  v_balance := greatest(v_total - v_paid, 0);
  -- What other phones at the table are paying right now.
  SELECT coalesce(sum(amount_cents), 0)::INT INTO v_reserved FROM dine.online_payments
   WHERE check_id = v_check.id AND status = 'OPEN' AND created_at > now() - interval '15 minutes';

  IF coalesce(p_amount_cents, 0) <= 0 OR p_amount_cents > v_balance THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_amount', 'balance_cents', v_balance);
  END IF;
  IF p_amount_cents > v_balance - v_reserved THEN
    RETURN jsonb_build_object('success', false, 'message', 'being_paid', 'balance_cents', v_balance);
  END IF;

  -- Tax inside this payment: the payment that settles the check takes exactly
  -- the tax left; a share takes it in proportion (DineRepository.taxOfPayment).
  v_tax := CASE WHEN p_amount_cents >= v_balance THEN greatest(v_tax_total - v_paid_tax, 0)
                ELSE ((p_amount_cents::BIGINT * greatest(v_tax_total - v_paid_tax, 0) + v_balance / 2) / v_balance)::INT END;
  -- Tip: Tipping.maxCustomCents -- the bigger of the amount before tax and $20.
  v_basis := p_amount_cents - v_tax;
  IF coalesce(p_tip_cents, 0) < 0 OR coalesce(p_tip_cents, 0) > greatest(v_basis, 2000) THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_tip');
  END IF;

  INSERT INTO dine.online_payments (org_id, check_id, amount_cents, tax_cents, tip_cents, acquirer_account_id)
  VALUES (v_table.org_id, v_check.id, p_amount_cents, v_tax, coalesce(p_tip_cents, 0), v_account)
  RETURNING id INTO v_id;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'acquirer_account_id', v_account,
                            'amount_cents', p_amount_cents, 'tax_cents', v_tax, 'tip_cents', coalesce(p_tip_cents, 0));
END $$;

-- Nuvei confirmed the payment (p_paid_cents = what it charged, tip included).
-- The money is taken, so it is always recorded -- even on a check staff closed
-- meanwhile (an overpayment staff then refund) -- unless the amount is wrong.
CREATE OR REPLACE FUNCTION public.dine_online_pay_settle(p_id UUID, p_transaction_id TEXT, p_paid_cents INT, p_auth_code TEXT, p_card TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_p dine.online_payments%ROWTYPE; v_check dine.checks%ROWTYPE; v_ref TEXT; v_total INT; v_paid INT; v_sn TEXT;
BEGIN
  SELECT * INTO v_p FROM dine.online_payments WHERE id = p_id FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'payment_not_found'); END IF;
  IF v_p.status = 'PAID' THEN RETURN jsonb_build_object('success', true, 'duplicate', true); END IF;
  IF coalesce(trim(p_transaction_id), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_transaction'); END IF;
  IF p_paid_cents IS DISTINCT FROM v_p.amount_cents + v_p.tip_cents THEN
    -- Never seen in a correct flow; left OPEN for a person to look at.
    UPDATE dine.online_payments SET reason = 'amount_mismatch: charged ' || coalesce(p_paid_cents::TEXT, '?') || ' tx ' || trim(p_transaction_id)
     WHERE id = p_id;
    RETURN jsonb_build_object('success', false, 'message', 'amount_mismatch');
  END IF;

  v_ref := 'NUVEI-' || trim(p_transaction_id);
  UPDATE dine.online_payments SET status = 'PAID', paid_at = now(), transaction_id = trim(p_transaction_id),
         auth_code = nullif(trim(p_auth_code), ''), card = nullif(trim(p_card), ''), reason = NULL
   WHERE id = p_id;
  INSERT INTO dine.payments (check_id, ecr_ref_num, amount_cents, tax_cents, tip_cents, method, paid_by)
  VALUES (v_p.check_id, v_ref, v_p.amount_cents, v_p.tax_cents, v_p.tip_cents, 'ONLINE', 'QR')
  ON CONFLICT (ecr_ref_num) DO NOTHING;

  -- The sale in transactions like one taken on the Ourea (reports, Insights, Cael),
  -- under the store's Ourea: the one that opened the check, else the last seen;
  -- a store without an Ourea gets the device that opened the check.
  SELECT * INTO v_check FROM dine.checks WHERE id = v_p.check_id;
  SELECT d.sn INTO v_sn FROM devices d
   WHERE d.org_id = v_p.org_id AND d.is_active AND d.app_version LIKE '%-ourea'
   ORDER BY (d.sn = v_check.opened_by_sn) DESC, d.last_seen DESC NULLS LAST LIMIT 1;
  IF v_sn IS NULL THEN
    SELECT sn INTO v_sn FROM devices WHERE sn = v_check.opened_by_sn;
  END IF;
  INSERT INTO public.transactions (device_sn, amount, currency, payment_status, payment_method, entry_mode, auth_code, ecr_ref_num,
                                   card_brand, subtotal_cents, tax_cents, tip_cents, check_id)
  VALUES (v_sn, v_p.amount_cents + v_p.tip_cents, v_p.currency, 'PAID', 'CREDIT_CARD', 'ONLINE', nullif(trim(p_auth_code), ''), v_ref,
          nullif(upper(split_part(trim(coalesce(p_card, '')), ' ', 1)), ''), v_p.amount_cents - v_p.tax_cents, v_p.tax_cents, v_p.tip_cents,
          v_p.check_id)
  ON CONFLICT (ecr_ref_num) DO NOTHING;

  v_total := dine_check_total(v_p.check_id);
  SELECT coalesce(sum(amount_cents), 0) INTO v_paid FROM dine.payments WHERE check_id = v_p.check_id;
  UPDATE dine.checks SET status = CASE WHEN status IN ('OPEN', 'BILLED') AND v_paid >= v_total AND v_total > 0 THEN 'PAID' ELSE status END,
         closed_at = CASE WHEN status IN ('OPEN', 'BILLED') AND v_paid >= v_total AND v_total > 0 THEN now() ELSE closed_at END,
         updated_at = now()
   WHERE id = v_p.check_id;
  RETURN jsonb_build_object('success', true, 'balance_cents', greatest(v_total - v_paid, 0));
END $$;

-- The Nuvei order opened for an attempt.
CREATE OR REPLACE FUNCTION public.dine_online_pay_attach(p_id UUID, p_session_token TEXT, p_order_id TEXT)
RETURNS JSONB LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
  UPDATE dine.online_payments SET session_token = p_session_token, order_id = p_order_id
   WHERE id = p_id AND status = 'OPEN';
  SELECT jsonb_build_object('success', true);
$$;

-- An attempt (null when unknown, or p_token given and not its table's).
-- p_claim_status_check: true once per attempt -- the caller may then ask Nuvei's
-- getPaymentStatus, which Nuvei wants called once, not polled.
CREATE OR REPLACE FUNCTION public.dine_online_pay_get(p_id UUID, p_token TEXT, p_claim_status_check BOOLEAN)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_p dine.online_payments%ROWTYPE; v_claimed BOOLEAN := false;
BEGIN
  SELECT o.* INTO v_p FROM dine.online_payments o
   WHERE o.id = p_id
     AND (p_token IS NULL OR EXISTS (SELECT 1 FROM dine.checks c JOIN dine.tables t ON t.id = c.table_id
                                      WHERE c.id = o.check_id AND t.qr_token = p_token))
   FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;
  IF p_claim_status_check AND v_p.status = 'OPEN' AND v_p.status_checked_at IS NULL AND v_p.session_token IS NOT NULL THEN
    UPDATE dine.online_payments SET status_checked_at = now() WHERE id = p_id;
    v_claimed := true;
  END IF;
  RETURN to_jsonb(v_p) || jsonb_build_object('claimed', v_claimed,
    'balance_cents', greatest(dine_check_total(v_p.check_id) - (SELECT coalesce(sum(amount_cents), 0) FROM dine.payments WHERE check_id = v_p.check_id), 0));
END $$;

CREATE OR REPLACE FUNCTION public.dine_online_pay_fail(p_id UUID, p_reason TEXT)
RETURNS JSONB LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
  UPDATE dine.online_payments SET status = 'FAILED', reason = left(p_reason, 200)
   WHERE id = p_id AND status = 'OPEN';
  SELECT jsonb_build_object('success', true);
$$;

REVOKE ALL ON FUNCTION public.scan_bill(TEXT) FROM public;
GRANT EXECUTE ON FUNCTION public.scan_bill(TEXT) TO anon, authenticated;
REVOKE ALL ON FUNCTION public.dine_online_pay_open(TEXT, INT, INT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.dine_online_pay_settle(UUID, TEXT, INT, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.dine_online_pay_fail(UUID, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.dine_online_pay_attach(UUID, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.dine_online_pay_get(UUID, TEXT, BOOLEAN) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.dine_online_pay_open(TEXT, INT, INT) TO service_role;
GRANT EXECUTE ON FUNCTION public.dine_online_pay_settle(UUID, TEXT, INT, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.dine_online_pay_fail(UUID, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.dine_online_pay_attach(UUID, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.dine_online_pay_get(UUID, TEXT, BOOLEAN) TO service_role;

COMMIT;
