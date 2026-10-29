-- 2026-10-28: a refunded payment no longer counts as paid. Found testing the
-- floor's balance: a share refunded on an OPEN check still counted, so the table
-- showed $13.50 owed instead of $27.01 and could have been closed short.
--
-- Every "paid so far" sum skips dine.payments rows with a refund_status (floor,
-- Cael floor, the customer's bill, online pay open / get / settle, the terminals'
-- dine_record_payment). A check already PAID stays closed when one of its
-- payments is refunded (2026-10-28_scan_pay_refund.sql); an open one owes it again.
-- Generated from the live definitions with only those sums changed.

BEGIN;

CREATE OR REPLACE FUNCTION public.dine_floor()
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$
  SELECT jsonb_build_object(
    'tables', dine_list_tables(),
    'checks', coalesce((
      SELECT jsonb_agg(to_jsonb(c) || jsonb_build_object(
               'total_cents', dine_check_total(c.id),
               'paid_cents', (SELECT coalesce(sum(p.amount_cents), 0) FROM dine.payments p WHERE p.check_id = c.id AND p.refund_status IS NULL)))
        FROM dine.checks c
       WHERE c.org_id = dine_device_org() AND c.status IN ('OPEN', 'BILLED')), '[]'::jsonb),
    'scan_pending', dine_scan_pending(),
    'online_paid', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'ecr_ref_num', p.ecr_ref_num,
               'table_name', t.name,
               'check_no', c.check_no,
               'amount_cents', p.amount_cents + p.tip_cents,
               'check_closed', c.status = 'PAID',
               'balance_cents', greatest(dine_check_total(c.id) - (SELECT coalesce(sum(q.amount_cents), 0) FROM dine.payments q WHERE q.check_id = c.id AND q.refund_status IS NULL), 0),
               'created_at', p.created_at) ORDER BY p.created_at DESC)
        FROM dine.payments p JOIN dine.checks c ON c.id = p.check_id JOIN dine.tables t ON t.id = c.table_id
       WHERE c.org_id = dine_device_org() AND p.method = 'ONLINE' AND p.refund_status IS NULL
         AND p.created_at > now() - interval '30 minutes'), '[]'::jsonb))
$function$;

CREATE OR REPLACE FUNCTION public.cmp_dine_floor(p_org uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$

BEGIN

  IF NOT cmp_dine_can(p_org, false) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;

  RETURN jsonb_build_object(

    'tables', coalesce((SELECT jsonb_agg(jsonb_build_object('id', t.id, 'name', t.name, 'area', t.area, 'seats', t.seats)

                                ORDER BY t.area NULLS FIRST, t.sort, t.name)

                          FROM dine.tables t WHERE t.org_id = p_org AND t.is_active), '[]'::jsonb),

    'checks', coalesce((SELECT jsonb_agg(jsonb_build_object(

                                'id', c.id, 'table_id', c.table_id, 'check_no', c.check_no, 'status', c.status,

                                'guests', c.guests, 'server_name', c.server_name, 'opened_at', c.opened_at,

                                'total_cents', dine_check_total(c.id),

                                'paid_cents', (SELECT coalesce(sum(amount_cents), 0) FROM dine.payments p WHERE p.check_id = c.id AND p.refund_status IS NULL)))

                          FROM dine.checks c WHERE c.org_id = p_org AND c.status IN ('OPEN', 'BILLED')), '[]'::jsonb),

    'scan_pending', coalesce((SELECT jsonb_agg(jsonb_build_object(

                                'id', s.id, 'table_id', s.table_id, 'table_name', t.name, 'items', s.items, 'note', s.note,

                                'subtotal_cents', s.subtotal_cents, 'tax_cents', s.tax_cents, 'created_at', s.created_at,

                                'approved_at', s.approved_at, 'approved_by', s.approved_by) ORDER BY s.created_at)

                          FROM dine.scan_orders s JOIN dine.tables t ON t.id = s.table_id

                         WHERE s.org_id = p_org AND s.status = 'PENDING'), '[]'::jsonb));

END $function$;

CREATE OR REPLACE FUNCTION public.scan_bill(p_token text)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$

  WITH t AS (SELECT * FROM dine.tables WHERE qr_token = p_token AND is_active),

  c AS (SELECT c.* FROM dine.checks c JOIN t ON c.table_id = t.id WHERE c.status IN ('OPEN', 'BILLED')),

  tot AS (

    SELECT coalesce(sum(s), 0)::INT AS subtotal, coalesce(sum(round(s * b / 10000.0)), 0)::INT AS tax

      FROM (SELECT i.tax_rate_bps b, sum(i.quantity * i.unit_price_cents) s

              FROM dine.check_items i JOIN c ON i.check_id = c.id WHERE i.status <> 'VOID' GROUP BY 1) r),

  paid AS (SELECT coalesce(sum(p.amount_cents), 0)::INT AS amt, coalesce(sum(p.tax_cents), 0)::INT AS tax

             FROM dine.payments p JOIN c ON p.check_id = c.id WHERE p.refund_status IS NULL)

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

$function$;

CREATE OR REPLACE FUNCTION public.dine_online_pay_open(p_token text, p_amount_cents integer, p_tip_cents integer)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$

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

    FROM dine.payments WHERE check_id = v_check.id AND refund_status IS NULL;

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

END $function$;

CREATE OR REPLACE FUNCTION public.dine_online_pay_get(p_id uuid, p_token text, p_claim_status_check boolean)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$

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

    'balance_cents', greatest(dine_check_total(v_p.check_id) - (SELECT coalesce(sum(amount_cents), 0) FROM dine.payments WHERE check_id = v_p.check_id AND refund_status IS NULL), 0));

END $function$;

CREATE OR REPLACE FUNCTION public.dine_online_pay_settle(p_id uuid, p_transaction_id text, p_paid_cents integer, p_auth_code text, p_card text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$
DECLARE v_p dine.online_payments%ROWTYPE; v_check dine.checks%ROWTYPE; v_ref TEXT; v_total INT; v_paid INT; v_sn TEXT;
BEGIN
  SELECT * INTO v_p FROM dine.online_payments WHERE id = p_id FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'payment_not_found'); END IF;
  IF v_p.status = 'PAID' THEN
    -- Already recorded (scan-pay's confirm and Nuvei's DMN both arrive): same answer as the first time.
    RETURN jsonb_build_object('success', true, 'duplicate', true, 'balance_cents',
      greatest(dine_check_total(v_p.check_id) - (SELECT coalesce(sum(amount_cents), 0) FROM dine.payments WHERE check_id = v_p.check_id AND refund_status IS NULL), 0));
  END IF;
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

  -- The store's Ourea: the one that opened the check, else the last seen;
  -- a store without an Ourea gets the terminal that opened the check.
  SELECT * INTO v_check FROM dine.checks WHERE id = v_p.check_id;
  SELECT d.sn INTO v_sn FROM devices d
   WHERE d.org_id = v_p.org_id AND d.is_active AND d.app_version LIKE '%-ourea'
   ORDER BY (d.sn = v_check.opened_by_sn) DESC, d.last_seen DESC NULLS LAST LIMIT 1;
  IF v_sn IS NULL THEN
    SELECT sn INTO v_sn FROM devices WHERE sn = v_check.opened_by_sn;
  END IF;
  INSERT INTO dine.payments (check_id, ecr_ref_num, amount_cents, tax_cents, tip_cents, method, paid_by, device_sn)
  VALUES (v_p.check_id, v_ref, v_p.amount_cents, v_p.tax_cents, v_p.tip_cents, 'ONLINE', 'QR', v_sn)
  ON CONFLICT (ecr_ref_num) DO NOTHING;

  v_total := dine_check_total(v_p.check_id);
  SELECT coalesce(sum(amount_cents), 0) INTO v_paid FROM dine.payments WHERE check_id = v_p.check_id AND refund_status IS NULL;
  UPDATE dine.checks SET status = CASE WHEN status IN ('OPEN', 'BILLED') AND v_paid >= v_total AND v_total > 0 THEN 'PAID' ELSE status END,
         closed_at = CASE WHEN status IN ('OPEN', 'BILLED') AND v_paid >= v_total AND v_total > 0 THEN now() ELSE closed_at END,
         updated_at = now()
   WHERE id = v_p.check_id;
  RETURN jsonb_build_object('success', true, 'balance_cents', greatest(v_total - v_paid, 0));
END $function$;

CREATE OR REPLACE FUNCTION public.dine_record_payment(p_check_id uuid, p_ecr_ref_num text, p_amount_cents integer, p_tax_cents integer, p_tip_cents integer, p_method text, p_paid_by text, p_item_ids uuid[])
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$
DECLARE v_org UUID := dine_device_org(); v_check dine.checks%ROWTYPE; v_total INT; v_paid INT; v_sn TEXT;
BEGIN
  SELECT * INTO v_check FROM dine.checks WHERE id = p_check_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'check_not_found'); END IF;
  IF coalesce(p_amount_cents, 0) <= 0 OR coalesce(trim(p_ecr_ref_num), '') = '' THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_payment');
  END IF;
  -- The calling terminal, as dine_device_org picks it.
  SELECT m.device_sn INTO v_sn FROM device_auth_map m JOIN devices d ON d.sn = m.device_sn
   WHERE m.auth_user_id = auth.uid() AND m.org_id IS NOT NULL
   ORDER BY m.created_at DESC LIMIT 1;
  INSERT INTO dine.payments (check_id, ecr_ref_num, amount_cents, tax_cents, tip_cents, method, paid_by, device_sn)
  VALUES (p_check_id, trim(p_ecr_ref_num), p_amount_cents, greatest(coalesce(p_tax_cents, 0), 0), greatest(coalesce(p_tip_cents, 0), 0), p_method, nullif(trim(p_paid_by), ''), v_sn)
  ON CONFLICT (ecr_ref_num) DO NOTHING;
  IF p_item_ids IS NOT NULL THEN
    UPDATE dine.check_items SET paid_ecr_ref_num = trim(p_ecr_ref_num)
     WHERE check_id = p_check_id AND id = ANY (p_item_ids) AND paid_ecr_ref_num IS NULL;
  END IF;
  v_total := dine_check_total(p_check_id);
  SELECT coalesce(sum(amount_cents), 0) INTO v_paid FROM dine.payments WHERE check_id = p_check_id AND refund_status IS NULL;
  IF v_paid >= v_total AND v_total > 0 THEN
    UPDATE dine.checks SET status = 'PAID', closed_at = coalesce(closed_at, now()), updated_at = now() WHERE id = p_check_id;
  ELSE
    UPDATE dine.checks SET updated_at = now() WHERE id = p_check_id;
  END IF;
  UPDATE public.transactions SET check_id = p_check_id WHERE ecr_ref_num = trim(p_ecr_ref_num) AND check_id IS NULL;
  RETURN jsonb_build_object('success', true, 'total_cents', v_total, 'paid_cents', v_paid,
                            'balance_cents', greatest(v_total - v_paid, 0), 'closed', v_paid >= v_total AND v_total > 0);
END $function$;

COMMIT;
