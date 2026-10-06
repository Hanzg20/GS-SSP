-- 2026-10-06: staff balance changes in the VIP card history.
--
-- Card history (vip_card_ledger) recorded terminal deductions, loads and
-- bonuses, but not what staff do in CMP: the opening balance given when a
-- card is created (admin_create_vip_card) and staff top-ups
-- (admin_topup_vip_card) changed the balance with no history entry, so a
-- card's history did not add up to its balance. Both now write an entry
-- (OPENING / ADMIN_TOPUP, with the staff member in actor_profile_id) in
-- the same transaction, and past ones are backfilled from audit_logs.

ALTER TABLE public.vip_card_ledger ADD COLUMN IF NOT EXISTS actor_profile_id UUID;
ALTER TABLE public.vip_card_ledger DROP CONSTRAINT IF EXISTS vip_card_ledger_kind_check;
ALTER TABLE public.vip_card_ledger ADD CONSTRAINT vip_card_ledger_kind_check
    CHECK (kind IN ('DEDUCT', 'LOAD', 'BONUS', 'OPENING', 'ADMIN_TOPUP'));

-- Staff top-up: same checks as before, now with the history entry.
CREATE OR REPLACE FUNCTION public.admin_topup_vip_card(p_card_uid text, p_amount_cents integer)
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_org_id UUID;
  v_before INT;
  v_new_balance INT;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;
  IF p_amount_cents <= 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;
  SELECT org_id, balance_cents INTO v_org_id, v_before FROM public.vip_cards WHERE card_uid = p_card_uid FOR UPDATE;
  IF v_org_id IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;
  IF NOT public.has_permission_for_org('vip.topup', v_org_id) THEN
    RETURN json_build_object('success', false, 'message', 'not_authorized');
  END IF;
  UPDATE public.vip_cards SET balance_cents = balance_cents + p_amount_cents
  WHERE card_uid = p_card_uid
  RETURNING balance_cents INTO v_new_balance;
  INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, actor_profile_id)
  VALUES (p_card_uid, v_org_id, 'ADMIN_TOPUP', p_amount_cents, v_before, v_new_balance, auth.uid());
  INSERT INTO public.audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), v_org_id, 'TOPUP_VIP_CARD', 'vip_cards', p_card_uid,
    json_build_object('amount_cents', p_amount_cents, 'new_balance_cents', v_new_balance));
  RETURN json_build_object('success', true, 'new_balance_cents', v_new_balance);
END;
$function$;

-- Card creation: opening balance into the history.
CREATE OR REPLACE FUNCTION public.admin_create_vip_card(p_org_id uuid, p_card_uid text, p_initial_balance_cents integer DEFAULT 0, p_display_card_number text DEFAULT NULL::text, p_cardholder_name text DEFAULT NULL::text, p_mobile_phone text DEFAULT NULL::text, p_card_expiration_date date DEFAULT NULL::date, p_max_daily_cents integer DEFAULT NULL::integer)
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_qr_code TEXT;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;
  IF p_initial_balance_cents < 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;
  IF p_max_daily_cents IS NOT NULL AND p_max_daily_cents <= 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_max_daily');
  END IF;
  IF NOT public.has_permission_for_org('vip.manage', p_org_id) THEN
    RETURN json_build_object('success', false, 'message', 'not_authorized');
  END IF;
  -- Shortened 6 chars (was 12) 2026-09-20, per the same "too long to read
  -- off a kiosk screen" product requirement as the coupon-code shortening.
  -- Still never collides with an 8-char coupon code on length (6 != 8),
  -- which is all the client's format-based routing regex ever needed.
  -- floor(), not a bare ::int cast -- Postgres rounds a float->int cast to
  -- the nearest integer rather than truncating, so ::int alone occasionally
  -- yields 36 (when random()*36 lands in [35.5, 36)), an out-of-range substr
  -- position that silently returns NULL and gets dropped by string_agg,
  -- producing a short code (caught live: "HNYN0F93N3Y" before this fix, back
  -- when the code was still 12 chars).
  LOOP
    SELECT string_agg(
      substr('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789', floor(random() * 36)::int + 1, 1), ''
    ) INTO v_qr_code FROM generate_series(1, 6);
    EXIT WHEN NOT EXISTS (SELECT 1 FROM public.vip_cards WHERE qr_code = v_qr_code);
  END LOOP;
  BEGIN
    INSERT INTO public.vip_cards (
      card_uid, org_id, balance_cents, is_active, qr_code,
      display_card_number, cardholder_name, mobile_phone, card_expiration_date, max_daily_cents
    )
    VALUES (
      p_card_uid, p_org_id, p_initial_balance_cents, true, v_qr_code,
      p_display_card_number, p_cardholder_name, p_mobile_phone, p_card_expiration_date, p_max_daily_cents
    );
  EXCEPTION WHEN unique_violation THEN
    RETURN json_build_object('success', false, 'message', 'card_uid_qr_code_or_display_number_exists');
  END;
  -- Card history starts with the opening balance (2026-10-06).
  IF p_initial_balance_cents > 0 THEN
    INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, actor_profile_id)
    VALUES (p_card_uid, p_org_id, 'OPENING', p_initial_balance_cents, 0, p_initial_balance_cents, auth.uid());
  END IF;
  INSERT INTO public.audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), p_org_id, 'CREATE_VIP_CARD', 'vip_cards', p_card_uid,
    json_build_object('initial_balance_cents', p_initial_balance_cents, 'max_daily_cents', p_max_daily_cents));
  RETURN json_build_object('success', true, 'card_uid', p_card_uid, 'qr_code', v_qr_code);
END;
$function$;

-- Backfill from audit_logs (cards that still exist; skipped if already there).
INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, actor_profile_id, created_at)
SELECT a.target_id, a.org_id, 'OPENING', (a.details->>'initial_balance_cents')::int, 0,
       (a.details->>'initial_balance_cents')::int, a.actor_profile_id, a.created_at
FROM public.audit_logs a
JOIN public.vip_cards c ON c.card_uid = a.target_id
WHERE a.action = 'CREATE_VIP_CARD' AND COALESCE((a.details->>'initial_balance_cents')::int, 0) > 0
  AND NOT EXISTS (SELECT 1 FROM public.vip_card_ledger l WHERE l.card_uid = a.target_id AND l.kind = 'OPENING');

INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, actor_profile_id, created_at)
SELECT a.target_id, a.org_id, 'ADMIN_TOPUP', (a.details->>'amount_cents')::int,
       (a.details->>'new_balance_cents')::int - (a.details->>'amount_cents')::int,
       (a.details->>'new_balance_cents')::int, a.actor_profile_id, a.created_at
FROM public.audit_logs a
JOIN public.vip_cards c ON c.card_uid = a.target_id
WHERE a.action = 'TOPUP_VIP_CARD'
  AND NOT EXISTS (SELECT 1 FROM public.vip_card_ledger l
                  WHERE l.card_uid = a.target_id AND l.kind = 'ADMIN_TOPUP' AND l.created_at = a.created_at);
