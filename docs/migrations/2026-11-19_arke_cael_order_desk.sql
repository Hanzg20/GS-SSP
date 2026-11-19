-- Arke: Cael order desk (2026-11-19, design doc phase 1: "stores without a terminal take orders in a Cael web page").
-- The order steps move into online.order_step(p_org, ...) unchanged; two doors into it:
--   online_store_step  -- Ourea / Iris, store from the terminal's sign-in (dine_device_org), as before.
--   cmp_online_order_step -- Cael, a member of that store with orders.manage (or a sys admin);
--                            recorded as handled by the user's name, device "CAEL".
-- Refunds of rejected orders: arke-pay-refund also accepts a Cael user, checked by cmp_online_refund_org.

CREATE OR REPLACE FUNCTION online.order_step(p_org uuid, p_id uuid, p_step text, p_minutes integer, p_reason text, p_staff_name text, p_device_sn text)
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
  IF v_o.channel <> 'ARKE' THEN RETURN jsonb_build_object('success', false, 'message', 'order_on_platform'); END IF;
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
REVOKE ALL ON FUNCTION online.order_step(uuid, uuid, text, integer, text, text, text) FROM PUBLIC;

CREATE OR REPLACE FUNCTION public.online_store_step(p_id uuid, p_step text, p_minutes integer, p_reason text, p_staff_name text, p_device_sn text)
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path = public, online AS $$
  SELECT online.order_step(dine_device_org(), p_id, p_step, p_minutes, p_reason, p_staff_name, p_device_sn);
$$;

CREATE OR REPLACE FUNCTION public.cmp_online_order_step(p_org uuid, p_id uuid, p_step text, p_minutes integer DEFAULT NULL, p_reason text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_name TEXT;
BEGIN
  IF NOT (is_sys_admin() OR (p_org IN (SELECT member_org_ids()) AND has_permission('orders.manage'))) THEN
    RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
  END IF;
  SELECT coalesce(nullif(trim(full_name), ''), split_part(email, '@', 1)) INTO v_name FROM profiles WHERE id = auth.uid();
  RETURN online.order_step(p_org, p_id, p_step, p_minutes, p_reason, coalesce(v_name, 'Cael'), 'CAEL');
END $$;
REVOKE ALL ON FUNCTION public.cmp_online_order_step(uuid, uuid, text, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cmp_online_order_step(uuid, uuid, text, integer, text) TO authenticated;

-- For arke-pay-refund: the store of a REJECTED order the calling Cael user may give back, else NULL.
CREATE OR REPLACE FUNCTION public.cmp_online_refund_org(p_order_id uuid)
RETURNS uuid LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT o.org_id FROM online.orders o
   WHERE o.id = p_order_id AND o.status = 'REJECTED'
     AND (is_sys_admin() OR (o.org_id IN (SELECT member_org_ids()) AND has_permission('orders.manage')));
$$;
REVOKE ALL ON FUNCTION public.cmp_online_refund_org(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cmp_online_refund_org(uuid) TO authenticated;
