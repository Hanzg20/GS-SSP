-- 2026-11-17: Arke phase 2 -- the store's own delivery (docs/online_ordering_design.md).
-- On top of 2026-11-16_arke_online_orders.sql.
--
-- * A store turns delivery on in Cael and draws its delivery zones by postal code
--   (Canada: the first 3 to 6 characters, e.g. "K2K" or "K2K1X"): each zone has its
--   own delivery fee and minimum order. No map / geocoding service: the customer's
--   postal code picks the zone (the longest matching prefix), or "we don't deliver there".
-- * The customer picks pickup or delivery; a delivery order carries the address and
--   the fee. The server prices it (food + delivery fee, tax on both: the fee at the
--   store's delivery_tax_bps, HST 13% by default) -- the phone only sends a postal code.
-- * Delivery orders go NEW -> ACCEPTED (the store promises a time: when it arrives)
--   -> READY -> OUT_FOR_DELIVERY (a driver -- on Ourea, or Iris's delivery screen --
--   took it) -> DELIVERED. A driver who can't hand it over brings it back (READY again,
--   attempts + 1, the reason kept). Already paid online: nobody collects at the door.
--   It is NOT copied into public.delivery_orders (that table is Iris's
--   collect-at-the-door orders; each line of business keeps its own records).
-- * The payment keeps the customer's email (Nuvei needs it anyway) and the page's
--   language, so arke-pay can mail the order's link once it is paid.
-- * Pickup numbers: one daily sequence per store; pickup orders read A<n>, delivery D<n>.

BEGIN;

-- ---------------------------------------------------------------- tables

ALTER TABLE online.stores
  ADD COLUMN IF NOT EXISTS delivery_enabled  BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS delivery_minutes  INTEGER NOT NULL DEFAULT 45,
  ADD COLUMN IF NOT EXISTS delivery_tax_bps  INTEGER NOT NULL DEFAULT 1300;
ALTER TABLE online.stores DROP CONSTRAINT IF EXISTS stores_delivery_minutes_check;
ALTER TABLE online.stores ADD CONSTRAINT stores_delivery_minutes_check CHECK (delivery_minutes BETWEEN 10 AND 240);
ALTER TABLE online.stores DROP CONSTRAINT IF EXISTS stores_delivery_tax_bps_check;
ALTER TABLE online.stores ADD CONSTRAINT stores_delivery_tax_bps_check CHECK (delivery_tax_bps BETWEEN 0 AND 3000);

CREATE TABLE IF NOT EXISTS online.delivery_zones (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id           UUID NOT NULL REFERENCES online.stores(org_id) ON DELETE CASCADE,
  name             TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 40),
  postal_prefixes  TEXT[] NOT NULL CHECK (cardinality(postal_prefixes) BETWEEN 1 AND 200),   -- normalised: upper case, no spaces
  fee_cents        INTEGER NOT NULL DEFAULT 0 CHECK (fee_cents BETWEEN 0 AND 10000),
  min_order_cents  INTEGER NOT NULL DEFAULT 0 CHECK (min_order_cents BETWEEN 0 AND 100000),
  sort             INTEGER NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS online_delivery_zones_org_idx ON online.delivery_zones (org_id, sort);
COMMENT ON TABLE online.delivery_zones IS 'Arke: where a store delivers -- postal code prefixes, the fee and the minimum order there.';
ALTER TABLE online.delivery_zones ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON online.delivery_zones FROM public, anon, authenticated;

ALTER TABLE online.orders
  ADD COLUMN IF NOT EXISTS delivery_address    JSONB,           -- {line1, unit, city, postal, instructions}
  ADD COLUMN IF NOT EXISTS delivery_zone       TEXT,            -- the zone's name when ordered
  ADD COLUMN IF NOT EXISTS delivery_fee_cents  INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS out_at              TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS out_by              TEXT,            -- the driver (staff name on the terminal)
  ADD COLUMN IF NOT EXISTS out_by_sn           TEXT,
  ADD COLUMN IF NOT EXISTS delivered_at        TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS delivery_attempts   INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS last_attempt_note   TEXT;
ALTER TABLE online.orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE online.orders ADD CONSTRAINT orders_status_check
  CHECK (status IN ('AWAITING_PAYMENT', 'NEW', 'ACCEPTED', 'READY', 'PICKED_UP', 'OUT_FOR_DELIVERY', 'DELIVERED', 'REJECTED', 'EXPIRED'));
ALTER TABLE online.orders DROP CONSTRAINT IF EXISTS orders_delivery_check;
ALTER TABLE online.orders ADD CONSTRAINT orders_delivery_check
  CHECK (fulfilment <> 'OWN_DELIVERY' OR delivery_address IS NOT NULL);
COMMENT ON TABLE online.orders IS 'Arke online orders. AWAITING_PAYMENT until Nuvei confirms (then NEW + number) -> ACCEPTED -> READY -> PICKED_UP, or for delivery -> OUT_FOR_DELIVERY -> DELIVERED; REJECTED (paid back) / EXPIRED (never paid). tax_cents includes the delivery fee''s tax.';

ALTER TABLE online.payments
  ADD COLUMN IF NOT EXISTS email TEXT,       -- the customer's (Nuvei's receipt, and the order link we mail)
  ADD COLUMN IF NOT EXISTS lang  TEXT;       -- the page's language when paying (zh / en / fr / ar)
COMMENT ON COLUMN online.payments.amount_cents IS 'food + delivery fee + tax, without tip';

-- ---------------------------------------------------------------- helpers

CREATE OR REPLACE FUNCTION online.postal_norm(p TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE AS $$ SELECT upper(regexp_replace(coalesce(p, ''), '[^A-Za-z0-9]', '', 'g')) $$;

-- The zone a postal code falls in: the longest matching prefix (then the zone listed first).
CREATE OR REPLACE FUNCTION online.zone_for(p_org UUID, p_postal TEXT) RETURNS online.delivery_zones
LANGUAGE sql STABLE SET search_path = public, online AS $$
  SELECT z.* FROM online.delivery_zones z, unnest(z.postal_prefixes) pre
   WHERE z.org_id = p_org AND online.postal_norm(p_postal) LIKE pre || '%'
   ORDER BY length(pre) DESC, z.sort, z.created_at
   LIMIT 1
$$;

-- What delivery costs for this order: {success, zone, fee_cents, fee_tax_cents, min_order_cents} or a refusal.
CREATE OR REPLACE FUNCTION online.delivery_price(v_s online.stores, p_postal TEXT, p_subtotal INT) RETURNS JSONB
LANGUAGE plpgsql STABLE SET search_path = public, online AS $$
DECLARE v_z online.delivery_zones%ROWTYPE; v_postal TEXT := online.postal_norm(p_postal);
BEGIN
  IF NOT v_s.delivery_enabled OR NOT EXISTS (SELECT 1 FROM online.delivery_zones WHERE org_id = v_s.org_id) THEN
    RETURN jsonb_build_object('success', false, 'message', 'no_delivery');
  END IF;
  IF v_postal !~ '^[A-Z][0-9][A-Z][0-9][A-Z][0-9]$' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_postal'); END IF;
  v_z := online.zone_for(v_s.org_id, v_postal);
  IF v_z.id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'out_of_area'); END IF;
  IF p_subtotal IS NOT NULL AND p_subtotal < v_z.min_order_cents THEN
    RETURN jsonb_build_object('success', false, 'message', 'below_minimum', 'zone', v_z.name,
      'fee_cents', v_z.fee_cents, 'min_order_cents', v_z.min_order_cents);
  END IF;
  RETURN jsonb_build_object('success', true, 'zone', v_z.name, 'fee_cents', v_z.fee_cents,
    'fee_tax_cents', round(v_z.fee_cents * v_s.delivery_tax_bps / 10000.0)::INT,
    'min_order_cents', v_z.min_order_cents, 'minutes', v_s.delivery_minutes, 'postal', v_postal);
END $$;

-- An order as the customer sees it (status page) and as the store sees it (plus phone, handled_by).
CREATE OR REPLACE FUNCTION online.order_json(o online.orders, p_for_store BOOLEAN) RETURNS JSONB
LANGUAGE sql STABLE SET search_path = public, online AS $$
  SELECT jsonb_build_object(
    'id', o.id, 'token', o.public_token, 'status', o.status, 'fulfilment', o.fulfilment,
    'pickup_code', CASE WHEN o.pickup_no IS NOT NULL THEN (CASE WHEN o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END) || o.pickup_no END,
    'customer_name', o.customer_name, 'note', o.note, 'items', o.items,
    'subtotal_cents', o.subtotal_cents, 'tax_cents', o.tax_cents, 'tip_cents', o.tip_cents,
    'delivery_fee_cents', o.delivery_fee_cents, 'delivery_address', o.delivery_address, 'delivery_zone', o.delivery_zone,
    'total_cents', o.subtotal_cents + o.delivery_fee_cents + o.tax_cents + o.tip_cents,
    'created_at', o.created_at, 'paid_at', o.paid_at, 'accepted_at', o.accepted_at, 'ready_by', o.ready_by,
    'ready_at', o.ready_at, 'picked_up_at', o.picked_up_at, 'out_at', o.out_at, 'delivered_at', o.delivered_at,
    'rejected_at', o.rejected_at, 'reject_reason', o.reject_reason)
  || CASE WHEN p_for_store THEN jsonb_build_object('customer_phone', o.customer_phone, 'handled_by', o.handled_by,
       'out_by', o.out_by, 'out_by_sn', o.out_by_sn, 'delivery_attempts', o.delivery_attempts, 'last_attempt_note', o.last_attempt_note)
     ELSE '{}'::jsonb END
$$;

REVOKE ALL ON FUNCTION online.postal_norm(TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION online.zone_for(UUID, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION online.delivery_price(online.stores, TEXT, INT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION online.order_json(online.orders, BOOLEAN) FROM public, anon, authenticated;

-- ---------------------------------------------------------------- customer (anon)

CREATE OR REPLACE FUNCTION public.online_menu(p_slug TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT jsonb_build_object(
    'store', jsonb_build_object('name', o.name, 'logo_url', o.logo_url, 'slug', s.slug,
      'open_now', online.open_now(s), 'is_open', s.is_open, 'hours', s.hours, 'timezone', s.timezone,
      'pickup_minutes', s.pickup_minutes, 'address', s.address, 'phone', coalesce(s.phone, o.support_phone),
      'notice', s.notice,
      'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a
                             WHERE a.org_id = s.org_id AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE'),
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
$$;

-- Does the store deliver to this postal code, and for how much ([p_subtotal]: checks the minimum too).
CREATE OR REPLACE FUNCTION public.online_delivery_quote(p_slug TEXT, p_postal TEXT, p_subtotal_cents INT DEFAULT NULL) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_s online.stores%ROWTYPE;
BEGIN
  SELECT * INTO v_s FROM online.stores WHERE slug = lower(trim(p_slug));
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_store'); END IF;
  RETURN online.delivery_price(v_s, p_postal, p_subtotal_cents);
END $$;

-- Place an order (not paid yet). The same client_ref again = the same order.
-- [p_fulfilment] PICKUP (default) / OWN_DELIVERY with [p_address] {line1, unit, city, postal, instructions}.
DROP FUNCTION IF EXISTS public.online_place_order(TEXT, JSONB, TEXT, TEXT, TEXT, TEXT);
CREATE OR REPLACE FUNCTION public.online_place_order(p_slug TEXT, p_items JSONB, p_name TEXT, p_phone TEXT,
                                                     p_note TEXT, p_client_ref TEXT,
                                                     p_fulfilment TEXT DEFAULT 'PICKUP', p_address JSONB DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
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
  IF NOT online.open_now(v_s) THEN RETURN jsonb_build_object('success', false, 'message', 'store_closed'); END IF;
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
                             delivery_address, delivery_zone, delivery_fee_cents)
  VALUES (v_s.org_id, p_client_ref, v_ful, left(trim(p_name), 40), v_phone, nullif(left(trim(coalesce(p_note, '')), 200), ''),
          v_priced->'lines', v_sub, v_tax, v_addr, v_dp->>'zone', v_fee)
  RETURNING id, public_token INTO v_id, v_token;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'token', v_token,
    'subtotal_cents', v_sub, 'tax_cents', v_tax, 'delivery_fee_cents', v_fee);
END $$;

-- The customer's status page. Unpaid orders older than 30 minutes read as EXPIRED.
CREATE OR REPLACE FUNCTION public.online_order_status(p_token TEXT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_o online.orders%ROWTYPE; v_s online.stores%ROWTYPE;
BEGIN
  UPDATE online.orders SET status = 'EXPIRED'
   WHERE public_token = p_token AND status = 'AWAITING_PAYMENT' AND created_at < now() - interval '30 minutes'
     AND NOT EXISTS (SELECT 1 FROM online.payments p WHERE p.order_id = orders.id AND p.status = 'OPEN' AND p.created_at > now() - interval '15 minutes');
  SELECT * INTO v_o FROM online.orders WHERE public_token = p_token;
  IF NOT FOUND THEN RETURN NULL; END IF;
  SELECT * INTO v_s FROM online.stores WHERE org_id = v_o.org_id;
  RETURN online.order_json(v_o, false) || jsonb_build_object(
    'store', jsonb_build_object('name', (SELECT name FROM organizations WHERE id = v_o.org_id),
                                'logo_url', (SELECT logo_url FROM organizations WHERE id = v_o.org_id),
                                'slug', v_s.slug, 'address', v_s.address,
                                'phone', coalesce(v_s.phone, (SELECT support_phone FROM organizations WHERE id = v_o.org_id)),
                                'pickup_minutes', v_s.pickup_minutes, 'delivery_minutes', v_s.delivery_minutes, 'timezone', v_s.timezone),
    'refund', (SELECT p.status FROM online.payments p WHERE p.order_id = v_o.id AND p.status IN ('REFUNDING', 'VOIDED', 'REFUNDED')
                ORDER BY p.created_at DESC LIMIT 1));
END $$;

REVOKE ALL ON FUNCTION public.online_menu(TEXT) FROM public;
REVOKE ALL ON FUNCTION public.online_delivery_quote(TEXT, TEXT, INT) FROM public;
REVOKE ALL ON FUNCTION public.online_place_order(TEXT, JSONB, TEXT, TEXT, TEXT, TEXT, TEXT, JSONB) FROM public;
REVOKE ALL ON FUNCTION public.online_order_status(TEXT) FROM public;
GRANT EXECUTE ON FUNCTION public.online_menu(TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_delivery_quote(TEXT, TEXT, INT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_place_order(TEXT, JSONB, TEXT, TEXT, TEXT, TEXT, TEXT, JSONB) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_order_status(TEXT) TO anon, authenticated;

-- ---------------------------------------------------------------- payment (arke-pay, service_role)

-- [p_email] / [p_lang]: kept on the payment so the paid order's link can be mailed (arke-pay).
DROP FUNCTION IF EXISTS public.online_pay_open(TEXT, INT);
CREATE OR REPLACE FUNCTION public.online_pay_open(p_token TEXT, p_tip_cents INT, p_email TEXT DEFAULT NULL, p_lang TEXT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
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
  v_amount := v_o.subtotal_cents + v_o.delivery_fee_cents + v_o.tax_cents;
  INSERT INTO online.payments (org_id, order_id, amount_cents, tax_cents, tip_cents, acquirer_account_id, email, lang)
  VALUES (v_o.org_id, v_o.id, v_amount, v_o.tax_cents, coalesce(p_tip_cents, 0), v_account,
          nullif(left(trim(coalesce(p_email, '')), 200), ''),
          CASE WHEN p_lang IN ('zh', 'en', 'fr', 'ar') THEN p_lang END)
  RETURNING id INTO v_id;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'acquirer_account_id', v_account,
    'amount_cents', v_amount, 'tax_cents', v_o.tax_cents, 'tip_cents', coalesce(p_tip_cents, 0));
END $$;

-- Nuvei confirmed the money: the payment is PAID, the order NEW with today's next number.
-- The first settle also answers what the "it's paid" email needs (mail: true).
CREATE OR REPLACE FUNCTION public.online_pay_settle(p_id UUID, p_transaction_id TEXT, p_paid_cents INT, p_auth_code TEXT, p_card TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
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
  v_day := (now() AT TIME ZONE v_s.timezone)::DATE;
  v_no := CASE WHEN v_s.last_pickup_day = v_day THEN v_s.last_pickup_no + 1 ELSE 1 END;
  UPDATE online.stores SET last_pickup_day = v_day, last_pickup_no = v_no WHERE org_id = v_s.org_id;
  UPDATE online.orders SET status = 'NEW', paid_at = now(), tip_cents = v_p.tip_cents, pickup_no = v_no, pickup_day = v_day
   WHERE id = v_o.id;
  v_code := CASE WHEN v_o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END || v_no;
  RETURN jsonb_build_object('success', true, 'pickup_code', v_code,
    'mail', jsonb_build_object('email', v_p.email, 'lang', v_p.lang, 'token', v_o.public_token, 'code', v_code,
      'fulfilment', v_o.fulfilment, 'store', (SELECT name FROM organizations WHERE id = v_o.org_id),
      'total_cents', v_p.amount_cents + v_p.tip_cents));
END $$;

REVOKE ALL ON FUNCTION public.online_pay_open(TEXT, INT, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_pay_settle(UUID, TEXT, INT, TEXT, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_pay_open(TEXT, INT, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_pay_settle(UUID, TEXT, INT, TEXT, TEXT) TO service_role;

-- ---------------------------------------------------------------- the store's terminal (Ourea / Iris)

CREATE OR REPLACE FUNCTION public.online_store_orders() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT coalesce(jsonb_agg(online.order_json(o, true) ORDER BY o.paid_at DESC), '[]'::jsonb)
    FROM online.orders o
   WHERE o.org_id = dine_device_org()
     AND (o.status IN ('NEW', 'ACCEPTED', 'READY', 'OUT_FOR_DELIVERY')
          OR o.status IN ('PICKED_UP', 'DELIVERED', 'REJECTED') AND o.paid_at > now() - interval '12 hours')
$$;

-- Move an order on. Both: ACCEPT (minutes until ready -- for delivery, until it arrives), READY,
-- REJECT (a reason; the terminal then calls arke-pay-refund). Pickup: PICKED_UP. Delivery: OUT
-- (the driver = p_staff_name), DELIVERED, BACK (couldn't hand it over: p_reason, READY again).
CREATE OR REPLACE FUNCTION public.online_store_step(p_id UUID, p_step TEXT, p_minutes INT, p_reason TEXT, p_staff_name TEXT, p_device_sn TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_org UUID := dine_device_org(); v_o online.orders%ROWTYPE; v_staff TEXT := nullif(left(trim(coalesce(p_staff_name, '')), 40), '');
        v_sn TEXT := nullif(trim(coalesce(p_device_sn, '')), ''); v_delivery BOOLEAN;
BEGIN
  SELECT * INTO v_o FROM online.orders WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_o.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'order_not_found'); END IF;
  v_delivery := v_o.fulfilment = 'OWN_DELIVERY';
  IF p_step = 'ACCEPT' AND v_o.status = 'NEW' THEN
    UPDATE online.orders SET status = 'ACCEPTED', accepted_at = now(),
           ready_by = now() + make_interval(mins => greatest(1, least(coalesce(p_minutes, 20), 240)))
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
END $$;

-- ---------------------------------------------------------------- Cael

CREATE OR REPLACE FUNCTION public.cmp_online_store_get(p_org UUID) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_s online.stores%ROWTYPE;
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501'; END IF;
  SELECT * INTO v_s FROM online.stores WHERE org_id = p_org;
  IF NOT FOUND THEN RETURN NULL; END IF;
  RETURN jsonb_build_object('slug', v_s.slug, 'is_open', v_s.is_open, 'open_now', online.open_now(v_s),
    'pickup_minutes', v_s.pickup_minutes, 'hours', v_s.hours, 'timezone', v_s.timezone,
    'address', v_s.address, 'phone', v_s.phone, 'notice', v_s.notice,
    'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a WHERE a.org_id = p_org AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE'),
    'delivery_enabled', v_s.delivery_enabled, 'delivery_minutes', v_s.delivery_minutes, 'delivery_tax_bps', v_s.delivery_tax_bps,
    'zones', coalesce((SELECT jsonb_agg(jsonb_build_object('id', z.id, 'name', z.name, 'postal_prefixes', to_jsonb(z.postal_prefixes),
                         'fee_cents', z.fee_cents, 'min_order_cents', z.min_order_cents) ORDER BY z.sort, z.created_at)
                         FROM online.delivery_zones z WHERE z.org_id = p_org), '[]'::jsonb));
END $$;

-- Delivery settings and the whole list of zones (replaced): [{name, postal_prefixes[], fee_cents, min_order_cents}].
CREATE OR REPLACE FUNCTION public.cmp_online_delivery_save(p_org UUID, p_enabled BOOLEAN, p_minutes INT, p_tax_bps INT, p_zones JSONB)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_z JSONB; v_i INT := 0; v_pre TEXT[]; v_bad TEXT;
BEGIN
  IF NOT (is_sys_admin() OR (p_org IN (SELECT member_org_ids()) AND has_permission('catalog.manage'))) THEN
    RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM online.stores WHERE org_id = p_org) THEN RETURN jsonb_build_object('success', false, 'message', 'no_store'); END IF;
  IF coalesce(p_minutes, 45) NOT BETWEEN 10 AND 240 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_minutes'); END IF;
  IF coalesce(p_tax_bps, 1300) NOT BETWEEN 0 AND 3000 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_tax'); END IF;
  IF p_zones IS NULL OR jsonb_typeof(p_zones) <> 'array' OR jsonb_array_length(p_zones) > 20 THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_zones');
  END IF;
  IF coalesce(p_enabled, false) AND jsonb_array_length(p_zones) = 0 THEN RETURN jsonb_build_object('success', false, 'message', 'zones_required'); END IF;

  DELETE FROM online.delivery_zones WHERE org_id = p_org;
  FOR v_z IN SELECT * FROM jsonb_array_elements(p_zones) LOOP
    v_i := v_i + 1;
    SELECT array_agg(DISTINCT online.postal_norm(x)) INTO v_pre
      FROM jsonb_array_elements_text(coalesce(v_z->'postal_prefixes', '[]'::jsonb)) x WHERE online.postal_norm(x) <> '';
    SELECT p INTO v_bad FROM unnest(coalesce(v_pre, '{}')) p WHERE p !~ '^[A-Z][0-9][A-Z]([0-9]([A-Z]([0-9])?)?)?$' LIMIT 1;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'bad_postal:%', v_bad USING ERRCODE = '22023'; END IF;
    IF coalesce(cardinality(v_pre), 0) = 0 THEN RAISE EXCEPTION 'zone_postal_required:%', v_i USING ERRCODE = '22023'; END IF;
    INSERT INTO online.delivery_zones (org_id, name, postal_prefixes, fee_cents, min_order_cents, sort)
    VALUES (p_org, left(coalesce(nullif(trim(v_z->>'name'), ''), 'Zone ' || v_i), 40), v_pre,
            coalesce((v_z->>'fee_cents')::INT, 0), coalesce((v_z->>'min_order_cents')::INT, 0), v_i);
  END LOOP;
  UPDATE online.stores SET delivery_enabled = coalesce(p_enabled, false), delivery_minutes = coalesce(p_minutes, 45),
         delivery_tax_bps = coalesce(p_tax_bps, 1300), updated_at = now()
   WHERE org_id = p_org;
  RETURN jsonb_build_object('success', true, 'store', cmp_online_store_get(p_org));
EXCEPTION WHEN invalid_parameter_value THEN
  RETURN jsonb_build_object('success', false, 'message', split_part(SQLERRM, ':', 1), 'detail', split_part(SQLERRM, ':', 2));
END $$;

REVOKE ALL ON FUNCTION public.cmp_online_delivery_save(UUID, BOOLEAN, INT, INT, JSONB) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_online_delivery_save(UUID, BOOLEAN, INT, INT, JSONB) TO authenticated;

COMMIT;
