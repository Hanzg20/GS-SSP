-- 2026-10-06: restore the vip_card_ledger DEDUCT row in deduct_vip_balance.
--
-- 2026-09-20 (vip_card_ledger migration) made every successful deduction
-- write a DEDUCT ledger row. The 2026-09-22 cross-org fix (b641d0d, applied
-- live 9/23) re-created the function from docs/supabase_full_schema.sql,
-- whose copy predated the ledger, so from then on deductions still moved
-- the balance but left no history (last DEDUCT row: 2026-09-22 16:44).
-- Found 2026-10-06 when a VIP-paid wash between two terminal loads showed
-- up in the balance but not in the card history. This is the live function
-- (with the org check) plus the ledger insert.

CREATE OR REPLACE FUNCTION public.deduct_vip_balance(p_card_uid text, p_amount_cents integer)
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_balance_cents INT;
  v_active BOOLEAN;
  v_expiration DATE;
  v_max_daily_cents INT;
  v_daily_spent_cents INT;
  v_daily_spent_date DATE;
  v_org_id UUID;
BEGIN
  IF p_amount_cents <= 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;
  SELECT balance_cents, is_active, card_expiration_date, max_daily_cents, daily_spent_cents, daily_spent_date, org_id
  INTO v_balance_cents, v_active, v_expiration, v_max_daily_cents, v_daily_spent_cents, v_daily_spent_date, v_org_id
  FROM public.vip_cards
  WHERE card_uid = p_card_uid
  FOR UPDATE;
  IF v_balance_cents IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;
  IF v_org_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.device_auth_map
    WHERE auth_user_id = auth.uid() AND org_id = v_org_id
  ) THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;
  IF NOT v_active THEN
    RETURN json_build_object('success', false, 'message', 'card_inactive');
  END IF;
  IF v_expiration IS NOT NULL AND v_expiration < CURRENT_DATE THEN
    RETURN json_build_object('success', false, 'message', 'card_expired');
  END IF;
  IF v_balance_cents < p_amount_cents THEN
    RETURN json_build_object('success', false, 'message', 'insufficient_balance');
  END IF;
  IF v_daily_spent_date IS DISTINCT FROM CURRENT_DATE THEN
    v_daily_spent_cents := 0;
  END IF;
  IF v_max_daily_cents IS NOT NULL AND v_daily_spent_cents + p_amount_cents > v_max_daily_cents THEN
    RETURN json_build_object('success', false, 'message', 'daily_limit_exceeded');
  END IF;
  UPDATE public.vip_cards
  SET balance_cents = balance_cents - p_amount_cents,
      daily_spent_cents = v_daily_spent_cents + p_amount_cents,
      daily_spent_date = CURRENT_DATE
  WHERE card_uid = p_card_uid;
  INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents)
  VALUES (p_card_uid, v_org_id, 'DEDUCT', p_amount_cents, v_balance_cents, v_balance_cents - p_amount_cents);

  RETURN json_build_object('success', true, 'new_balance_cents', v_balance_cents - p_amount_cents);
END;
$function$;
