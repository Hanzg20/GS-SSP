-- 2026-11-17: Uber Eats orders into Ourea, step 2 of docs/uber_eats_design.md (receiving).
-- On top of 2026-11-17_arke_delivery.sql.
--
-- * Uber Eats orders live in online.orders next to Arke's, told apart by `channel`
--   (ARKE / UBER_EATS), so Ourea's online-orders screen, chime, banner and kitchen
--   ticket work for them as they are. Uber collects the money: an Uber order never has
--   an online.payments row, is never refunded by Arke, and never shows on Arke's
--   customer status page.
-- * online.uber_stores: our store <-> Uber's store id, filled by a sys admin in Cael.
--   A webhook for an Uber store we don't know is logged and not taken in (we never
--   accept an order for a store nobody linked).
-- * online.uber_events: every webhook Uber sent (event_id is unique -- Uber retries up
--   to 7 times), what we did with it. Uber orders are unique per Uber order id.
-- * Step 2 is receive-and-show only: the store accepts / denies / readies on Uber's own
--   tablet, and online_store_step refuses Uber orders (step 3 does them through
--   ubereats-action, Uber confirming first). Uber's cancel comes in as CANCELLED.
-- Written only by the ubereats-webhook Edge Function (service_role).

BEGIN;

-- ---------------------------------------------------------------- tables

ALTER TABLE online.orders
  ADD COLUMN IF NOT EXISTS channel        TEXT NOT NULL DEFAULT 'ARKE',
  ADD COLUMN IF NOT EXISTS external_id    TEXT,          -- Uber's order id (uuid)
  ADD COLUMN IF NOT EXISTS external_code  TEXT,          -- Uber's display id, what the courier / customer says
  ADD COLUMN IF NOT EXISTS external_type  TEXT,          -- Uber's fulfilment: DELIVERY_BY_UBER / PICK_UP / ...
  ADD COLUMN IF NOT EXISTS cancelled_at   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS cancel_reason  TEXT;
ALTER TABLE online.orders DROP CONSTRAINT IF EXISTS orders_channel_check;
ALTER TABLE online.orders ADD CONSTRAINT orders_channel_check CHECK (channel IN ('ARKE', 'UBER_EATS'));
ALTER TABLE online.orders DROP CONSTRAINT IF EXISTS orders_external_check;
ALTER TABLE online.orders ADD CONSTRAINT orders_external_check CHECK (channel = 'ARKE' OR external_id IS NOT NULL);
ALTER TABLE online.orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE online.orders ADD CONSTRAINT orders_status_check
  CHECK (status IN ('AWAITING_PAYMENT', 'NEW', 'ACCEPTED', 'READY', 'PICKED_UP', 'OUT_FOR_DELIVERY', 'DELIVERED',
                    'REJECTED', 'EXPIRED', 'CANCELLED'));
CREATE UNIQUE INDEX IF NOT EXISTS online_orders_external_key ON online.orders (channel, external_id) WHERE external_id IS NOT NULL;
COMMENT ON COLUMN online.orders.channel IS 'ARKE (our page, paid through online.payments) / UBER_EATS (Uber collects; external_id = Uber''s order id)';
COMMENT ON COLUMN online.orders.status IS 'CANCELLED = the platform (Uber) cancelled it; Arke''s own refusals are REJECTED';

CREATE TABLE IF NOT EXISTS online.uber_stores (
  uber_store_id  TEXT PRIMARY KEY CHECK (length(uber_store_id) BETWEEN 8 AND 64),
  org_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  name           TEXT CHECK (length(name) <= 80),     -- the store's name on Uber, as a reminder
  created_by     UUID,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS online_uber_stores_org_idx ON online.uber_stores (org_id);
COMMENT ON TABLE online.uber_stores IS 'Uber Eats: which of our stores an Uber store id is (sys admin, Cael). Webhooks for other ids are not taken in.';

CREATE TABLE IF NOT EXISTS online.uber_events (
  event_id       TEXT PRIMARY KEY,
  event_type     TEXT NOT NULL,
  resource_id    TEXT,          -- the order id for orders.* events
  uber_store_id  TEXT,
  org_id         UUID,
  body           JSONB NOT NULL,
  order_body     JSONB,         -- the order as Uber's API answered it (what we took in)
  received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at   TIMESTAMPTZ,
  result         TEXT           -- taken / duplicate_order / unknown_store / cancelled / ignored / fetch_failed:<http> / ...
);
CREATE INDEX IF NOT EXISTS online_uber_events_received_idx ON online.uber_events (received_at DESC);
COMMENT ON TABLE online.uber_events IS 'Uber Eats webhooks as received (deduplicated by event_id) and what ubereats-webhook did with each.';

-- Uber's access token (30 days), shared by every Edge Function instance so we don't ask Uber
-- for a new one each time. Only service_role reads it.
CREATE TABLE IF NOT EXISTS online.uber_tokens (
  env           TEXT PRIMARY KEY CHECK (env IN ('sandbox', 'production')),
  access_token  TEXT NOT NULL,
  scope         TEXT,
  expires_at    TIMESTAMPTZ NOT NULL,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE online.uber_tokens IS 'Uber Eats API access token per environment (client credentials, 30 days), cached for the Edge Functions.';

ALTER TABLE online.uber_stores ENABLE ROW LEVEL SECURITY;
ALTER TABLE online.uber_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE online.uber_tokens ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON online.uber_stores, online.uber_events, online.uber_tokens FROM public, anon, authenticated;

-- ---------------------------------------------------------------- order_json: channel, Uber's code

CREATE OR REPLACE FUNCTION online.order_json(o online.orders, p_for_store BOOLEAN) RETURNS JSONB
LANGUAGE sql STABLE SET search_path = public, online AS $$
  SELECT jsonb_build_object(
    'id', o.id, 'token', o.public_token, 'status', o.status, 'fulfilment', o.fulfilment, 'channel', o.channel,
    'pickup_code', CASE WHEN o.channel = 'UBER_EATS' THEN o.external_code
                        WHEN o.pickup_no IS NOT NULL THEN (CASE WHEN o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END) || o.pickup_no END,
    'customer_name', o.customer_name, 'note', o.note, 'items', o.items,
    'subtotal_cents', o.subtotal_cents, 'tax_cents', o.tax_cents, 'tip_cents', o.tip_cents,
    'delivery_fee_cents', o.delivery_fee_cents, 'delivery_address', o.delivery_address, 'delivery_zone', o.delivery_zone,
    'total_cents', o.subtotal_cents + o.delivery_fee_cents + o.tax_cents + o.tip_cents,
    'created_at', o.created_at, 'paid_at', o.paid_at, 'accepted_at', o.accepted_at, 'ready_by', o.ready_by,
    'ready_at', o.ready_at, 'picked_up_at', o.picked_up_at, 'out_at', o.out_at, 'delivered_at', o.delivered_at,
    'rejected_at', o.rejected_at, 'reject_reason', o.reject_reason,
    'cancelled_at', o.cancelled_at, 'cancel_reason', o.cancel_reason)
  || CASE WHEN p_for_store THEN jsonb_build_object('customer_phone', o.customer_phone, 'handled_by', o.handled_by,
       'out_by', o.out_by, 'out_by_sn', o.out_by_sn, 'delivery_attempts', o.delivery_attempts, 'last_attempt_note', o.last_attempt_note,
       'external_id', o.external_id, 'external_type', o.external_type)
     ELSE '{}'::jsonb END
$$;
REVOKE ALL ON FUNCTION online.order_json(online.orders, BOOLEAN) FROM public, anon, authenticated;

-- ---------------------------------------------------------------- Arke's customer page: Arke orders only

CREATE OR REPLACE FUNCTION public.online_order_status(p_token TEXT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_o online.orders%ROWTYPE; v_s online.stores%ROWTYPE;
BEGIN
  UPDATE online.orders SET status = 'EXPIRED'
   WHERE public_token = p_token AND status = 'AWAITING_PAYMENT' AND created_at < now() - interval '30 minutes'
     AND NOT EXISTS (SELECT 1 FROM online.payments p WHERE p.order_id = orders.id AND p.status = 'OPEN' AND p.created_at > now() - interval '15 minutes');
  SELECT * INTO v_o FROM online.orders WHERE public_token = p_token AND channel = 'ARKE';
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

-- ---------------------------------------------------------------- the store's terminal

CREATE OR REPLACE FUNCTION public.online_store_orders() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT coalesce(jsonb_agg(online.order_json(o, true) ORDER BY o.paid_at DESC), '[]'::jsonb)
    FROM online.orders o
   WHERE o.org_id = dine_device_org()
     AND (o.status IN ('NEW', 'ACCEPTED', 'READY', 'OUT_FOR_DELIVERY')
          OR o.status IN ('PICKED_UP', 'DELIVERED', 'REJECTED', 'CANCELLED') AND o.paid_at > now() - interval '12 hours')
$$;

-- Unchanged for Arke; Uber orders are moved on through Uber (step 3), never here.
CREATE OR REPLACE FUNCTION public.online_store_step(p_id UUID, p_step TEXT, p_minutes INT, p_reason TEXT, p_staff_name TEXT, p_device_sn TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_org UUID := dine_device_org(); v_o online.orders%ROWTYPE; v_staff TEXT := nullif(left(trim(coalesce(p_staff_name, '')), 40), '');
        v_sn TEXT := nullif(trim(coalesce(p_device_sn, '')), ''); v_delivery BOOLEAN;
BEGIN
  SELECT * INTO v_o FROM online.orders WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_o.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'order_not_found'); END IF;
  IF v_o.channel <> 'ARKE' THEN RETURN jsonb_build_object('success', false, 'message', 'order_on_platform'); END IF;
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

-- ---------------------------------------------------------------- ubereats-webhook (service_role)

-- A webhook as received. todo = still to be handled: first time, or an earlier try that couldn't
-- reach Uber's API (we answered 5xx, so Uber retries). A retry of a handled event: todo = false.
CREATE OR REPLACE FUNCTION public.online_uber_event_in(p_event_id TEXT, p_type TEXT, p_resource_id TEXT, p_store_id TEXT, p_body JSONB)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_org UUID; v_n INT; v_new BOOLEAN;
BEGIN
  IF coalesce(p_event_id, '') = '' OR coalesce(p_type, '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_event'); END IF;
  SELECT org_id INTO v_org FROM online.uber_stores WHERE uber_store_id = p_store_id;
  INSERT INTO online.uber_events (event_id, event_type, resource_id, uber_store_id, org_id, body)
  VALUES (left(p_event_id, 100), left(p_type, 60), left(p_resource_id, 100), left(p_store_id, 100), v_org, coalesce(p_body, '{}'::jsonb))
  ON CONFLICT (event_id) DO NOTHING;
  GET DIAGNOSTICS v_n = ROW_COUNT;
  v_new := v_n > 0;
  IF v_new AND v_org IS NULL AND p_type LIKE 'orders.%' THEN
    UPDATE online.uber_events SET processed_at = now(), result = 'unknown_store' WHERE event_id = p_event_id;
  END IF;
  RETURN jsonb_build_object('success', true, 'new', v_new, 'org_id', v_org,
    'todo', EXISTS (SELECT 1 FROM online.uber_events WHERE event_id = p_event_id
                     AND (processed_at IS NULL OR result LIKE 'fetch_failed%')));
END $$;

CREATE OR REPLACE FUNCTION public.online_uber_event_done(p_event_id TEXT, p_result TEXT)
RETURNS VOID LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
  UPDATE online.uber_events SET processed_at = now(), result = left(p_result, 200) WHERE event_id = p_event_id;
$$;

-- A new Uber order, as ubereats-webhook read it from Uber (already in our shape):
-- {id, display_id, type, customer_name, customer_phone, note, placed_at,
--  items: [{name, options[], quantity, unit_price_cents, note}], subtotal_cents, tax_cents}.
-- Taken in as NEW once per Uber order; the store must be linked to the event's Uber store.
CREATE OR REPLACE FUNCTION public.online_uber_order_in(p_event_id TEXT, p_order JSONB, p_raw JSONB)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_e online.uber_events%ROWTYPE; v_id UUID; v_type TEXT := upper(coalesce(p_order->>'type', ''));
        v_placed TIMESTAMPTZ; v_items JSONB;
BEGIN
  SELECT * INTO v_e FROM online.uber_events WHERE event_id = p_event_id;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'no_event'); END IF;
  IF v_e.org_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_store'); END IF;
  UPDATE online.uber_events SET order_body = p_raw WHERE event_id = p_event_id;
  IF coalesce(p_order->>'id', '') = '' OR p_order->>'id' <> v_e.resource_id THEN
    PERFORM online_uber_event_done(p_event_id, 'order_mismatch');
    RETURN jsonb_build_object('success', false, 'message', 'order_mismatch');
  END IF;
  IF jsonb_typeof(p_order->'items') <> 'array' OR jsonb_array_length(p_order->'items') = 0 THEN
    PERFORM online_uber_event_done(p_event_id, 'no_items');
    RETURN jsonb_build_object('success', false, 'message', 'no_items');
  END IF;
  BEGIN v_placed := (p_order->>'placed_at')::timestamptz; EXCEPTION WHEN others THEN v_placed := NULL; END;
  SELECT jsonb_agg(jsonb_build_object(
           'product_id', NULL, 'name', left(coalesce(nullif(trim(i->>'name'), ''), '?'), 120),
           'options', coalesce((SELECT jsonb_agg(left(x, 120)) FROM jsonb_array_elements_text(coalesce(i->'options', '[]'::jsonb)) x), '[]'::jsonb),
           'quantity', greatest(1, coalesce((i->>'quantity')::INT, 1)),
           'unit_price_cents', coalesce((i->>'unit_price_cents')::INT, 0), 'tax_rate_bps', 0,
           'note', nullif(left(trim(coalesce(i->>'note', '')), 300), '')))
    INTO v_items FROM jsonb_array_elements(p_order->'items') i;

  INSERT INTO online.orders (org_id, client_ref, channel, external_id, external_code, external_type, fulfilment, status,
                             customer_name, customer_phone, note, items, subtotal_cents, tax_cents, tip_cents, paid_at)
  VALUES (v_e.org_id, 'uber:' || (p_order->>'id'), 'UBER_EATS', p_order->>'id',
          left(coalesce(nullif(trim(p_order->>'display_id'), ''), left(p_order->>'id', 5)), 20), left(v_type, 40),
          CASE WHEN v_type IN ('PICK_UP', 'DINE_IN') THEN 'PICKUP' ELSE 'THIRD_PARTY' END, 'NEW',
          left(coalesce(nullif(trim(p_order->>'customer_name'), ''), 'Uber Eats'), 60),
          left(coalesce(nullif(trim(p_order->>'customer_phone'), ''), '-'), 40),
          nullif(left(trim(coalesce(p_order->>'note', '')), 500), ''), v_items,
          greatest(0, coalesce((p_order->>'subtotal_cents')::INT, 0)), greatest(0, coalesce((p_order->>'tax_cents')::INT, 0)), 0,
          coalesce(v_placed, now()))
  ON CONFLICT (channel, external_id) WHERE external_id IS NOT NULL DO NOTHING
  RETURNING id INTO v_id;
  PERFORM online_uber_event_done(p_event_id, CASE WHEN v_id IS NULL THEN 'duplicate_order' ELSE 'taken' END);
  RETURN jsonb_build_object('success', true, 'order_id', v_id, 'duplicate', v_id IS NULL);
END $$;

-- Uber cancelled an order (orders.cancel / orders.failure): CANCELLED, unless it is already over.
CREATE OR REPLACE FUNCTION public.online_uber_order_cancel(p_event_id TEXT, p_order_id TEXT, p_reason TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_id UUID;
BEGIN
  UPDATE online.orders SET status = 'CANCELLED', cancelled_at = now(), cancel_reason = nullif(left(trim(coalesce(p_reason, '')), 200), '')
   WHERE channel = 'UBER_EATS' AND external_id = p_order_id AND status IN ('NEW', 'ACCEPTED', 'READY')
  RETURNING id INTO v_id;
  PERFORM online_uber_event_done(p_event_id, CASE WHEN v_id IS NULL THEN 'cancel_nothing_open' ELSE 'cancelled' END);
  RETURN jsonb_build_object('success', true, 'order_id', v_id);
END $$;

CREATE OR REPLACE FUNCTION public.online_uber_token(p_env TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT jsonb_build_object('access_token', access_token, 'expires_at', expires_at)
    FROM online.uber_tokens WHERE env = p_env AND expires_at > now() + interval '1 day'
$$;

CREATE OR REPLACE FUNCTION public.online_uber_token_save(p_env TEXT, p_token TEXT, p_scope TEXT, p_expires_in INT)
RETURNS VOID LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
  INSERT INTO online.uber_tokens (env, access_token, scope, expires_at)
  VALUES (p_env, p_token, p_scope, now() + make_interval(secs => greatest(60, coalesce(p_expires_in, 3600)) - 60))
  ON CONFLICT (env) DO UPDATE SET access_token = excluded.access_token, scope = excluded.scope,
                                  expires_at = excluded.expires_at, updated_at = now();
$$;

REVOKE ALL ON FUNCTION public.online_uber_token(TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_uber_token_save(TEXT, TEXT, TEXT, INT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_uber_token(TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_uber_token_save(TEXT, TEXT, TEXT, INT) TO service_role;
REVOKE ALL ON FUNCTION public.online_uber_event_in(TEXT, TEXT, TEXT, TEXT, JSONB) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_uber_event_done(TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_uber_order_in(TEXT, JSONB, JSONB) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_uber_order_cancel(TEXT, TEXT, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_uber_event_in(TEXT, TEXT, TEXT, TEXT, JSONB) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_uber_event_done(TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_uber_order_in(TEXT, JSONB, JSONB) TO service_role;
GRANT EXECUTE ON FUNCTION public.online_uber_order_cancel(TEXT, TEXT, TEXT) TO service_role;

-- ---------------------------------------------------------------- Cael (sys admin links Uber stores)

CREATE OR REPLACE FUNCTION public.cmp_uber_stores(p_org UUID) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, online AS $$
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501'; END IF;
  RETURN jsonb_build_object(
    'stores', coalesce((SELECT jsonb_agg(jsonb_build_object('uber_store_id', s.uber_store_id, 'name', s.name, 'created_at', s.created_at)
                                         ORDER BY s.created_at) FROM online.uber_stores s WHERE s.org_id = p_org), '[]'::jsonb),
    'last_event', (SELECT jsonb_build_object('type', e.event_type, 'received_at', e.received_at, 'result', e.result)
                     FROM online.uber_events e WHERE e.org_id = p_org ORDER BY e.received_at DESC LIMIT 1));
END $$;

CREATE OR REPLACE FUNCTION public.cmp_uber_store_link(p_org UUID, p_uber_store_id TEXT, p_name TEXT, p_link BOOLEAN)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_id TEXT := lower(trim(coalesce(p_uber_store_id, ''))); v_other UUID;
BEGIN
  IF NOT is_sys_admin() THEN RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501'; END IF;
  IF NOT coalesce(p_link, true) THEN
    DELETE FROM online.uber_stores WHERE uber_store_id = v_id AND org_id = p_org;
    RETURN jsonb_build_object('success', true, 'uber', cmp_uber_stores(p_org));
  END IF;
  IF v_id !~ '^[0-9a-z-]{8,64}$' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_store_id'); END IF;
  SELECT org_id INTO v_other FROM online.uber_stores WHERE uber_store_id = v_id;
  IF v_other IS NOT NULL AND v_other <> p_org THEN RETURN jsonb_build_object('success', false, 'message', 'linked_elsewhere'); END IF;
  INSERT INTO online.uber_stores (uber_store_id, org_id, name, created_by)
  VALUES (v_id, p_org, nullif(left(trim(coalesce(p_name, '')), 80), ''), auth.uid())
  ON CONFLICT (uber_store_id) DO UPDATE SET name = excluded.name;
  RETURN jsonb_build_object('success', true, 'uber', cmp_uber_stores(p_org));
END $$;

REVOKE ALL ON FUNCTION public.cmp_uber_stores(UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_uber_store_link(UUID, TEXT, TEXT, BOOLEAN) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_uber_stores(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_uber_store_link(UUID, TEXT, TEXT, BOOLEAN) TO authenticated;

COMMIT;
