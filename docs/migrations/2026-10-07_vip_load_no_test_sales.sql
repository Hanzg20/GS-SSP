-- 2026-10-07: device_vip_load refuses technician test sales (TEST_ refs).
-- Previously a $0.10 test credited $0.10 to the card; test amounts must
-- never become VIP balance. The terminal (1.1.3 / 1.2.2) also no longer
-- applies the test amount to VIP purchases at all.
CREATE OR REPLACE FUNCTION public.device_vip_load(p_ecr_ref_num text, p_plan_id uuid, p_card_uid text DEFAULT NULL::text, p_mobile_phone text DEFAULT NULL::text)
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_sn TEXT;
  v_org_id UUID;
  v_tx RECORD;
  v_plan RECORD;
  v_card RECORD;
  v_load INT;
  v_bonus INT;
  v_uid TEXT;
  v_qr TEXT;
  v_phone TEXT;
  v_created BOOLEAN := false;
  v_done RECORD;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;
  SELECT device_sn, org_id INTO v_sn, v_org_id FROM public.device_auth_map WHERE auth_user_id = auth.uid();
  IF v_sn IS NULL OR v_org_id IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'device_not_bound');
  END IF;

  SELECT id, amount, payment_status, txn_kind INTO v_tx
  FROM public.transactions
  WHERE ecr_ref_num = p_ecr_ref_num AND device_sn = v_sn
  FOR UPDATE;
  IF v_tx.id IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'sale_not_found');
  END IF;

  -- Already loaded (a retry after a lost response): answer with that result.
  SELECT l.card_uid, c.qr_code, c.balance_cents INTO v_done
  FROM public.vip_card_ledger l JOIN public.vip_cards c ON c.card_uid = l.card_uid
  WHERE l.source_ref = p_ecr_ref_num AND l.kind = 'LOAD';
  IF v_done.card_uid IS NOT NULL THEN
    RETURN json_build_object('success', true, 'already_loaded', true, 'card_uid', v_done.card_uid,
                             'qr_code', v_done.qr_code, 'balance_cents', v_done.balance_cents);
  END IF;

  IF v_tx.payment_status <> 'PAID' OR v_tx.txn_kind <> 'VIP_LOAD' THEN
    RETURN json_build_object('success', false, 'message', 'sale_not_loadable');
  END IF;

  SELECT id, amount_cents, bonus_cents INTO v_plan
  FROM public.vip_load_plans
  WHERE id = p_plan_id AND org_id = v_org_id AND is_active;
  IF v_plan.id IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'plan_not_found');
  END IF;

  -- Technician test sales (TEST_, a few cents) never become VIP balance
  -- (2026-10-07): the terminal reverses the charge on this rejection.
  IF p_ecr_ref_num LIKE 'TEST\_%' THEN
    RETURN json_build_object('success', false, 'message', 'test_sale_not_allowed');
  END IF;
  IF v_tx.amount <> v_plan.amount_cents THEN
    RETURN json_build_object('success', false, 'message', 'amount_mismatch');
  END IF;
  v_load := v_plan.amount_cents;
  v_bonus := v_plan.bonus_cents;

  IF p_card_uid IS NULL THEN
    v_phone := NULLIF(regexp_replace(COALESCE(p_mobile_phone, ''), '[^0-9+]', '', 'g'), '');
    LOOP
      v_uid := 'VC' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 14));
      EXIT WHEN NOT EXISTS (SELECT 1 FROM public.vip_cards WHERE card_uid = v_uid);
    END LOOP;
    LOOP
      SELECT string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', floor(random() * 32)::int + 1, 1), '')
      INTO v_qr FROM generate_series(1, 6);
      EXIT WHEN NOT EXISTS (SELECT 1 FROM public.vip_cards WHERE qr_code = v_qr);
    END LOOP;
    INSERT INTO public.vip_cards (card_uid, org_id, balance_cents, is_active, qr_code, mobile_phone)
    VALUES (v_uid, v_org_id, 0, true, v_qr, v_phone);
    v_created := true;
  ELSE
    v_uid := p_card_uid;
  END IF;

  SELECT card_uid, org_id, balance_cents, is_active, qr_code INTO v_card
  FROM public.vip_cards WHERE card_uid = v_uid FOR UPDATE;
  -- Same answer for "no such card" and "another merchant's card" (no enumeration).
  IF v_card.card_uid IS NULL OR v_card.org_id IS DISTINCT FROM v_org_id THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;
  IF NOT v_card.is_active THEN
    RETURN json_build_object('success', false, 'message', 'card_inactive');
  END IF;

  UPDATE public.vip_cards SET balance_cents = balance_cents + v_load + v_bonus WHERE card_uid = v_uid;
  INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, source_ref)
  VALUES (v_uid, v_org_id, 'LOAD', v_load, v_card.balance_cents, v_card.balance_cents + v_load, p_ecr_ref_num);
  IF v_bonus > 0 THEN
    INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, source_ref)
    VALUES (v_uid, v_org_id, 'BONUS', v_bonus, v_card.balance_cents + v_load, v_card.balance_cents + v_load + v_bonus, p_ecr_ref_num);
  END IF;
  UPDATE public.transactions SET vip_card_uid = v_uid WHERE id = v_tx.id;

  RETURN json_build_object(
    'success', true, 'created', v_created, 'card_uid', v_uid, 'qr_code', v_card.qr_code,
    'loaded_cents', v_load, 'bonus_cents', v_bonus, 'balance_cents', v_card.balance_cents + v_load + v_bonus
  );
END;
$function$;
