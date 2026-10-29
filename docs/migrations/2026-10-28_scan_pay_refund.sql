-- 2026-10-28: refund an Ourea Scan online payment from the Ourea, like a card
-- sale: whole payment, a manager's PIN on the terminal, voided at Nuvei when
-- not yet settled, refunded when it is (supabase/functions/scan-pay-refund).
--
-- * dine.payments gets the refund (refund_status VOIDED / REFUNDED, when, which
--   manager) -- the same model as transactions' refunded_at / refund_approved_by_*.
--   A refund doesn't reopen the check (a counter refund doesn't reopen a sale
--   either); the day report and the day profit leave the money out.
-- * dine.online_payments keeps Nuvei's void / refund transaction id, and
--   refund_started_at so one payment can't be refunded twice at once.
-- * dine_online_sales also returns the terminal's online payments refunded in
--   the range, with their refund state, in the day report's row shape.

BEGIN;

ALTER TABLE dine.payments ADD COLUMN IF NOT EXISTS refund_status TEXT CHECK (refund_status IN ('VOIDED', 'REFUNDED'));
ALTER TABLE dine.payments ADD COLUMN IF NOT EXISTS refunded_at TIMESTAMPTZ;
ALTER TABLE dine.payments ADD COLUMN IF NOT EXISTS refund_approved_by_staff_id UUID REFERENCES public.staff(id) ON DELETE SET NULL;
ALTER TABLE dine.payments ADD COLUMN IF NOT EXISTS refund_approved_by_name TEXT;
COMMENT ON COLUMN dine.payments.refund_status IS 'VOIDED (cancelled before settlement) / REFUNDED; null = kept. The check is not reopened.';

ALTER TABLE dine.online_payments ADD COLUMN IF NOT EXISTS refund_transaction_id TEXT;
ALTER TABLE dine.online_payments ADD COLUMN IF NOT EXISTS refund_started_at TIMESTAMPTZ;

-- ---------------------------------------------------------------- terminal (authenticated)

-- The calling terminal's online payments made or refunded in [p_start, p_end),
-- in the day report's row shape (DayReportRepository.ReportTxn).
CREATE OR REPLACE FUNCTION public.dine_online_sales(p_start TIMESTAMPTZ, p_end TIMESTAMPTZ)
RETURNS JSONB LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
           'ecr_ref_num', p.ecr_ref_num,
           'amount', p.amount_cents + p.tip_cents,
           'subtotal_cents', p.amount_cents - p.tax_cents,
           'tax_cents', p.tax_cents,
           'tip_cents', p.tip_cents,
           'payment_status', coalesce(p.refund_status, 'PAID'),
           'payment_method', 'ONLINE',
           'staff_name', 'Ourea Scan',
           'created_at', p.created_at,
           'refunded_at', p.refunded_at,
           'refund_approved_by_name', p.refund_approved_by_name) ORDER BY p.created_at), '[]'::jsonb)
    FROM dine.payments p
   WHERE p.method = 'ONLINE'
     AND (p.created_at >= p_start AND p.created_at < p_end OR p.refunded_at >= p_start AND p.refunded_at < p_end)
     AND p.device_sn = (SELECT m.device_sn FROM device_auth_map m
                         WHERE m.auth_user_id = auth.uid() AND m.org_id IS NOT NULL
                         ORDER BY m.created_at DESC LIMIT 1)
$$;

-- The store's day profit: refunded online payments are left out, like refunded sales.
CREATE OR REPLACE FUNCTION public.office_day_profit_for(p_org UUID, p_day DATE)
RETURNS JSONB LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  WITH sales AS (
    SELECT t.amount AS total, coalesce(t.tax_cents, 0) AS tax, coalesce(t.tip_cents, 0) AS tip
      FROM transactions t
     WHERE t.device_sn IN (SELECT sn FROM devices WHERE org_id = p_org)
       AND t.payment_status = 'PAID'
       AND (t.created_at AT TIME ZONE 'America/Toronto')::date = p_day
    UNION ALL
    SELECT p.amount_cents + p.tip_cents, p.tax_cents, p.tip_cents
      FROM dine.payments p JOIN dine.checks c ON c.id = p.check_id
     WHERE c.org_id = p_org AND p.method = 'ONLINE' AND p.refund_status IS NULL
       AND (p.created_at AT TIME ZONE 'America/Toronto')::date = p_day),
  s AS (
    SELECT count(*) AS n, coalesce(sum(total), 0) AS total, coalesce(sum(tax), 0) AS tax, coalesce(sum(tip), 0) AS tip FROM sales),
  e AS (
    SELECT category, count(*) AS n, sum(amount_cents) AS c
      FROM expenses WHERE org_id = p_org AND spent_on = p_day GROUP BY category)
  SELECT jsonb_build_object(
    'day', p_day,
    'sales', s.n, 'sales_total_cents', s.total, 'tax_cents', s.tax, 'tip_cents', s.tip,
    'revenue_cents', s.total - s.tax - s.tip,
    'expenses_count', (SELECT coalesce(sum(n), 0) FROM e),
    'expenses_cents', (SELECT coalesce(sum(c), 0) FROM e),
    'expenses_by_category', (SELECT coalesce(jsonb_object_agg(category, c), '{}'::jsonb) FROM e),
    'rough_profit_cents', s.total - s.tax - s.tip - (SELECT coalesce(sum(c), 0) FROM e))
    FROM s
$$;

-- ---------------------------------------------------------------- scan-pay-refund (service_role)

-- Claim an online payment of [p_device_sn]'s store for a refund. Returns what
-- Nuvei needs; refuses one already refunded or being refunded (2 min).
CREATE OR REPLACE FUNCTION public.dine_online_refund_begin(p_device_sn TEXT, p_ecr_ref_num TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID; v_pay dine.payments%ROWTYPE; v_o dine.online_payments%ROWTYPE;
BEGIN
  SELECT org_id INTO v_org FROM devices WHERE sn = p_device_sn;
  SELECT p.* INTO v_pay FROM dine.payments p JOIN dine.checks c ON c.id = p.check_id
   WHERE p.ecr_ref_num = p_ecr_ref_num AND p.method = 'ONLINE' AND c.org_id = v_org
   FOR UPDATE OF p;
  IF NOT FOUND OR v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'payment_not_found'); END IF;
  IF v_pay.refund_status IS NOT NULL THEN
    RETURN jsonb_build_object('success', false, 'message', 'already_' || lower(v_pay.refund_status));
  END IF;
  SELECT * INTO v_o FROM dine.online_payments
   WHERE check_id = v_pay.check_id AND status = 'PAID' AND 'NUVEI-' || transaction_id = p_ecr_ref_num
   FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'payment_not_found'); END IF;
  IF v_o.refund_started_at > now() - interval '2 minutes' THEN
    RETURN jsonb_build_object('success', false, 'message', 'refund_in_progress');
  END IF;
  UPDATE dine.online_payments SET refund_started_at = now() WHERE id = v_o.id;
  RETURN jsonb_build_object('success', true, 'id', v_o.id, 'transaction_id', v_o.transaction_id,
    'amount_cents', v_o.amount_cents + v_o.tip_cents, 'currency', v_o.currency,
    'acquirer_account_id', v_o.acquirer_account_id);
END $$;

-- Nuvei voided / refunded it: record the refund on the payment.
CREATE OR REPLACE FUNCTION public.dine_online_refund_done(p_ecr_ref_num TEXT, p_status TEXT, p_refund_transaction_id TEXT,
                                                          p_staff_id UUID, p_staff_name TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_pay dine.payments%ROWTYPE; v_org UUID; v_staff UUID;
BEGIN
  IF p_status NOT IN ('VOIDED', 'REFUNDED') THEN RETURN jsonb_build_object('success', false, 'message', 'bad_status'); END IF;
  SELECT * INTO v_pay FROM dine.payments WHERE ecr_ref_num = p_ecr_ref_num AND method = 'ONLINE' FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'payment_not_found'); END IF;
  SELECT org_id INTO v_org FROM dine.checks WHERE id = v_pay.check_id;
  -- The approver is the terminal's word (as for card refunds); keep the id only if it's this store's.
  SELECT id INTO v_staff FROM staff WHERE id = p_staff_id AND org_id = v_org;
  UPDATE dine.payments SET refund_status = p_status, refunded_at = now(),
         refund_approved_by_staff_id = v_staff, refund_approved_by_name = nullif(trim(p_staff_name), '')
   WHERE ecr_ref_num = p_ecr_ref_num AND refund_status IS NULL;
  UPDATE dine.online_payments SET refund_transaction_id = p_refund_transaction_id, refund_started_at = NULL
   WHERE check_id = v_pay.check_id AND 'NUVEI-' || transaction_id = p_ecr_ref_num;
  RETURN jsonb_build_object('success', true);
END $$;

-- Nuvei refused: the payment can be tried again at once.
CREATE OR REPLACE FUNCTION public.dine_online_refund_abort(p_ecr_ref_num TEXT)
RETURNS JSONB LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
  UPDATE dine.online_payments SET refund_started_at = NULL WHERE 'NUVEI-' || transaction_id = p_ecr_ref_num;
  SELECT jsonb_build_object('success', true);
$$;

REVOKE ALL ON FUNCTION public.dine_online_refund_begin(TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.dine_online_refund_done(TEXT, TEXT, TEXT, UUID, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.dine_online_refund_abort(TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.dine_online_refund_begin(TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.dine_online_refund_done(TEXT, TEXT, TEXT, UUID, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.dine_online_refund_abort(TEXT) TO service_role;

COMMIT;
