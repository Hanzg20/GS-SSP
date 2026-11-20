-- Uber Eats step 3 (2026-11-20, docs/uber_eats_design.md): Ourea accepts / rejects / readies Uber orders.
-- The store's tap goes to supabase/functions/ubereats-action, which asks Uber first (accept_pos_order,
-- deny_pos_order, cancel) and only on Uber's yes moves our order -- through the same steps as Arke
-- (online.order_step_core with p_platform), never through online_store_step (still refuses platform orders).

-- The steps, for any channel when p_platform (only ubereats-action, after Uber said yes).
CREATE OR REPLACE FUNCTION online.order_step_core(p_org uuid, p_id uuid, p_step text, p_minutes integer, p_reason text, p_staff_name text, p_device_sn text, p_platform boolean)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_org UUID := p_org; v_o online.orders%ROWTYPE; v_staff TEXT := nullif(left(trim(coalesce(p_staff_name, '')), 40), '');
        v_sn TEXT := nullif(trim(coalesce(p_device_sn, '')), ''); v_delivery BOOLEAN;
BEGIN
  SELECT * INTO v_o FROM online.orders WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_o.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'order_not_found'); END IF;
  IF v_o.channel <> 'ARKE' AND NOT p_platform THEN RETURN jsonb_build_object('success', false, 'message', 'order_on_platform'); END IF;
  v_delivery := v_o.fulfilment = 'OWN_DELIVERY';
  IF p_step = 'ACCEPT' AND v_o.status = 'NEW' THEN
    UPDATE online.orders SET status = 'ACCEPTED', accepted_at = now(),
           ready_by = CASE WHEN v_o.scheduled_for IS NOT NULL
                           THEN greatest(v_o.scheduled_for, now() + make_interval(mins => greatest(0, least(coalesce(p_minutes, 0), 240))))
                           ELSE now() + make_interval(mins => greatest(1, least(coalesce(p_minutes, 20), 240))) END
     WHERE id = p_id;
  ELSIF p_step = 'READY' AND v_o.status IN ('NEW', 'ACCEPTED') THEN
    UPDATE online.orders SET status = 'READY', ready_at = now(), accepted_at = coalesce(accepted_at, now()) WHERE id = p_id;
  ELSIF p_step = 'PICKED_UP' AND NOT v_delivery AND v_o.status IN ('ACCEPTED', 'READY') THEN
    UPDATE online.orders SET status = 'PICKED_UP', picked_up_at = now() WHERE id = p_id;
  ELSIF p_step = 'OUT' AND v_delivery AND v_o.status IN ('ACCEPTED', 'READY') THEN
    UPDATE online.orders SET status = 'OUT_FOR_DELIVERY', out_at = now(), out_by = v_staff, out_by_sn = v_sn,
           ready_at = coalesce(ready_at, now()), accepted_at = coalesce(accepted_at, now())
     WHERE id = p_id;
  ELSIF p_step = 'DELIVERED' AND v_delivery AND v_o.status = 'OUT_FOR_DELIVERY' THEN
    UPDATE online.orders SET status = 'DELIVERED', delivered_at = now() WHERE id = p_id;
  ELSIF p_step = 'BACK' AND v_delivery AND v_o.status = 'OUT_FOR_DELIVERY' THEN
    UPDATE online.orders SET status = 'READY', delivery_attempts = delivery_attempts + 1,
           last_attempt_note = nullif(left(trim(coalesce(p_reason, '')), 200), ''), out_by = NULL, out_by_sn = NULL
     WHERE id = p_id;
  ELSIF p_step = 'REJECT' AND (v_o.status IN ('NEW', 'ACCEPTED') OR v_delivery AND v_o.status = 'READY') THEN
    IF coalesce(trim(p_reason), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'reason_required'); END IF;
    UPDATE online.orders SET status = 'REJECTED', rejected_at = now(), reject_reason = left(trim(p_reason), 200) WHERE id = p_id;
  ELSE
    RETURN jsonb_build_object('success', false, 'message', 'order_' || lower(v_o.status));
  END IF;
  UPDATE online.orders SET handled_by = v_staff, handled_by_sn = v_sn WHERE id = p_id;
  SELECT * INTO v_o FROM online.orders WHERE id = p_id;
  RETURN jsonb_build_object('success', true, 'order', online.order_json(v_o, true));
END $function$;

-- Arke's own entry stays exactly as it was: platform orders refused.
CREATE OR REPLACE FUNCTION online.order_step(p_org uuid, p_id uuid, p_step text, p_minutes integer, p_reason text, p_staff_name text, p_device_sn text)
 RETURNS jsonb
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
  SELECT online.order_step_core(p_org, p_id, p_step, p_minutes, p_reason, p_staff_name, p_device_sn, false);
$function$;

-- ubereats-action: the Uber order a terminal of store p_org wants to move (null = not this store's Uber order).
CREATE OR REPLACE FUNCTION public.online_uber_step_get(p_org uuid, p_id uuid)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
  SELECT jsonb_build_object('status', o.status, 'external_id', o.external_id, 'fulfilment', o.fulfilment, 'code', o.external_code)
    FROM online.orders o WHERE o.id = p_id AND o.org_id = p_org AND o.channel = 'UBER_EATS';
$function$;

-- ubereats-action, once Uber said yes: the same step as Arke's.
CREATE OR REPLACE FUNCTION public.online_uber_step_apply(p_org uuid, p_id uuid, p_step text, p_minutes integer, p_reason text, p_staff_name text, p_device_sn text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM online.orders WHERE id = p_id AND org_id = p_org AND channel = 'UBER_EATS') THEN
    RETURN jsonb_build_object('success', false, 'message', 'order_not_found');
  END IF;
  RETURN online.order_step_core(p_org, p_id, p_step, p_minutes, p_reason, p_staff_name, p_device_sn, true);
END $function$;

REVOKE ALL ON FUNCTION online.order_step_core(uuid, uuid, text, integer, text, text, text, boolean) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_uber_step_get(uuid, uuid) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_uber_step_apply(uuid, uuid, text, integer, text, text, text) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_uber_step_get(uuid, uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_uber_step_apply(uuid, uuid, text, integer, text, text, text) TO service_role;
