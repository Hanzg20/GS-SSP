-- 2026-10-28: dine-in keeps its own transactions (owner: each vertical has its
-- own transaction table) -- dine.payments is dine's, public.transactions is not
-- written by dine anymore for online payments.
--
-- * dine.payments.device_sn: the terminal a payment belongs to. Ourea / Iris
--   payments (dine_record_payment): the calling terminal. Ourea Scan online
--   payments (dine_online_pay_settle): the store's Ourea (app_version
--   '*-ourea'; the one that opened the check, else the last seen), or the
--   terminal that opened the check when the store has no Ourea.
-- * dine_online_pay_settle no longer inserts into public.transactions
--   (2026-10-28_scan_pay.sql did; nothing had been paid online when this replaced it).

BEGIN;

ALTER TABLE dine.payments ADD COLUMN IF NOT EXISTS device_sn TEXT REFERENCES public.devices(sn) ON DELETE SET NULL;
COMMENT ON COLUMN dine.payments.device_sn IS
  'The terminal the payment belongs to: the Ourea / Iris that took it, or for an Ourea Scan online payment the store''s Ourea';

CREATE OR REPLACE FUNCTION public.dine_record_payment(p_check_id UUID, p_ecr_ref_num TEXT, p_amount_cents INT, p_tax_cents INT, p_tip_cents INT,
                                                      p_method TEXT, p_paid_by TEXT, p_item_ids UUID[])
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
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
  SELECT coalesce(sum(amount_cents), 0) INTO v_paid FROM dine.payments WHERE check_id = p_check_id;
  IF v_paid >= v_total AND v_total > 0 THEN
    UPDATE dine.checks SET status = 'PAID', closed_at = coalesce(closed_at, now()), updated_at = now() WHERE id = p_check_id;
  ELSE
    UPDATE dine.checks SET updated_at = now() WHERE id = p_check_id;
  END IF;
  UPDATE public.transactions SET check_id = p_check_id WHERE ecr_ref_num = trim(p_ecr_ref_num) AND check_id IS NULL;
  RETURN jsonb_build_object('success', true, 'total_cents', v_total, 'paid_cents', v_paid,
                            'balance_cents', greatest(v_total - v_paid, 0), 'closed', v_paid >= v_total AND v_total > 0);
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
  IF v_p.status = 'PAID' THEN
    -- Already recorded (scan-pay's confirm and Nuvei's DMN both arrive): same answer as the first time.
    RETURN jsonb_build_object('success', true, 'duplicate', true, 'balance_cents',
      greatest(dine_check_total(v_p.check_id) - (SELECT coalesce(sum(amount_cents), 0) FROM dine.payments WHERE check_id = v_p.check_id), 0));
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
  SELECT coalesce(sum(amount_cents), 0) INTO v_paid FROM dine.payments WHERE check_id = v_p.check_id;
  UPDATE dine.checks SET status = CASE WHEN status IN ('OPEN', 'BILLED') AND v_paid >= v_total AND v_total > 0 THEN 'PAID' ELSE status END,
         closed_at = CASE WHEN status IN ('OPEN', 'BILLED') AND v_paid >= v_total AND v_total > 0 THEN now() ELSE closed_at END,
         updated_at = now()
   WHERE id = v_p.check_id;
  RETURN jsonb_build_object('success', true, 'balance_cents', greatest(v_total - v_paid, 0));
END $$;

REVOKE ALL ON FUNCTION public.dine_online_pay_settle(UUID, TEXT, INT, TEXT, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.dine_online_pay_settle(UUID, TEXT, INT, TEXT, TEXT) TO service_role;

COMMIT;
