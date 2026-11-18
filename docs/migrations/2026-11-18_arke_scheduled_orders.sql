-- Arke: order ahead for a time slot (owner 2026-11-18, "预约时段下单 + 营业时间").
--
-- The customer picks "as soon as possible" (only while the store is open, as before) or a 15-minute slot today or
-- on the next days (online.stores.schedule_days, 0 = no ordering ahead). Slots come from the store's hours in its own
-- time zone: from opening + the pickup / delivery minutes to closing, never sooner than now + those minutes. While
-- the store is closed, ordering ahead is the only way in. The server makes the slot list and checks the chosen one
-- against it -- the page never decides what's bookable. online.orders.scheduled_for = the promised time (NULL = ASAP).
-- On accept, ready_by is the booked time (or later, if the staff need longer). Applied 2026-11-18.

ALTER TABLE online.stores ADD COLUMN IF NOT EXISTS schedule_days SMALLINT NOT NULL DEFAULT 2
  CHECK (schedule_days BETWEEN 0 AND 7);
COMMENT ON COLUMN online.stores.schedule_days IS
  'Ordering ahead: how many days can be booked, today included (2 = today + tomorrow); 0 = only "as soon as possible".';
ALTER TABLE online.orders ADD COLUMN IF NOT EXISTS scheduled_for TIMESTAMPTZ;
COMMENT ON COLUMN online.orders.scheduled_for IS 'Arke: the slot the customer booked (pickup / delivery time); NULL = as soon as possible.';

-- The bookable slots, every 15 minutes, soonest first (at most 300).
CREATE OR REPLACE FUNCTION online.slot_times(s online.stores, p_ful TEXT) RETURNS TIMESTAMPTZ[]
LANGUAGE plpgsql STABLE SET search_path = public, online AS $$
DECLARE v_lead INTERVAL := make_interval(mins => CASE WHEN p_ful = 'OWN_DELIVERY' THEN s.delivery_minutes ELSE s.pickup_minutes END);
        v_today DATE := (now() AT TIME ZONE s.timezone)::DATE;
        v_ranges JSONB; v_r JSONB; v_day DATE; v_open TIMESTAMPTZ; v_close TIMESTAMPTZ; v_t TIMESTAMPTZ;
        v_out TIMESTAMPTZ[] := '{}'; i INT;
BEGIN
  IF NOT s.is_open OR s.schedule_days < 1 THEN RETURN v_out; END IF;
  FOR i IN 0 .. s.schedule_days - 1 LOOP
    v_day := v_today + i;
    v_ranges := CASE WHEN s.hours IS NULL OR jsonb_typeof(s.hours) <> 'object' THEN '[["00:00", "00:00"]]'::jsonb   -- no hours: all day
                     ELSE coalesce(s.hours->(extract(isodow FROM v_day)::INT::TEXT), '[]'::jsonb) END;
    FOR v_r IN SELECT x FROM jsonb_array_elements(v_ranges) x LOOP
      v_open := (v_day + (v_r->>0)::TIME) AT TIME ZONE s.timezone;
      v_close := (CASE WHEN (v_r->>1)::TIME <= (v_r->>0)::TIME THEN v_day + 1 ELSE v_day END + (v_r->>1)::TIME) AT TIME ZONE s.timezone;
      v_t := greatest(v_open + v_lead, now() + v_lead);
      v_t := to_timestamp(ceil(extract(epoch FROM v_t) / 900) * 900);   -- up to the next quarter hour
      WHILE v_t <= v_close AND cardinality(v_out) < 300 LOOP
        v_out := v_out || v_t;
        v_t := v_t + interval '15 minutes';
      END LOOP;
    END LOOP;
  END LOOP;
  RETURN ARRAY(SELECT DISTINCT x FROM unnest(v_out) x ORDER BY x);
EXCEPTION WHEN others THEN
  RETURN '{}';   -- unreadable hours: nothing bookable, never a crash on the customer's page
END $$;
REVOKE ALL ON FUNCTION online.slot_times(online.stores, TEXT) FROM public, anon, authenticated;

-- Orders carry the booked time.
CREATE OR REPLACE FUNCTION online.order_json(o online.orders, p_for_store boolean)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO 'public', 'online'
AS $function$
  SELECT jsonb_build_object(
    'id', o.id, 'token', o.public_token, 'status', o.status, 'fulfilment', o.fulfilment, 'channel', o.channel,
    'pickup_code', CASE WHEN o.channel = 'UBER_EATS' THEN o.external_code
                        WHEN o.pickup_no IS NOT NULL THEN (CASE WHEN o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END) || o.pickup_no END,
    'customer_name', o.customer_name, 'note', o.note, 'items', o.items,
    'subtotal_cents', o.subtotal_cents, 'tax_cents', o.tax_cents, 'tip_cents', o.tip_cents,
    'delivery_fee_cents', o.delivery_fee_cents, 'delivery_address', o.delivery_address, 'delivery_zone', o.delivery_zone,
    'total_cents', o.subtotal_cents + o.delivery_fee_cents + o.tax_cents + o.tip_cents,
    'scheduled_for', o.scheduled_for,
    'created_at', o.created_at, 'paid_at', o.paid_at, 'accepted_at', o.accepted_at, 'ready_by', o.ready_by,
    'ready_at', o.ready_at, 'picked_up_at', o.picked_up_at, 'out_at', o.out_at, 'delivered_at', o.delivered_at,
    'rejected_at', o.rejected_at, 'reject_reason', o.reject_reason,
    'cancelled_at', o.cancelled_at, 'cancel_reason', o.cancel_reason)
  || CASE WHEN p_for_store THEN jsonb_build_object('customer_phone', o.customer_phone, 'handled_by', o.handled_by,
       'out_by', o.out_by, 'out_by_sn', o.out_by_sn, 'delivery_attempts', o.delivery_attempts, 'last_attempt_note', o.last_attempt_note,
       'external_id', o.external_id, 'external_type', o.external_type)
     ELSE '{}'::jsonb END
$function$;
REVOKE ALL ON FUNCTION online.order_json(online.orders, BOOLEAN) FROM public, anon, authenticated;

-- The menu tells the page what can be booked.
CREATE OR REPLACE FUNCTION public.online_menu(p_slug text)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
  SELECT jsonb_build_object(
    'store', jsonb_build_object('name', coalesce(nullif(trim(o.brand_name), ''), o.name), 'logo_url', o.logo_url, 'slug', s.slug,
      'open_now', online.open_now(s), 'is_open', s.is_open, 'hours', s.hours, 'timezone', s.timezone,
      'pickup_minutes', s.pickup_minutes, 'address', s.address, 'phone', coalesce(s.phone, o.support_phone),
      'notice', s.notice,
      'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a
                             WHERE a.org_id = s.org_id AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE'),
      -- Ordering ahead: the bookable slots per way of getting the order (empty = none).
      'schedule_days', s.schedule_days,
      'slots', jsonb_build_object('PICKUP', to_jsonb(online.slot_times(s, 'PICKUP')),
                                  'OWN_DELIVERY', to_jsonb(online.slot_times(s, 'OWN_DELIVERY'))),
      -- Delivery: on only with at least one zone; the cheapest fee / lowest minimum for "from $x".
      'delivery', CASE WHEN s.delivery_enabled AND EXISTS (SELECT 1 FROM online.delivery_zones z WHERE z.org_id = s.org_id)
        THEN jsonb_build_object('minutes', s.delivery_minutes,
          'fee_from_cents', (SELECT min(fee_cents) FROM online.delivery_zones z WHERE z.org_id = s.org_id),
          'min_order_from_cents', (SELECT min(min_order_cents) FROM online.delivery_zones z WHERE z.org_id = s.org_id),
          'tax_bps', s.delivery_tax_bps) END),
    'products', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'id', p.id, 'name', p.name, 'price_cents', p.price_cents,
               'names', CASE WHEN jsonb_typeof(p.attributes->'name_i18n') = 'object' THEN p.attributes->'name_i18n' END,
               'image_url', p.attributes->>'image_url',
               'category', p.attributes->>'category',
               'description', p.attributes->>'description',
               'ingredients', p.attributes->>'ingredients',
               'allergens', p.attributes->'allergens',
               'dietary', coalesce(p.attributes->'dietary', '[]'::jsonb),
               'spice_level', (p.attributes->>'spice_level')::INT,
               'guide', p.attributes->>'guide',
               'tax_rate_bps', coalesce((p.attributes->>'tax_rate_bps')::INT, 1300),
               'modifier_groups', coalesce(p.attributes->'modifier_groups', '[]'::jsonb),
               'sold_out', coalesce((p.attributes->>'stock_qty')::INT, 1) <= 0)
             ORDER BY p.attributes->>'category' NULLS LAST, p.name)
        FROM products p
       WHERE p.org_id = s.org_id AND p.is_active AND p.vertical_type = 'RETAIL'
         AND coalesce((p.attributes->>'online_hidden')::BOOLEAN, false) = false), '[]'::jsonb))
    FROM online.stores s JOIN organizations o ON o.id = s.org_id
   WHERE s.slug = lower(trim(p_slug))
$function$;

-- Placing an order: ASAP needs the store open now; a booked time must be one of the store's slots.
DROP FUNCTION IF EXISTS public.online_place_order(TEXT, JSONB, TEXT, TEXT, TEXT, TEXT, TEXT, JSONB);
CREATE OR REPLACE FUNCTION public.online_place_order(p_slug text, p_items jsonb, p_name text, p_phone text, p_note text, p_client_ref text,
  p_fulfilment text DEFAULT 'PICKUP'::text, p_address jsonb DEFAULT NULL::jsonb, p_scheduled_for timestamptz DEFAULT NULL)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_s online.stores%ROWTYPE; v_existing online.orders%ROWTYPE; v_priced JSONB; v_phone TEXT; v_id UUID; v_token TEXT;
        v_ful TEXT := upper(coalesce(nullif(trim(p_fulfilment), ''), 'PICKUP')); v_dp JSONB; v_addr JSONB;
        v_sub INT; v_fee INT := 0; v_tax INT;
BEGIN
  SELECT * INTO v_s FROM online.stores WHERE slug = lower(trim(p_slug));
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_store'); END IF;
  IF coalesce(trim(p_client_ref), '') = '' OR length(p_client_ref) > 64 OR v_ful NOT IN ('PICKUP', 'OWN_DELIVERY') THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_request');
  END IF;
  SELECT * INTO v_existing FROM online.orders WHERE org_id = v_s.org_id AND client_ref = p_client_ref;
  IF FOUND THEN
    RETURN jsonb_build_object('success', true, 'duplicate', true, 'id', v_existing.id, 'token', v_existing.public_token,
      'subtotal_cents', v_existing.subtotal_cents, 'tax_cents', v_existing.tax_cents, 'delivery_fee_cents', v_existing.delivery_fee_cents);
  END IF;
  IF p_scheduled_for IS NULL THEN
    IF NOT online.open_now(v_s) THEN RETURN jsonb_build_object('success', false, 'message', 'store_closed'); END IF;
  ELSIF NOT (p_scheduled_for = ANY (online.slot_times(v_s, v_ful))) THEN
    RETURN jsonb_build_object('success', false, 'message', 'slot_gone');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM merchant_acquirer_accounts a WHERE a.org_id = v_s.org_id AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE') THEN
    RETURN jsonb_build_object('success', false, 'message', 'online_pay_off');
  END IF;

  IF coalesce(trim(p_name), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'name_required'); END IF;
  v_phone := regexp_replace(coalesce(p_phone, ''), '[^0-9+]', '', 'g');
  IF length(regexp_replace(v_phone, '[^0-9]', '', 'g')) NOT BETWEEN 10 AND 15 THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_phone');
  END IF;

  -- Abuse limits: one phone number, and the store as a whole, can't flood it with unpaid orders.
  IF (SELECT count(*) FROM online.orders WHERE org_id = v_s.org_id AND customer_phone = v_phone AND created_at > now() - interval '10 minutes') >= 5
     OR (SELECT count(*) FROM online.orders WHERE org_id = v_s.org_id AND status = 'AWAITING_PAYMENT' AND created_at > now() - interval '10 minutes') >= 40 THEN
    RETURN jsonb_build_object('success', false, 'message', 'too_many_orders');
  END IF;

  v_priced := online.price_lines(v_s.org_id, p_items);
  IF NOT (v_priced->>'success')::BOOLEAN THEN RETURN v_priced; END IF;
  v_sub := (v_priced->>'subtotal_cents')::INT;
  v_tax := (v_priced->>'tax_cents')::INT;

  IF v_ful = 'OWN_DELIVERY' THEN
    IF p_address IS NULL OR jsonb_typeof(p_address) <> 'object' OR length(trim(coalesce(p_address->>'line1', ''))) < 3 THEN
      RETURN jsonb_build_object('success', false, 'message', 'address_required');
    END IF;
    v_dp := online.delivery_price(v_s, p_address->>'postal', v_sub);
    IF NOT (v_dp->>'success')::BOOLEAN THEN RETURN v_dp; END IF;
    v_fee := (v_dp->>'fee_cents')::INT;
    v_tax := v_tax + (v_dp->>'fee_tax_cents')::INT;
    v_addr := jsonb_strip_nulls(jsonb_build_object(
      'line1', left(trim(p_address->>'line1'), 120),
      'unit', nullif(left(trim(coalesce(p_address->>'unit', '')), 20), ''),
      'city', nullif(left(trim(coalesce(p_address->>'city', '')), 60), ''),
      'postal', substr(v_dp->>'postal', 1, 3) || ' ' || substr(v_dp->>'postal', 4, 3),
      'instructions', nullif(left(trim(coalesce(p_address->>'instructions', '')), 200), '')));
  END IF;

  INSERT INTO online.orders (org_id, client_ref, fulfilment, customer_name, customer_phone, note, items, subtotal_cents, tax_cents,
                             delivery_address, delivery_zone, delivery_fee_cents, scheduled_for)
  VALUES (v_s.org_id, p_client_ref, v_ful, left(trim(p_name), 40), v_phone, nullif(left(trim(coalesce(p_note, '')), 200), ''),
          v_priced->'lines', v_sub, v_tax, v_addr, v_dp->>'zone', v_fee, p_scheduled_for)
  RETURNING id, public_token INTO v_id, v_token;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'token', v_token,
    'subtotal_cents', v_sub, 'tax_cents', v_tax, 'delivery_fee_cents', v_fee);
END $function$;
REVOKE ALL ON FUNCTION public.online_place_order(TEXT, JSONB, TEXT, TEXT, TEXT, TEXT, TEXT, JSONB, TIMESTAMPTZ) FROM public;
GRANT EXECUTE ON FUNCTION public.online_place_order(TEXT, JSONB, TEXT, TEXT, TEXT, TEXT, TEXT, JSONB, TIMESTAMPTZ) TO anon, authenticated;

-- Accepting a booked order: ready by the booked time, or later if the staff ask for more minutes than are left.
CREATE OR REPLACE FUNCTION public.online_store_step(p_id uuid, p_step text, p_minutes integer, p_reason text, p_staff_name text, p_device_sn text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_org UUID := dine_device_org(); v_o online.orders%ROWTYPE; v_staff TEXT := nullif(left(trim(coalesce(p_staff_name, '')), 40), '');
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

-- Cael: read and set how far ahead customers can book.
CREATE OR REPLACE FUNCTION public.cmp_online_store_get(p_org uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_s online.stores%ROWTYPE;
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501'; END IF;
  SELECT * INTO v_s FROM online.stores WHERE org_id = p_org;
  IF NOT FOUND THEN RETURN NULL; END IF;
  RETURN jsonb_build_object('slug', v_s.slug, 'is_open', v_s.is_open, 'open_now', online.open_now(v_s),
    'pickup_minutes', v_s.pickup_minutes, 'hours', v_s.hours, 'timezone', v_s.timezone,
    'address', v_s.address, 'phone', v_s.phone, 'notice', v_s.notice, 'schedule_days', v_s.schedule_days,
    'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a WHERE a.org_id = p_org AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE'),
    'delivery_enabled', v_s.delivery_enabled, 'delivery_minutes', v_s.delivery_minutes, 'delivery_tax_bps', v_s.delivery_tax_bps,
    'zones', coalesce((SELECT jsonb_agg(jsonb_build_object('id', z.id, 'name', z.name, 'postal_prefixes', to_jsonb(z.postal_prefixes),
                         'fee_cents', z.fee_cents, 'min_order_cents', z.min_order_cents) ORDER BY z.sort, z.created_at)
                         FROM online.delivery_zones z WHERE z.org_id = p_org), '[]'::jsonb));
END $function$;

CREATE OR REPLACE FUNCTION public.cmp_online_schedule_save(p_org UUID, p_days INT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
BEGIN
  IF NOT (is_sys_admin() OR (p_org IN (SELECT member_org_ids()) AND has_permission('catalog.manage'))) THEN
    RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
  END IF;
  IF p_days IS NULL OR p_days NOT BETWEEN 0 AND 7 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_days'); END IF;
  UPDATE online.stores SET schedule_days = p_days, updated_at = now() WHERE org_id = p_org;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'no_store'); END IF;
  RETURN jsonb_build_object('success', true);
END $$;
REVOKE ALL ON FUNCTION public.cmp_online_schedule_save(UUID, INT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_online_schedule_save(UUID, INT) TO authenticated;

NOTIFY pgrst, 'reload schema';
