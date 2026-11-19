-- 2026-11-19: Arke online orders earn the store's loyalty points (member.*, 2026-11-01_loyalty.sql).
--
-- A member is a phone number in a store. At checkout the customer keeps "earn points with my phone number"
-- ticked (online.orders.earn_points); when the order is PAID, online.order_earn joins that phone (or finds the
-- member) and earns on what the dishes cost after the coupon -- before tax, delivery fee and tip, the same rule as
-- the counter -- points_per_dollar per whole dollar. Ledger ref 'ARKE:' || order id, staff 'Arke online', so a
-- repeated settle never counts twice. A rejected / cancelled order's refund takes them back.
--
-- Redeeming stays at the counter for now: a phone number is not a secret, and spending someone's points online
-- would need the customer to prove the number (SMS code), which we don't have yet.
--
-- Also: online_refund_done gives back the coupon use (and now the points) only when the ORDER is off; refunding
-- an extra second payment of an order that still stands gives back nothing.

ALTER TABLE online.orders ADD COLUMN IF NOT EXISTS earn_points BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE online.orders ADD COLUMN IF NOT EXISTS member_id UUID REFERENCES member.members(id) ON DELETE SET NULL;
ALTER TABLE online.orders ADD COLUMN IF NOT EXISTS points_earned INTEGER;

-- Joins / finds the member by the order's phone and earns on the paid order. Not callable from the API.
CREATE OR REPLACE FUNCTION online.order_earn(p_order UUID) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, online, member AS $$
DECLARE v_o online.orders%ROWTYPE; v_set JSONB; v_phone TEXT; v_m member.members%ROWTYPE; v_earn INT; v_after INT;
BEGIN
  SELECT * INTO v_o FROM online.orders WHERE id = p_order;
  IF NOT FOUND OR v_o.paid_at IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_paid'); END IF;
  v_set := member_settings_for(v_o.org_id);
  IF NOT (v_set->>'enabled')::BOOLEAN THEN RETURN jsonb_build_object('success', false, 'message', 'loyalty_off'); END IF;
  v_phone := member_phone(v_o.customer_phone);
  IF v_phone !~ '^\d{7,15}$' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_phone'); END IF;
  BEGIN
    INSERT INTO member.members (org_id, phone, name) VALUES (v_o.org_id, v_phone, nullif(trim(v_o.customer_name), ''))
    ON CONFLICT (org_id, phone) DO UPDATE SET name = coalesce(member.members.name, excluded.name)
    RETURNING * INTO v_m;
    v_earn := (greatest(v_o.subtotal_cents - v_o.discount_cents, 0) / 100) * (v_set->>'points_per_dollar')::INT;
    v_after := member_points_apply(v_m.id, 'EARN', v_earn, 'ARKE:' || v_o.id, NULL, 'Arke online', NULL);
    IF v_after IS NOT NULL THEN
      UPDATE member.members SET visits = visits + 1, last_visit_at = now() WHERE id = v_m.id;
      UPDATE online.orders SET member_id = v_m.id, points_earned = v_earn WHERE id = v_o.id;
    END IF;
    RETURN jsonb_build_object('success', true, 'earned', v_earn, 'points', v_after);
  EXCEPTION WHEN OTHERS THEN
    -- Points must never stop a paid order going to the kitchen.
    RAISE WARNING 'arke points failed for %: %', v_o.id, SQLERRM;
    RETURN jsonb_build_object('success', false, 'message', 'error');
  END;
END $$;
REVOKE ALL ON FUNCTION online.order_earn(UUID) FROM public, anon, authenticated;

DROP FUNCTION IF EXISTS public.online_place_order(text, jsonb, text, text, text, text, text, jsonb, timestamp with time zone, text);

CREATE OR REPLACE FUNCTION public.online_place_order(p_slug text, p_items jsonb, p_name text, p_phone text, p_note text, p_client_ref text, p_fulfilment text DEFAULT 'PICKUP'::text, p_address jsonb DEFAULT NULL::jsonb, p_scheduled_for timestamp with time zone DEFAULT NULL::timestamp with time zone, p_coupon text DEFAULT NULL::text, p_points boolean DEFAULT false)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_s online.stores%ROWTYPE; v_existing online.orders%ROWTYPE; v_priced JSONB; v_phone TEXT; v_id UUID; v_token TEXT;
        v_ful TEXT := upper(coalesce(nullif(trim(p_fulfilment), ''), 'PICKUP')); v_dp JSONB; v_addr JSONB;
        v_sub INT; v_fee INT := 0; v_tax INT; v_cq JSONB; v_coupon TEXT; v_disc INT := 0;
BEGIN
  SELECT * INTO v_s FROM online.stores WHERE slug = lower(trim(p_slug));
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_store'); END IF;
  IF coalesce(trim(p_client_ref), '') = '' OR length(p_client_ref) > 64 OR v_ful NOT IN ('PICKUP', 'OWN_DELIVERY') THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_request');
  END IF;
  SELECT * INTO v_existing FROM online.orders WHERE org_id = v_s.org_id AND client_ref = p_client_ref;
  IF FOUND THEN
    RETURN jsonb_build_object('success', true, 'duplicate', true, 'id', v_existing.id, 'token', v_existing.public_token,
      'subtotal_cents', v_existing.subtotal_cents, 'tax_cents', v_existing.tax_cents, 'delivery_fee_cents', v_existing.delivery_fee_cents,
      'discount_cents', v_existing.discount_cents);
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
  IF coalesce(trim(p_coupon), '') <> '' THEN
    v_cq := online.coupon_quote(v_s.org_id, p_coupon, v_priced->'lines');
    IF NOT (v_cq->>'success')::BOOLEAN THEN RETURN v_cq; END IF;
    v_coupon := v_cq->>'code';
    v_disc := (v_cq->>'discount_cents')::INT;
    v_tax := (v_cq->>'tax_cents')::INT;
  END IF;

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
                             delivery_address, delivery_zone, delivery_fee_cents, scheduled_for, coupon_code, discount_cents, earn_points)
  VALUES (v_s.org_id, p_client_ref, v_ful, left(trim(p_name), 40), v_phone, nullif(left(trim(coalesce(p_note, '')), 200), ''),
          v_priced->'lines', v_sub, v_tax, v_addr, v_dp->>'zone', v_fee, p_scheduled_for, v_coupon, v_disc, coalesce(p_points, false))
  RETURNING id, public_token INTO v_id, v_token;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'token', v_token,
    'subtotal_cents', v_sub, 'tax_cents', v_tax, 'delivery_fee_cents', v_fee, 'discount_cents', v_disc);
END $function$;

CREATE OR REPLACE FUNCTION public.online_pay_settle(p_id uuid, p_transaction_id text, p_paid_cents integer, p_auth_code text, p_card text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_p online.payments%ROWTYPE; v_o online.orders%ROWTYPE; v_s online.stores%ROWTYPE; v_day DATE; v_no INT; v_code TEXT;
BEGIN
  SELECT * INTO v_p FROM online.payments WHERE id = p_id FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'payment_not_found'); END IF;
  IF v_p.status IN ('PAID', 'REFUNDING', 'VOIDED', 'REFUNDED') THEN RETURN jsonb_build_object('success', true, 'duplicate', true); END IF;
  IF coalesce(trim(p_transaction_id), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_transaction'); END IF;
  IF p_paid_cents IS DISTINCT FROM v_p.amount_cents + v_p.tip_cents THEN
    UPDATE online.payments SET reason = 'amount_mismatch: charged ' || coalesce(p_paid_cents::TEXT, '?') || ' tx ' || trim(p_transaction_id)
     WHERE id = p_id;
    RETURN jsonb_build_object('success', false, 'message', 'amount_mismatch');
  END IF;
  SELECT * INTO v_o FROM online.orders WHERE id = v_p.order_id FOR UPDATE;
  IF v_o.status NOT IN ('AWAITING_PAYMENT', 'EXPIRED') THEN
    -- The order was already paid by another attempt: this money is extra -- a person refunds it.
    UPDATE online.payments SET status = 'PAID', paid_at = now(), transaction_id = trim(p_transaction_id),
           auth_code = nullif(trim(p_auth_code), ''), card = nullif(trim(p_card), ''), reason = 'second_payment'
     WHERE id = p_id;
    RETURN jsonb_build_object('success', false, 'message', 'second_payment');
  END IF;

  UPDATE online.payments SET status = 'PAID', paid_at = now(), transaction_id = trim(p_transaction_id),
         auth_code = nullif(trim(p_auth_code), ''), card = nullif(trim(p_card), ''), reason = NULL
   WHERE id = p_id;

  SELECT * INTO v_s FROM online.stores WHERE org_id = v_o.org_id FOR UPDATE;
  -- Numbered within the day the order is collected: a booked order takes its booked day's next number, so
  -- tomorrow's first walk-up order is A2, not a second A1. The store row lock above keeps two payments apart.
  v_day := (coalesce(v_o.scheduled_for, now()) AT TIME ZONE v_s.timezone)::DATE;
  SELECT coalesce(max(pickup_no), 0) + 1 INTO v_no FROM online.orders
   WHERE org_id = v_o.org_id AND pickup_day = v_day AND pickup_no IS NOT NULL;
  IF v_day = (now() AT TIME ZONE v_s.timezone)::DATE THEN
    UPDATE online.stores SET last_pickup_day = v_day, last_pickup_no = v_no WHERE org_id = v_s.org_id;
  END IF;
  UPDATE online.orders SET status = 'NEW', paid_at = now(), tip_cents = v_p.tip_cents, pickup_no = v_no, pickup_day = v_day
   WHERE id = v_o.id;
  IF v_o.coupon_code IS NOT NULL THEN
    -- Counted even if the last use went to someone else meanwhile: this customer already paid the discounted price.
    UPDATE coupons SET uses_count = uses_count + 1 WHERE code = v_o.coupon_code;
    INSERT INTO coupon_redemptions (coupon_code, device_sn, ecr_ref_num) VALUES (v_o.coupon_code, NULL, 'ARKE:' || v_o.id);
  END IF;
  IF v_o.earn_points THEN
    PERFORM online.order_earn(v_o.id);
  END IF;
  v_code := CASE WHEN v_o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END || v_no;
  RETURN jsonb_build_object('success', true, 'pickup_code', v_code,
    'mail', jsonb_build_object('email', v_p.email, 'lang', v_p.lang, 'token', v_o.public_token, 'code', v_code,
      'fulfilment', v_o.fulfilment, 'store', (SELECT coalesce(nullif(trim(brand_name), ''), name) FROM organizations WHERE id = v_o.org_id),
      'total_cents', v_p.amount_cents + v_p.tip_cents));
END $function$;

CREATE OR REPLACE FUNCTION public.online_refund_done(p_id uuid, p_status text, p_refund_tx_id text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_p online.payments%ROWTYPE; v_o online.orders%ROWTYPE; l member.points_ledger%ROWTYPE;
BEGIN
  UPDATE online.payments SET status = CASE WHEN p_status = 'VOIDED' THEN 'VOIDED' ELSE 'REFUNDED' END,
         refund_tx_id = nullif(trim(p_refund_tx_id), ''), refunded_at = now()
   WHERE id = p_id AND status = 'REFUNDING'
  RETURNING * INTO v_p;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', true); END IF;
  SELECT * INTO v_o FROM online.orders WHERE id = v_p.order_id;
  IF v_o.status NOT IN ('REJECTED', 'CANCELLED') THEN RETURN jsonb_build_object('success', true); END IF;
  -- The order's money went back: so does its coupon use.
  WITH r AS (
    UPDATE coupon_redemptions cr SET released_at = now(), release_reason = 'arke_refund'
      FROM online.payments p
     WHERE p.id = p_id AND cr.ecr_ref_num = 'ARKE:' || p.order_id AND cr.released_at IS NULL
    RETURNING cr.coupon_code)
  UPDATE coupons c SET uses_count = greatest(c.uses_count - 1, 0) FROM r WHERE c.code = r.coupon_code;
  -- ... and its points: earned taken back (like a voided sale at the counter).
  IF v_o.member_id IS NOT NULL THEN
    BEGIN
      FOR l IN SELECT * FROM member.points_ledger WHERE member_id = v_o.member_id AND ecr_ref_num = 'ARKE:' || v_o.id AND reason = 'EARN' LOOP
        PERFORM member_points_apply(v_o.member_id, 'REVERSE', -l.delta, l.ecr_ref_num, 'online order refunded', 'Arke online', NULL);
      END LOOP;
    EXCEPTION WHEN OTHERS THEN
      RAISE WARNING 'arke points reversal failed for %: %', v_o.id, SQLERRM;
    END;
  END IF;
  RETURN jsonb_build_object('success', true);
END $function$;

CREATE OR REPLACE FUNCTION online.order_json(o online.orders, p_for_store boolean)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
  SELECT jsonb_build_object(
    'id', o.id, 'token', o.public_token, 'status', o.status, 'fulfilment', o.fulfilment, 'channel', o.channel,
    'pickup_code', CASE WHEN o.channel = 'UBER_EATS' THEN o.external_code
                        WHEN o.pickup_no IS NOT NULL THEN (CASE WHEN o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END) || o.pickup_no END,
    'customer_name', o.customer_name, 'note', o.note, 'items', o.items,
    'subtotal_cents', o.subtotal_cents, 'tax_cents', o.tax_cents, 'tip_cents', o.tip_cents,
    'delivery_fee_cents', o.delivery_fee_cents, 'delivery_address', o.delivery_address, 'delivery_zone', o.delivery_zone,
    'total_cents', o.subtotal_cents - o.discount_cents + o.delivery_fee_cents + o.tax_cents + o.tip_cents,
    'discount_cents', o.discount_cents, 'coupon_code', o.coupon_code,
    'points_earned', o.points_earned,
    'points_balance', (SELECT m.points FROM member.members m WHERE m.id = o.member_id),
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
      -- Store rewards (loyalty points by phone number), when the store has them on.
      'points', (SELECT jsonb_build_object('per_dollar', ms.points_per_dollar, 'reward_points', ms.reward_points, 'reward_cents', ms.reward_cents)
                   FROM member.settings ms WHERE ms.org_id = s.org_id AND ms.enabled),
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

REVOKE ALL ON FUNCTION public.online_place_order(text, jsonb, text, text, text, text, text, jsonb, timestamptz, text, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.online_place_order(text, jsonb, text, text, text, text, text, jsonb, timestamptz, text, boolean) TO anon, authenticated;
