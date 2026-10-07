-- 2026-10-07: get_vip_card_by_uid also returns qr_code -- the 6-character
-- member code customers actually have. The terminal showed card_uid
-- (VC + 14 hex) as "Card No.", which matched nothing the customer holds.
CREATE OR REPLACE FUNCTION public.get_vip_card_by_uid(p_card_uid text)
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_card RECORD;
BEGIN
  SELECT card_uid, balance_cents, is_active, display_card_number, qr_code
  INTO v_card
  FROM public.vip_cards
  WHERE card_uid = p_card_uid;
  IF NOT FOUND THEN
    RETURN json_build_object('found', false);
  END IF;
  RETURN json_build_object(
    'found', true,
    'card_uid', v_card.card_uid,
    'balance_cents', v_card.balance_cents,
    'is_active', v_card.is_active,
    'tier', 'REGULAR',
    'display_card_number', v_card.display_card_number,
    'qr_code', v_card.qr_code
  );
END;
$function$;
