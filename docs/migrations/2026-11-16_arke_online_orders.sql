-- 2026-11-16: Arke -- online ordering, phase 1: pickup at a restaurant.
-- docs/online_ordering_design.md, docs/database_design.md §3.
--
-- The customer opens the store's link (web/ourea-scan ?s=<slug>), builds a cart on
-- their phone, places the order (AWAITING_PAYMENT) and pays it online through the
-- store's Nuvei account (supabase/functions/arke-pay, same Simply Connect as Ourea
-- Scan's pay at the table). Only a payment Nuvei confirmed to the server makes the
-- order NEW, gives it a pickup number (A1, A2 ... per store per day) and shows it to
-- the store: Ourea accepts it (with a ready time), marks it READY, then PICKED_UP.
-- A store that can't make it rejects it and the payment goes back (arke-pay refund).
--
-- * Schema `online`, reached only through public.online_* (customer, anon),
--   online_pay_* (arke-pay, service_role), online_store_* (Ourea / Iris, a signed-in
--   terminal of the store) and cmp_online_* (Cael) functions.
-- * online.payments is this line of business's own transaction table (owner
--   2026-10-28: each vertical keeps its own); nothing is copied into
--   public.transactions or dine.payments.
-- * Prices, options, tax: the server's, same rules as scan_submit_order (options
--   must belong to the dish, required groups answered, max_select; tax summed per
--   rate, rounded once). The phone only sends ids and quantities.
-- * Known gap: online sales don't move retail stock (stock moves only through
--   transaction_items) -- restaurants rarely count stock; sold-out still blocks.

BEGIN;

CREATE SCHEMA IF NOT EXISTS online;
COMMENT ON SCHEMA online IS 'Arke online ordering; reached only through public online_* / online_pay_* / online_store_* / cmp_online_* RPCs';
REVOKE ALL ON SCHEMA online FROM public, anon, authenticated;

-- ---------------------------------------------------------------- tables

CREATE TABLE IF NOT EXISTS online.stores (
  org_id          UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
  slug            TEXT NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$'),
  is_open         BOOLEAN NOT NULL DEFAULT false,      -- the store's switch: taking online orders at all
  pickup_minutes  INTEGER NOT NULL DEFAULT 20 CHECK (pickup_minutes BETWEEN 5 AND 180),
  -- {"1": [["11:00","21:30"]], ... "7": []}: ISO weekday (1 = Monday) -> opening ranges, store's time zone.
  -- No row for a day = closed that day; hours NULL = open whenever is_open. A range that ends
  -- before it starts runs past midnight (counted on the day it starts).
  hours           JSONB,
  timezone        TEXT NOT NULL DEFAULT 'America/Toronto',
  address         TEXT,
  phone           TEXT,
  notice          TEXT CHECK (length(notice) <= 300),   -- shown at the top of the page ("Closed Monday for a holiday")
  last_pickup_day DATE,
  last_pickup_no  INTEGER NOT NULL DEFAULT 0,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE online.stores IS 'Arke: a store''s online ordering settings and its link (slug); daily pickup numbers.';

CREATE TABLE IF NOT EXISTS online.orders (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  public_token    TEXT NOT NULL UNIQUE DEFAULT replace(gen_random_uuid()::TEXT, '-', ''),   -- the customer's status link (122 random bits)
  client_ref      TEXT NOT NULL,
  fulfilment      TEXT NOT NULL DEFAULT 'PICKUP' CHECK (fulfilment IN ('PICKUP', 'OWN_DELIVERY', 'THIRD_PARTY')),
  status          TEXT NOT NULL DEFAULT 'AWAITING_PAYMENT'
                  CHECK (status IN ('AWAITING_PAYMENT', 'NEW', 'ACCEPTED', 'READY', 'PICKED_UP', 'REJECTED', 'EXPIRED')),
  customer_name   TEXT NOT NULL,
  customer_phone  TEXT NOT NULL,
  note            TEXT,
  items           JSONB NOT NULL,     -- [{product_id, name, options[], quantity, unit_price_cents, tax_rate_bps, note}]
  subtotal_cents  INTEGER NOT NULL,
  tax_cents       INTEGER NOT NULL,
  tip_cents       INTEGER NOT NULL DEFAULT 0,
  pickup_no       INTEGER,            -- given when paid: A<n>
  pickup_day      DATE,
  paid_at         TIMESTAMPTZ,
  accepted_at     TIMESTAMPTZ,
  ready_by        TIMESTAMPTZ,        -- the time the store promised when accepting
  ready_at        TIMESTAMPTZ,
  picked_up_at    TIMESTAMPTZ,
  rejected_at     TIMESTAMPTZ,
  reject_reason   TEXT,
  handled_by      TEXT,               -- staff name on the terminal, last step
  handled_by_sn   TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT online_orders_client_ref_key UNIQUE (org_id, client_ref)
);
CREATE INDEX IF NOT EXISTS online_orders_org_status_idx ON online.orders (org_id, status, created_at DESC);
COMMENT ON TABLE online.orders IS 'Arke online orders. AWAITING_PAYMENT until Nuvei confirms (then NEW + pickup number) -> ACCEPTED -> READY -> PICKED_UP; REJECTED (paid back) / EXPIRED (never paid).';

CREATE TABLE IF NOT EXISTS online.payments (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id              UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  order_id            UUID NOT NULL REFERENCES online.orders(id) ON DELETE CASCADE,
  amount_cents        INTEGER NOT NULL CHECK (amount_cents > 0),   -- food + tax, without tip
  tax_cents           INTEGER NOT NULL DEFAULT 0 CHECK (tax_cents >= 0),
  tip_cents           INTEGER NOT NULL DEFAULT 0 CHECK (tip_cents >= 0),
  currency            TEXT NOT NULL DEFAULT 'CAD',
  status              TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PAID', 'FAILED', 'REFUNDING', 'VOIDED', 'REFUNDED')),
  acquirer_account_id UUID REFERENCES merchant_acquirer_accounts(id),
  session_token       TEXT,
  nuvei_order_id      TEXT,
  status_checked_at   TIMESTAMPTZ,   -- getPaymentStatus once per session (Nuvei blocks pollers)
  transaction_id      TEXT,
  auth_code           TEXT,
  card                TEXT,
  reason              TEXT,
  refund_tx_id        TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  paid_at             TIMESTAMPTZ,
  refunded_at         TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS online_payments_order_idx ON online.payments (order_id);
COMMENT ON TABLE online.payments IS 'Arke payments (Nuvei): OPEN -> PAID / FAILED; a rejected order''s PAID payment -> REFUNDING -> VOIDED / REFUNDED. Written only through arke-pay.';

ALTER TABLE online.stores ENABLE ROW LEVEL SECURITY;
ALTER TABLE online.orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE online.payments ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON ALL TABLES IN SCHEMA online FROM public, anon, authenticated;

-- ---------------------------------------------------------------- helpers (not callable from outside)

-- Is the store taking orders right now: its switch, then its hours in its own time zone.
CREATE OR REPLACE FUNCTION online.open_now(s online.stores) RETURNS BOOLEAN
LANGUAGE plpgsql STABLE SET search_path = public, online AS $$
DECLARE v_local TIMESTAMP; v_t TIME; v_r JSONB;
BEGIN
  IF NOT s.is_open THEN RETURN false; END IF;
  IF s.hours IS NULL OR jsonb_typeof(s.hours) <> 'object' THEN RETURN true; END IF;
  v_local := now() AT TIME ZONE s.timezone;
  v_t := v_local::TIME;
  -- Today's ranges, then yesterday's that run past midnight.
  FOR v_r IN SELECT x FROM jsonb_array_elements(coalesce(s.hours->(extract(isodow FROM v_local)::INT::TEXT), '[]'::jsonb)) x LOOP
    IF (v_r->>1)::TIME > (v_r->>0)::TIME THEN
      IF v_t >= (v_r->>0)::TIME AND v_t < (v_r->>1)::TIME THEN RETURN true; END IF;
    ELSIF v_t >= (v_r->>0)::TIME THEN RETURN true;
    END IF;
  END LOOP;
  FOR v_r IN SELECT x FROM jsonb_array_elements(coalesce(s.hours->(extract(isodow FROM v_local - interval '1 day')::INT::TEXT), '[]'::jsonb)) x LOOP
    IF (v_r->>1)::TIME <= (v_r->>0)::TIME AND v_t < (v_r->>1)::TIME THEN RETURN true; END IF;
  END LOOP;
  RETURN false;
EXCEPTION WHEN others THEN
  RETURN false;   -- unreadable hours: closed, never a crash on the customer's page
END $$;

-- Price a cart against the store's menu: {success, lines, subtotal_cents, tax_cents} or {success:false, message, product?}.
-- Same rules as scan_submit_order.
CREATE OR REPLACE FUNCTION online.price_lines(p_org UUID, p_items JSONB) RETURNS JSONB
LANGUAGE plpgsql STABLE SET search_path = public, online AS $$
DECLARE
  v_item JSONB; v_p products%ROWTYPE; v_groups JSONB; v_g JSONB; v_opts JSONB; v_opt_ids JSONB;
  v_qty INT; v_unit INT; v_bps INT; v_picked INT; v_names JSONB; v_lines JSONB := '[]'::jsonb; v_sub INT := 0; v_tax INT;
BEGIN
  IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 OR jsonb_array_length(p_items) > 40 THEN
    RETURN jsonb_build_object('success', false, 'message', 'items_required');
  END IF;
  FOR v_item IN SELECT * FROM jsonb_array_elements(p_items) LOOP
    v_qty := coalesce((v_item->>'quantity')::INT, 0);
    IF v_qty < 1 OR v_qty > 20 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_quantity'); END IF;
    SELECT * INTO v_p FROM products
     WHERE id = nullif(v_item->>'product_id', '')::UUID AND org_id = p_org AND is_active AND vertical_type = 'RETAIL'
       AND coalesce((attributes->>'online_hidden')::BOOLEAN, false) = false;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'item_unavailable'); END IF;
    IF coalesce((v_p.attributes->>'stock_qty')::INT, 1) <= 0 THEN
      RETURN jsonb_build_object('success', false, 'message', 'sold_out', 'product', v_p.name);
    END IF;
    v_unit := v_p.price_cents;
    v_names := '[]'::jsonb;
    v_opt_ids := coalesce(v_item->'option_ids', '[]'::jsonb);
    IF jsonb_typeof(v_opt_ids) <> 'array' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_option', 'product', v_p.name); END IF;
    v_groups := coalesce(v_p.attributes->'modifier_groups', '[]'::jsonb);
    IF EXISTS (SELECT 1 FROM jsonb_array_elements_text(v_opt_ids) o
                WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(v_groups) g, jsonb_array_elements(g->'options') x WHERE x->>'id' = o)) THEN
      RETURN jsonb_build_object('success', false, 'message', 'bad_option', 'product', v_p.name);
    END IF;
    FOR v_g IN SELECT * FROM jsonb_array_elements(v_groups) LOOP
      SELECT count(*) INTO v_picked FROM jsonb_array_elements(v_g->'options') x WHERE v_opt_ids ? (x->>'id');
      IF coalesce((v_g->>'required')::BOOLEAN, false) AND v_picked = 0
         OR (v_g->>'max_select') IS NOT NULL AND v_picked > (v_g->>'max_select')::INT THEN
        RETURN jsonb_build_object('success', false, 'message', 'bad_option', 'product', v_p.name);
      END IF;
      FOR v_opts IN SELECT x FROM jsonb_array_elements(v_g->'options') x WHERE v_opt_ids ? (x->>'id') LOOP
        v_unit := v_unit + coalesce((v_opts->>'price_cents')::INT, 0);
        v_names := v_names || to_jsonb(v_opts->>'name');
      END LOOP;
    END LOOP;
    v_bps := coalesce((v_p.attributes->>'tax_rate_bps')::INT, 1300);
    v_sub := v_sub + v_unit * v_qty;
    v_lines := v_lines || jsonb_build_object(
      'product_id', v_p.id, 'name', v_p.name, 'options', v_names, 'option_ids', v_opt_ids, 'quantity', v_qty,
      'unit_price_cents', v_unit, 'tax_rate_bps', v_bps,
      'note', nullif(left(trim(coalesce(v_item->>'note', '')), 120), ''));
  END LOOP;
  SELECT coalesce(sum(round(t * b / 10000.0)), 0)::INT INTO v_tax FROM (
    SELECT (l->>'tax_rate_bps')::INT b, sum((l->>'quantity')::INT * (l->>'unit_price_cents')::INT) t
      FROM jsonb_array_elements(v_lines) l GROUP BY 1) r;
  RETURN jsonb_build_object('success', true, 'lines', v_lines, 'subtotal_cents', v_sub, 'tax_cents', v_tax);
END $$;

-- An order as the customer sees it (status page) and as the store sees it (plus phone, handled_by).
CREATE OR REPLACE FUNCTION online.order_json(o online.orders, p_for_store BOOLEAN) RETURNS JSONB
LANGUAGE sql STABLE SET search_path = public, online AS $$
  SELECT jsonb_build_object(
    'id', o.id, 'token', o.public_token, 'status', o.status, 'fulfilment', o.fulfilment,
    'pickup_code', CASE WHEN o.pickup_no IS NOT NULL THEN 'A' || o.pickup_no END,
    'customer_name', o.customer_name, 'note', o.note, 'items', o.items,
    'subtotal_cents', o.subtotal_cents, 'tax_cents', o.tax_cents, 'tip_cents', o.tip_cents,
    'total_cents', o.subtotal_cents + o.tax_cents + o.tip_cents,
    'created_at', o.created_at, 'paid_at', o.paid_at, 'accepted_at', o.accepted_at, 'ready_by', o.ready_by,
    'ready_at', o.ready_at, 'picked_up_at', o.picked_up_at, 'rejected_at', o.rejected_at, 'reject_reason', o.reject_reason)
  || CASE WHEN p_for_store THEN jsonb_build_object('customer_phone', o.customer_phone, 'handled_by', o.handled_by) ELSE '{}'::jsonb END
$$;

REVOKE ALL ON FUNCTION online.open_now(online.stores) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION online.price_lines(UUID, JSONB) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION online.order_json(online.orders, BOOLEAN) FROM public, anon, authenticated;

-- ---------------------------------------------------------------- customer (anon)

-- The store's page: who, whether it takes orders now, and its menu (same shape as scan_menu).
-- NULL = no such link.
CREATE OR REPLACE FUNCTION public.online_menu(p_slug TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT jsonb_build_object(
    'store', jsonb_build_object('name', o.name, 'logo_url', o.logo_url, 'slug', s.slug,
      'open_now', online.open_now(s), 'is_open', s.is_open, 'hours', s.hours, 'timezone', s.timezone,
      'pickup_minutes', s.pickup_minutes, 'address', s.address, 'phone', coalesce(s.phone, o.support_phone),
      'notice', s.notice,
      'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a
                             WHERE a.org_id = s.org_id AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE')),
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

-- Place an order (not paid yet). The same client_ref again = the same order.
CREATE OR REPLACE FUNCTION public.online_place_order(p_slug TEXT, p_items JSONB, p_name TEXT, p_phone TEXT,
                                                     p_note TEXT, p_client_ref TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_s online.stores%ROWTYPE; v_existing online.orders%ROWTYPE; v_priced JSONB; v_phone TEXT; v_id UUID; v_token TEXT;
BEGIN
  SELECT * INTO v_s FROM online.stores WHERE slug = lower(trim(p_slug));
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_store'); END IF;
  IF coalesce(trim(p_client_ref), '') = '' OR length(p_client_ref) > 64 THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_request');
  END IF;
  SELECT * INTO v_existing FROM online.orders WHERE org_id = v_s.org_id AND client_ref = p_client_ref;
  IF FOUND THEN
    RETURN jsonb_build_object('success', true, 'duplicate', true, 'id', v_existing.id, 'token', v_existing.public_token,
      'subtotal_cents', v_existing.subtotal_cents, 'tax_cents', v_existing.tax_cents);
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

  INSERT INTO online.orders (org_id, client_ref, customer_name, customer_phone, note, items, subtotal_cents, tax_cents)
  VALUES (v_s.org_id, p_client_ref, left(trim(p_name), 40), v_phone, nullif(left(trim(coalesce(p_note, '')), 200), ''),
          v_priced->'lines', (v_priced->>'subtotal_cents')::INT, (v_priced->>'tax_cents')::INT)
  RETURNING id, public_token INTO v_id, v_token;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'token', v_token,
    'subtotal_cents', (v_priced->>'subtotal_cents')::INT, 'tax_cents', (v_priced->>'tax_cents')::INT);
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
                                'pickup_minutes', v_s.pickup_minutes, 'timezone', v_s.timezone),
    'refund', (SELECT p.status FROM online.payments p WHERE p.order_id = v_o.id AND p.status IN ('REFUNDING', 'VOIDED', 'REFUNDED')
                ORDER BY p.created_at DESC LIMIT 1));
END $$;

REVOKE ALL ON FUNCTION public.online_menu(TEXT) FROM public;
REVOKE ALL ON FUNCTION public.online_place_order(TEXT, JSONB, TEXT, TEXT, TEXT, TEXT) FROM public;
REVOKE ALL ON FUNCTION public.online_order_status(TEXT) FROM public;
GRANT EXECUTE ON FUNCTION public.online_menu(TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_place_order(TEXT, JSONB, TEXT, TEXT, TEXT, TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_order_status(TEXT) TO anon, authenticated;

-- ---------------------------------------------------------------- payment (arke-pay, service_role)

CREATE OR REPLACE FUNCTION public.online_pay_open(p_token TEXT, p_tip_cents INT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
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
  v_amount := v_o.subtotal_cents + v_o.tax_cents;
  INSERT INTO online.payments (org_id, order_id, amount_cents, tax_cents, tip_cents, acquirer_account_id)
  VALUES (v_o.org_id, v_o.id, v_amount, v_o.tax_cents, coalesce(p_tip_cents, 0), v_account)
  RETURNING id INTO v_id;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'acquirer_account_id', v_account,
    'amount_cents', v_amount, 'tax_cents', v_o.tax_cents, 'tip_cents', coalesce(p_tip_cents, 0));
END $$;

CREATE OR REPLACE FUNCTION public.online_pay_attach(p_id UUID, p_session_token TEXT, p_order_id TEXT) RETURNS JSONB
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
  UPDATE online.payments SET session_token = p_session_token, nuvei_order_id = p_order_id WHERE id = p_id AND status = 'OPEN';
  SELECT jsonb_build_object('success', true);
$$;

-- One payment; [p_token] (the order's link) must match unless NULL (DMN). Claims the one
-- getPaymentStatus call a session gets.
CREATE OR REPLACE FUNCTION public.online_pay_get(p_id UUID, p_token TEXT, p_claim_status_check BOOLEAN) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_p online.payments%ROWTYPE; v_claimed BOOLEAN := false;
BEGIN
  SELECT p.* INTO v_p FROM online.payments p
   WHERE p.id = p_id AND (p_token IS NULL OR EXISTS (SELECT 1 FROM online.orders o WHERE o.id = p.order_id AND o.public_token = p_token))
   FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;
  IF p_claim_status_check AND v_p.status = 'OPEN' AND v_p.status_checked_at IS NULL AND v_p.session_token IS NOT NULL THEN
    UPDATE online.payments SET status_checked_at = now() WHERE id = p_id;
    v_claimed := true;
  END IF;
  RETURN to_jsonb(v_p) || jsonb_build_object('claimed', v_claimed);
END $$;

-- Nuvei confirmed the money: the payment is PAID, the order NEW with today's next pickup number.
CREATE OR REPLACE FUNCTION public.online_pay_settle(p_id UUID, p_transaction_id TEXT, p_paid_cents INT, p_auth_code TEXT, p_card TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_p online.payments%ROWTYPE; v_o online.orders%ROWTYPE; v_s online.stores%ROWTYPE; v_day DATE; v_no INT;
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
  RETURN jsonb_build_object('success', true, 'pickup_code', 'A' || v_no);
END $$;

CREATE OR REPLACE FUNCTION public.online_pay_fail(p_id UUID, p_reason TEXT) RETURNS JSONB
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
  UPDATE online.payments SET status = 'FAILED', reason = left(p_reason, 200) WHERE id = p_id AND status = 'OPEN';
  SELECT jsonb_build_object('success', true);
$$;

-- Refund of a rejected order's payment: begin (PAID -> REFUNDING, the terminal's store only),
-- then done (VOIDED / REFUNDED) or abort (back to PAID).
CREATE OR REPLACE FUNCTION public.online_refund_begin(p_org UUID, p_order_id UUID) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_p online.payments%ROWTYPE;
BEGIN
  SELECT p.* INTO v_p FROM online.payments p JOIN online.orders o ON o.id = p.order_id
   WHERE p.order_id = p_order_id AND o.org_id = p_org AND o.status = 'REJECTED' AND p.status = 'PAID'
   ORDER BY p.paid_at LIMIT 1 FOR UPDATE OF p;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'nothing_to_refund'); END IF;
  UPDATE online.payments SET status = 'REFUNDING' WHERE id = v_p.id;
  RETURN jsonb_build_object('success', true, 'id', v_p.id, 'acquirer_account_id', v_p.acquirer_account_id,
    'transaction_id', v_p.transaction_id, 'cents', v_p.amount_cents + v_p.tip_cents);
END $$;

CREATE OR REPLACE FUNCTION public.online_refund_done(p_id UUID, p_status TEXT, p_refund_tx_id TEXT) RETURNS JSONB
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
  UPDATE online.payments SET status = CASE WHEN p_status = 'VOIDED' THEN 'VOIDED' ELSE 'REFUNDED' END,
         refund_tx_id = nullif(trim(p_refund_tx_id), ''), refunded_at = now()
   WHERE id = p_id AND status = 'REFUNDING';
  SELECT jsonb_build_object('success', true);
$$;

CREATE OR REPLACE FUNCTION public.online_refund_abort(p_id UUID, p_reason TEXT) RETURNS JSONB
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
  UPDATE online.payments SET status = 'PAID', reason = left('refund failed: ' || coalesce(p_reason, ''), 200)
   WHERE id = p_id AND status = 'REFUNDING';
  SELECT jsonb_build_object('success', true);
$$;

REVOKE ALL ON FUNCTION public.online_pay_open(TEXT, INT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_pay_attach(UUID, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_pay_get(UUID, TEXT, BOOLEAN) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_pay_settle(UUID, TEXT, INT, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_pay_fail(UUID, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_refund_begin(UUID, UUID) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_refund_done(UUID, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_refund_abort(UUID, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_pay_open(TEXT, INT) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_pay_attach(UUID, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_pay_get(UUID, TEXT, BOOLEAN) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_pay_settle(UUID, TEXT, INT, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_pay_fail(UUID, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_refund_begin(UUID, UUID) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_refund_done(UUID, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_refund_abort(UUID, TEXT) TO service_role;

-- ---------------------------------------------------------------- the store's terminal (Ourea / Iris)

-- Today's paid orders still to handle, and the last few done (store's day, newest first).
CREATE OR REPLACE FUNCTION public.online_store_orders() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT coalesce(jsonb_agg(online.order_json(o, true) ORDER BY o.paid_at DESC), '[]'::jsonb)
    FROM online.orders o
   WHERE o.org_id = dine_device_org()
     AND (o.status IN ('NEW', 'ACCEPTED', 'READY')
          OR o.status IN ('PICKED_UP', 'REJECTED') AND o.paid_at > now() - interval '12 hours')
$$;

-- Move an order on: ACCEPT (with minutes until ready), READY, PICKED_UP, REJECT (with a reason;
-- the terminal then calls arke-pay refund).
CREATE OR REPLACE FUNCTION public.online_store_step(p_id UUID, p_step TEXT, p_minutes INT, p_reason TEXT, p_staff_name TEXT, p_device_sn TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_org UUID := dine_device_org(); v_o online.orders%ROWTYPE; v_staff TEXT := nullif(left(trim(coalesce(p_staff_name, '')), 40), '');
BEGIN
  SELECT * INTO v_o FROM online.orders WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_o.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'order_not_found'); END IF;
  IF p_step = 'ACCEPT' AND v_o.status = 'NEW' THEN
    UPDATE online.orders SET status = 'ACCEPTED', accepted_at = now(),
           ready_by = now() + make_interval(mins => greatest(1, least(coalesce(p_minutes, 20), 240)))
     WHERE id = p_id;
  ELSIF p_step = 'READY' AND v_o.status IN ('NEW', 'ACCEPTED') THEN
    UPDATE online.orders SET status = 'READY', ready_at = now(), accepted_at = coalesce(accepted_at, now()) WHERE id = p_id;
  ELSIF p_step = 'PICKED_UP' AND v_o.status IN ('ACCEPTED', 'READY') THEN
    UPDATE online.orders SET status = 'PICKED_UP', picked_up_at = now() WHERE id = p_id;
  ELSIF p_step = 'REJECT' AND v_o.status IN ('NEW', 'ACCEPTED') THEN
    IF coalesce(trim(p_reason), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'reason_required'); END IF;
    UPDATE online.orders SET status = 'REJECTED', rejected_at = now(), reject_reason = left(trim(p_reason), 200) WHERE id = p_id;
  ELSE
    RETURN jsonb_build_object('success', false, 'message', 'order_' || lower(v_o.status));
  END IF;
  UPDATE online.orders SET handled_by = v_staff, handled_by_sn = nullif(trim(coalesce(p_device_sn, '')), '') WHERE id = p_id;
  SELECT * INTO v_o FROM online.orders WHERE id = p_id;
  RETURN jsonb_build_object('success', true, 'order', online.order_json(v_o, true));
END $$;

REVOKE ALL ON FUNCTION public.online_store_orders() FROM public, anon;
REVOKE ALL ON FUNCTION public.online_store_step(UUID, TEXT, INT, TEXT, TEXT, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.online_store_orders() TO authenticated;
GRANT EXECUTE ON FUNCTION public.online_store_step(UUID, TEXT, INT, TEXT, TEXT, TEXT) TO authenticated;

-- ---------------------------------------------------------------- Cael

-- The store's online settings (NULL = not set up yet).
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
    'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a WHERE a.org_id = p_org AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE'));
END $$;

CREATE OR REPLACE FUNCTION public.cmp_online_store_save(p_org UUID, p_slug TEXT, p_is_open BOOLEAN, p_pickup_minutes INT,
                                                        p_hours JSONB, p_address TEXT, p_phone TEXT, p_notice TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_slug TEXT := lower(trim(coalesce(p_slug, '')));
BEGIN
  IF NOT (is_sys_admin() OR (p_org IN (SELECT member_org_ids()) AND has_permission('catalog.manage'))) THEN
    RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
  END IF;
  IF v_slug !~ '^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_slug'); END IF;
  IF EXISTS (SELECT 1 FROM online.stores WHERE slug = v_slug AND org_id <> p_org) THEN
    RETURN jsonb_build_object('success', false, 'message', 'slug_taken');
  END IF;
  IF p_hours IS NOT NULL AND jsonb_typeof(p_hours) <> 'object' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_hours'); END IF;
  INSERT INTO online.stores (org_id, slug, is_open, pickup_minutes, hours, address, phone, notice, updated_at)
  VALUES (p_org, v_slug, coalesce(p_is_open, false), coalesce(p_pickup_minutes, 20), p_hours,
          nullif(trim(p_address), ''), nullif(trim(p_phone), ''), nullif(trim(p_notice), ''), now())
  ON CONFLICT (org_id) DO UPDATE SET slug = EXCLUDED.slug, is_open = EXCLUDED.is_open, pickup_minutes = EXCLUDED.pickup_minutes,
         hours = EXCLUDED.hours, address = EXCLUDED.address, phone = EXCLUDED.phone, notice = EXCLUDED.notice, updated_at = now();
  RETURN jsonb_build_object('success', true, 'store', cmp_online_store_get(p_org));
END $$;

-- Online orders for Cael (read-only list, newest first).
CREATE OR REPLACE FUNCTION public.cmp_online_orders(p_org UUID, p_limit INT DEFAULT 100) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, online AS $$
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501'; END IF;
  RETURN coalesce((SELECT jsonb_agg(online.order_json(o, true) ORDER BY o.created_at DESC)
                     FROM online.orders o
                    WHERE o.id IN (SELECT id FROM online.orders WHERE org_id = p_org AND status <> 'AWAITING_PAYMENT'
                                    ORDER BY created_at DESC LIMIT least(coalesce(p_limit, 100), 500))), '[]'::jsonb);
END $$;

REVOKE ALL ON FUNCTION public.cmp_online_store_get(UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_online_store_save(UUID, TEXT, BOOLEAN, INT, JSONB, TEXT, TEXT, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_online_orders(UUID, INT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_online_store_get(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_online_store_save(UUID, TEXT, BOOLEAN, INT, JSONB, TEXT, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_online_orders(UUID, INT) TO authenticated;

COMMIT;
