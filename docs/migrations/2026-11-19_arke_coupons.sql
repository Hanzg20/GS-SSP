-- Arke: coupons on online orders (2026-11-19, design doc phase 4).
-- The store's Cael coupons (cmp_create_coupons) work on its ordering page: PERCENT_OFF and FIXED_OFF take
-- money off the items (a coupon tied to a product: only that product's lines), never the delivery fee or
-- the tip; tax is worked out again on what is left, rate by rate. FREE_WASH coupons are for the wash.
-- Checked when typed (online_coupon_check) and again when the order is placed; a use is counted when the
-- order is PAID (online_pay_settle) and given back if its money is refunded (online_refund_done).
-- Wrong codes: after 20 misses in 10 minutes at a store, its coupon box rests (coupon_attempts, 'arke:<org>').

ALTER TABLE online.orders ADD COLUMN IF NOT EXISTS coupon_code TEXT;
ALTER TABLE online.orders ADD COLUMN IF NOT EXISTS discount_cents INT NOT NULL DEFAULT 0;

-- What [p_code] takes off these priced lines (online.price_lines' lines), and the tax after it.
CREATE OR REPLACE FUNCTION online.coupon_quote(p_org UUID, p_code TEXT, p_lines JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_key TEXT := 'arke:' || p_org; v_code TEXT; c RECORD; v_base INT; v_disc INT; v_tax INT;
BEGIN
  IF (SELECT count(*) FROM coupon_attempts WHERE limit_key = v_key AND at > now() - interval '10 minutes') >= 20 THEN
    RETURN jsonb_build_object('success', false, 'message', 'coupon_rest');
  END IF;
  v_code := coupon_find_code(p_code);
  SELECT * INTO c FROM coupons WHERE code = v_code;
  IF NOT FOUND OR c.org_id <> p_org OR NOT coalesce(c.is_active, false) THEN
    INSERT INTO coupon_attempts (limit_key) VALUES (v_key);
    RETURN jsonb_build_object('success', false, 'message', 'coupon_unknown');
  END IF;
  IF c.expires_at IS NOT NULL AND c.expires_at < now() THEN RETURN jsonb_build_object('success', false, 'message', 'coupon_expired'); END IF;
  IF c.uses_count >= c.max_uses THEN RETURN jsonb_build_object('success', false, 'message', 'coupon_used'); END IF;
  IF c.type NOT IN ('PERCENT_OFF', 'FIXED_OFF') THEN RETURN jsonb_build_object('success', false, 'message', 'coupon_not_online'); END IF;

  -- The lines it applies to: all, or only its product's.
  SELECT coalesce(sum((l->>'quantity')::INT * (l->>'unit_price_cents')::INT), 0) INTO v_base
    FROM jsonb_array_elements(p_lines) l
   WHERE c.applicable_product_id IS NULL OR (l->>'product_id')::UUID = c.applicable_product_id;
  IF v_base <= 0 THEN RETURN jsonb_build_object('success', false, 'message', 'coupon_no_item'); END IF;
  v_disc := CASE WHEN c.type = 'PERCENT_OFF' THEN round(v_base * c.value / 100.0)::INT ELSE least(c.value, v_base) END;

  -- Tax again, per rate, on each eligible line less its share of the discount (counter rule: each rate rounded once).
  SELECT coalesce(sum(round(t * b / 10000.0)), 0)::INT INTO v_tax FROM (
    SELECT (l->>'tax_rate_bps')::INT b,
           sum((l->>'quantity')::INT * (l->>'unit_price_cents')::INT
               - CASE WHEN c.applicable_product_id IS NULL OR (l->>'product_id')::UUID = c.applicable_product_id
                      THEN (l->>'quantity')::INT * (l->>'unit_price_cents')::INT * v_disc / v_base::NUMERIC ELSE 0 END) t
      FROM jsonb_array_elements(p_lines) l GROUP BY 1) x;
  RETURN jsonb_build_object('success', true, 'code', c.code, 'type', c.type, 'value', c.value,
    'discount_cents', v_disc, 'tax_cents', v_tax, 'expires_at', c.expires_at);
END $$;
REVOKE ALL ON FUNCTION online.coupon_quote(UUID, TEXT, JSONB) FROM PUBLIC;

-- The customer page, as the code is typed: what it takes off this cart.
CREATE OR REPLACE FUNCTION public.online_coupon_check(p_slug TEXT, p_code TEXT, p_items JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_s online.stores%ROWTYPE; v_priced JSONB;
BEGIN
  SELECT * INTO v_s FROM online.stores WHERE slug = lower(trim(p_slug));
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_store'); END IF;
  IF coalesce(trim(p_code), '') = '' OR length(p_code) > 40 THEN RETURN jsonb_build_object('success', false, 'message', 'coupon_unknown'); END IF;
  v_priced := online.price_lines(v_s.org_id, p_items);
  IF NOT (v_priced->>'success')::BOOLEAN THEN RETURN v_priced; END IF;
  RETURN online.coupon_quote(v_s.org_id, p_code, v_priced->'lines');
END $$;
REVOKE ALL ON FUNCTION public.online_coupon_check(TEXT, TEXT, JSONB) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.online_coupon_check(TEXT, TEXT, JSONB) TO anon, authenticated;

DROP FUNCTION IF EXISTS public.online_place_order(text, jsonb, text, text, text, text, text, jsonb, timestamptz);

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
    'total_cents', o.subtotal_cents - o.discount_cents + o.delivery_fee_cents + o.tax_cents + o.tip_cents,
    'discount_cents', o.discount_cents, 'coupon_code', o.coupon_code,
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

CREATE OR REPLACE FUNCTION public.online_place_order(p_slug text, p_items jsonb, p_name text, p_phone text, p_note text, p_client_ref text, p_fulfilment text DEFAULT 'PICKUP'::text, p_address jsonb DEFAULT NULL::jsonb, p_scheduled_for timestamp with time zone DEFAULT NULL::timestamp with time zone, p_coupon text DEFAULT NULL::text)
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
                             delivery_address, delivery_zone, delivery_fee_cents, scheduled_for, coupon_code, discount_cents)
  VALUES (v_s.org_id, p_client_ref, v_ful, left(trim(p_name), 40), v_phone, nullif(left(trim(coalesce(p_note, '')), 200), ''),
          v_priced->'lines', v_sub, v_tax, v_addr, v_dp->>'zone', v_fee, p_scheduled_for, v_coupon, v_disc)
  RETURNING id, public_token INTO v_id, v_token;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'token', v_token,
    'subtotal_cents', v_sub, 'tax_cents', v_tax, 'delivery_fee_cents', v_fee, 'discount_cents', v_disc);
END $function$;

CREATE OR REPLACE FUNCTION public.online_pay_open(p_token text, p_tip_cents integer, p_email text DEFAULT NULL::text, p_lang text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_o online.orders%ROWTYPE; v_account UUID; v_id UUID; v_amount INT;
BEGIN
  SELECT * INTO v_o FROM online.orders WHERE public_token = p_token FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_order'); END IF;
  IF v_o.status <> 'AWAITING_PAYMENT' THEN RETURN jsonb_build_object('success', false, 'message', 'order_' || lower(v_o.status)); END IF;
  IF v_o.created_at < now() - interval '30 minutes' THEN RETURN jsonb_build_object('success', false, 'message', 'order_expired'); END IF;
  SELECT id INTO v_account FROM merchant_acquirer_accounts WHERE org_id = v_o.org_id AND acquirer = 'nuvei' AND status = 'ACTIVE';
  IF v_account IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'online_pay_off'); END IF;
  IF (SELECT count(*) FROM online.payments WHERE order_id = v_o.id AND created_at > now() - interval '10 minutes') >= 6 THEN
    RETURN jsonb_build_object('success', false, 'message', 'too_many_attempts');
  END IF;
  -- Tip: Tipping.maxCustomCents -- the bigger of the amount before tax and $20.
  IF coalesce(p_tip_cents, 0) < 0 OR coalesce(p_tip_cents, 0) > greatest(v_o.subtotal_cents, 2000) THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_tip');
  END IF;
  v_amount := v_o.subtotal_cents - v_o.discount_cents + v_o.delivery_fee_cents + v_o.tax_cents;
  INSERT INTO online.payments (org_id, order_id, amount_cents, tax_cents, tip_cents, acquirer_account_id, email, lang)
  VALUES (v_o.org_id, v_o.id, v_amount, v_o.tax_cents, coalesce(p_tip_cents, 0), v_account,
          nullif(left(trim(coalesce(p_email, '')), 200), ''),
          CASE WHEN p_lang IN ('zh', 'en', 'fr', 'ar') THEN p_lang END)
  RETURNING id INTO v_id;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'acquirer_account_id', v_account,
    'amount_cents', v_amount, 'tax_cents', v_o.tax_cents, 'tip_cents', coalesce(p_tip_cents, 0));
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
  v_code := CASE WHEN v_o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END || v_no;
  RETURN jsonb_build_object('success', true, 'pickup_code', v_code,
    'mail', jsonb_build_object('email', v_p.email, 'lang', v_p.lang, 'token', v_o.public_token, 'code', v_code,
      'fulfilment', v_o.fulfilment, 'store', (SELECT coalesce(nullif(trim(brand_name), ''), name) FROM organizations WHERE id = v_o.org_id),
      'total_cents', v_p.amount_cents + v_p.tip_cents));
END $function$;

CREATE OR REPLACE FUNCTION public.online_refund_done(p_id uuid, p_status text, p_refund_tx_id text)
 RETURNS jsonb
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
  UPDATE online.payments SET status = CASE WHEN p_status = 'VOIDED' THEN 'VOIDED' ELSE 'REFUNDED' END,
         refund_tx_id = nullif(trim(p_refund_tx_id), ''), refunded_at = now()
   WHERE id = p_id AND status = 'REFUNDING';
  -- The order's money went back: so does its coupon use.
  WITH r AS (
    UPDATE coupon_redemptions cr SET released_at = now(), release_reason = 'arke_refund'
      FROM online.payments p
     WHERE p.id = p_id AND p.status IN ('VOIDED', 'REFUNDED') AND cr.ecr_ref_num = 'ARKE:' || p.order_id AND cr.released_at IS NULL
    RETURNING cr.coupon_code)
  UPDATE coupons c SET uses_count = greatest(c.uses_count - 1, 0) FROM r WHERE c.code = r.coupon_code;
  SELECT jsonb_build_object('success', true);
$function$;

REVOKE ALL ON FUNCTION public.online_place_order(text, jsonb, text, text, text, text, text, jsonb, timestamptz, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.online_place_order(text, jsonb, text, text, text, text, text, jsonb, timestamptz, text) TO anon, authenticated;

-- Cael's coupon uses list: an online use has no terminal; it reads "Arke online".
CREATE OR REPLACE FUNCTION public.cmp_coupon_uses(p_org_id uuid)
 RETURNS TABLE(coupon_code text, redeemed_at timestamp with time zone, device_sn text, device_name text, released_at timestamp with time zone)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
BEGIN
  IF auth.uid() IS NULL OR NOT (public.is_sys_admin() OR p_org_id IN (SELECT public.member_org_ids())) THEN
    RETURN;
  END IF;
  RETURN QUERY
    SELECT r.coupon_code, r.redeemed_at, r.device_sn, coalesce(nullif(d.alias, ''), CASE WHEN r.ecr_ref_num LIKE 'ARKE:%' THEN 'Arke online' END), r.released_at
      FROM coupon_redemptions r
      JOIN coupons c ON c.code = r.coupon_code
      LEFT JOIN devices d ON d.sn = r.device_sn
     WHERE c.org_id = p_org_id
     ORDER BY r.redeemed_at DESC
     LIMIT 5000;
END $function$;
