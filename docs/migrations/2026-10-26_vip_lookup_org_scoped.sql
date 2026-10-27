-- VIP card lookups scoped to the calling terminal's organization (owner, 2026-10-26).
--
-- get_vip_card_by_uid / resolve_vip_card_uid_by_qr used to answer for ANY organization's
-- card, so a terminal could scan another org's member code and show its balance -- only
-- deduct_vip_balance (org-checked through device_auth_map since 2026-10-06) then refused
-- it, after the customer had already confirmed. Now both lookups apply the same check:
-- a card of another organization is "not found" (no balance, and no hint that it exists
-- elsewhere). Callers are the terminal apps only (Wash, Timer, Laundry, Ourea, VIP
-- purchase); Cael does not use these functions.
--
-- Bodies are the live definitions of 2026-10-26 plus the org check; signatures, return
-- shapes and grants are unchanged.

BEGIN;

CREATE OR REPLACE FUNCTION public.get_vip_card_by_uid(p_card_uid text)
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_card RECORD;
BEGIN
  SELECT c.card_uid, c.balance_cents, c.is_active, c.display_card_number, c.qr_code
  INTO v_card
  FROM public.vip_cards c
  WHERE c.card_uid = p_card_uid
    AND c.org_id IS NOT NULL
    AND EXISTS (
      SELECT 1 FROM public.device_auth_map m
      WHERE m.auth_user_id = auth.uid() AND m.org_id = c.org_id
    );
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

CREATE OR REPLACE FUNCTION public.resolve_vip_card_uid_by_qr(p_qr_code text)
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_uid TEXT;
BEGIN
  SELECT c.card_uid INTO v_uid
  FROM public.vip_cards c
  WHERE c.qr_code = p_qr_code
    AND c.org_id IS NOT NULL
    AND EXISTS (
      SELECT 1 FROM public.device_auth_map m
      WHERE m.auth_user_id = auth.uid() AND m.org_id = c.org_id
    );
  RETURN json_build_object('card_uid', v_uid);
END;
$function$;

COMMENT ON FUNCTION public.get_vip_card_by_uid(text) IS
  'Terminal VIP lookup by card_uid. Only cards of the calling terminal''s organization (device_auth_map of auth.uid()); any other card is found=false.';
COMMENT ON FUNCTION public.resolve_vip_card_uid_by_qr(text) IS
  'Terminal member-code (QR) to card_uid. Only cards of the calling terminal''s organization (device_auth_map of auth.uid()); any other code gives card_uid null.';

COMMIT;

NOTIFY pgrst, 'reload schema';
