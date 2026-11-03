-- 2026-11-03: VIP balance payments with an order reference, and their refund.
--
-- Aegis Vend takes the VIP card (member QR) as a payment: the balance is deducted, then the
-- machine vends; if the item doesn't drop the money must go back -- "no item, no charge".
-- deduct_vip_balance has no reference, so (a) a retry after a lost response could deduct
-- twice and (b) nothing ties a refund to what was taken. Two new device RPCs:
--
--   vip_deduct(p_card_uid, p_amount_cents, p_ref)  same checks as deduct_vip_balance; the same
--       p_ref twice deducts once (the second call answers success with the current balance).
--   vip_refund(p_card_uid, p_ref)  gives back exactly what that p_ref deducted, once; the
--       daily spend goes down with it. No such deduct: success=false, message='not_found'
--       (a crash before the deduct reached the server -- nothing to give back).
--
-- Both: SECURITY DEFINER, the calling device must belong to the card's store (device_auth_map),
-- recorded in vip_card_ledger (source_ref = p_ref; uq_vip_card_ledger_source makes it once-only).
-- deduct_vip_balance stays for Wash as it is.

BEGIN;

ALTER TABLE public.vip_card_ledger DROP CONSTRAINT IF EXISTS vip_card_ledger_kind_check;
ALTER TABLE public.vip_card_ledger ADD CONSTRAINT vip_card_ledger_kind_check
  CHECK (kind = ANY (ARRAY['DEDUCT','LOAD','BONUS','OPENING','ADMIN_TOPUP','REFUND']));

CREATE OR REPLACE FUNCTION public.vip_deduct(p_card_uid text, p_amount_cents integer, p_ref text)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
  v_balance_cents INT;
  v_active BOOLEAN;
  v_expiration DATE;
  v_max_daily_cents INT;
  v_daily_spent_cents INT;
  v_daily_spent_date DATE;
  v_org_id UUID;
BEGIN
  IF p_amount_cents IS NULL OR p_amount_cents <= 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;
  IF p_ref IS NULL OR length(trim(p_ref)) = 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_ref');
  END IF;
  SELECT balance_cents, is_active, card_expiration_date, max_daily_cents, daily_spent_cents, daily_spent_date, org_id
  INTO v_balance_cents, v_active, v_expiration, v_max_daily_cents, v_daily_spent_cents, v_daily_spent_date, v_org_id
  FROM public.vip_cards
  WHERE card_uid = p_card_uid
  FOR UPDATE;
  IF v_balance_cents IS NULL OR v_org_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.device_auth_map WHERE auth_user_id = auth.uid() AND org_id = v_org_id
  ) THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;
  -- Already taken for this order (a retry): don't take it again.
  IF EXISTS (SELECT 1 FROM public.vip_card_ledger WHERE source_ref = p_ref AND kind = 'DEDUCT' AND card_uid = p_card_uid) THEN
    RETURN json_build_object('success', true, 'new_balance_cents', v_balance_cents, 'duplicate', true);
  END IF;
  IF NOT v_active THEN
    RETURN json_build_object('success', false, 'message', 'card_inactive');
  END IF;
  IF v_expiration IS NOT NULL AND v_expiration < CURRENT_DATE THEN
    RETURN json_build_object('success', false, 'message', 'card_expired');
  END IF;
  IF v_balance_cents < p_amount_cents THEN
    RETURN json_build_object('success', false, 'message', 'insufficient_balance', 'balance_cents', v_balance_cents);
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
  INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, source_ref)
  VALUES (p_card_uid, v_org_id, 'DEDUCT', p_amount_cents, v_balance_cents, v_balance_cents - p_amount_cents, p_ref);
  RETURN json_build_object('success', true, 'new_balance_cents', v_balance_cents - p_amount_cents);
END;
$$;

CREATE OR REPLACE FUNCTION public.vip_refund(p_card_uid text, p_ref text)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
  v_balance_cents INT;
  v_org_id UUID;
  v_amount INT;
  v_deducted_on DATE;
BEGIN
  SELECT balance_cents, org_id INTO v_balance_cents, v_org_id
  FROM public.vip_cards WHERE card_uid = p_card_uid FOR UPDATE;
  IF v_balance_cents IS NULL OR v_org_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.device_auth_map WHERE auth_user_id = auth.uid() AND org_id = v_org_id
  ) THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;
  SELECT amount_cents, (created_at AT TIME ZONE 'America/Toronto')::date INTO v_amount, v_deducted_on
  FROM public.vip_card_ledger
  WHERE source_ref = p_ref AND kind = 'DEDUCT' AND card_uid = p_card_uid;
  IF v_amount IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_found');
  END IF;
  IF EXISTS (SELECT 1 FROM public.vip_card_ledger WHERE source_ref = p_ref AND kind = 'REFUND') THEN
    RETURN json_build_object('success', true, 'new_balance_cents', v_balance_cents, 'duplicate', true);
  END IF;
  UPDATE public.vip_cards
  SET balance_cents = balance_cents + v_amount,
      -- Today's spend only goes down for a deduct made today.
      daily_spent_cents = CASE WHEN daily_spent_date = CURRENT_DATE AND v_deducted_on = CURRENT_DATE
                               THEN greatest(daily_spent_cents - v_amount, 0) ELSE daily_spent_cents END
  WHERE card_uid = p_card_uid;
  INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, source_ref)
  VALUES (p_card_uid, v_org_id, 'REFUND', v_amount, v_balance_cents, v_balance_cents + v_amount, p_ref);
  RETURN json_build_object('success', true, 'new_balance_cents', v_balance_cents + v_amount, 'refunded_cents', v_amount);
END;
$$;

COMMENT ON FUNCTION public.vip_deduct(text, integer, text) IS
  'Device: pay with a VIP card for order p_ref (once per p_ref). Aegis Vend, 2026-11-03.';
COMMENT ON FUNCTION public.vip_refund(text, text) IS
  'Device: give back what order p_ref deducted from a VIP card, once (item not vended). Aegis Vend, 2026-11-03.';

REVOKE ALL ON FUNCTION public.vip_deduct(text, integer, text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.vip_refund(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.vip_deduct(text, integer, text) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.vip_refund(text, text) TO authenticated, service_role;

COMMIT;
