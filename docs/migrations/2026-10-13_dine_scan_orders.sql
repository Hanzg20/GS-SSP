-- 2026-10-13: Ourea Scan -- customers order from their phone by scanning the
-- QR code on the table (web/ourea-scan). docs/database_design.md §5.
--
-- * Each table has a secret qr_token (128 bits); the QR is <site>/?t=<token>.
--   The token is the only thing the customer has: no store or table id in the
--   URL, nothing to guess. Rotating it (dine_rotate_table_qr) kills old codes.
-- * A customer order goes into dine.scan_orders as PENDING -- never straight to
--   the kitchen. Staff accept it on Ourea / Iris (it becomes the table's next
--   round and prints in the kitchen) or reject it (prank, wrong table).
-- * Prices, options and tax are the server's, from products; the customer
--   only sends product ids, option ids, quantities and notes.
-- * Customer functions (scan_*) are callable by anon; they see one table's
--   store menu and their own orders' status, nothing else.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE dine.tables ADD COLUMN IF NOT EXISTS qr_token TEXT;
UPDATE dine.tables SET qr_token = encode(gen_random_bytes(16), 'hex') WHERE qr_token IS NULL;
ALTER TABLE dine.tables ALTER COLUMN qr_token SET DEFAULT encode(gen_random_bytes(16), 'hex');
ALTER TABLE dine.tables ALTER COLUMN qr_token SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS dine_tables_qr_token_key ON dine.tables (qr_token);

CREATE TABLE IF NOT EXISTS dine.scan_orders (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  table_id       UUID NOT NULL REFERENCES dine.tables(id) ON DELETE CASCADE,
  status         TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
  items          JSONB NOT NULL,          -- server-priced lines, dine_send_items shape
  note           TEXT,
  subtotal_cents INTEGER NOT NULL,
  tax_cents      INTEGER NOT NULL,
  client_ref     TEXT NOT NULL,           -- the phone's id for this submit: a double tap is one order
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at     TIMESTAMPTZ,
  decided_by     TEXT,
  reject_reason  TEXT,
  check_id       UUID REFERENCES dine.checks(id) ON DELETE SET NULL,
  round          INTEGER,
  CONSTRAINT scan_orders_client_ref_key UNIQUE (table_id, client_ref)
);
CREATE INDEX IF NOT EXISTS scan_orders_org_status_idx ON dine.scan_orders (org_id, status);
COMMENT ON TABLE dine.scan_orders IS 'Customer QR orders (Ourea Scan) waiting for staff: PENDING -> ACCEPTED (a round on the check) / REJECTED';
ALTER TABLE dine.scan_orders ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON dine.scan_orders FROM public, anon, authenticated;

-- ---------------------------------------------------------------- customer (anon)

-- The table, the store and its menu for a QR token; null when the token is unknown.
CREATE OR REPLACE FUNCTION public.scan_menu(p_token TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object(
    'table', jsonb_build_object('name', t.name, 'area', t.area),
    'store', jsonb_build_object('name', o.name),
    'products', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'id', p.id, 'name', p.name, 'price_cents', p.price_cents,
               'category', p.attributes->>'category',
               'description', p.attributes->>'description',
               'tax_rate_bps', coalesce((p.attributes->>'tax_rate_bps')::INT, 1300),
               'modifier_groups', coalesce(p.attributes->'modifier_groups', '[]'::jsonb),
               'sold_out', coalesce((p.attributes->>'stock_qty')::INT, 1) <= 0)
             ORDER BY p.attributes->>'category' NULLS LAST, p.name)
        FROM products p
       WHERE p.org_id = t.org_id AND p.is_active AND p.vertical_type = 'RETAIL'), '[]'::jsonb))
    FROM dine.tables t JOIN organizations o ON o.id = t.org_id
   WHERE t.qr_token = p_token AND t.is_active
$$;

-- Place an order for staff to accept. p_items: [{product_id, quantity, option_ids[], note}].
CREATE OR REPLACE FUNCTION public.scan_submit_order(p_token TEXT, p_items JSONB, p_note TEXT, p_client_ref TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE
  v_table dine.tables%ROWTYPE; v_item JSONB; v_p products%ROWTYPE; v_groups JSONB; v_g JSONB;
  v_opts JSONB; v_opt_ids JSONB; v_qty INT; v_unit INT; v_bps INT; v_picked INT; v_names JSONB;
  v_lines JSONB := '[]'::jsonb; v_sub INT := 0; v_tax INT; v_id UUID; v_existing UUID;
BEGIN
  SELECT * INTO v_table FROM dine.tables WHERE qr_token = p_token AND is_active;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_table'); END IF;
  IF coalesce(trim(p_client_ref), '') = '' OR length(p_client_ref) > 64 THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_request');
  END IF;
  SELECT id INTO v_existing FROM dine.scan_orders WHERE table_id = v_table.id AND client_ref = p_client_ref;
  IF FOUND THEN RETURN jsonb_build_object('success', true, 'id', v_existing, 'duplicate', true); END IF;

  -- Abuse limits: a table can't flood the queue.
  IF (SELECT count(*) FROM dine.scan_orders WHERE table_id = v_table.id AND status = 'PENDING') >= 3
     OR (SELECT count(*) FROM dine.scan_orders WHERE table_id = v_table.id AND created_at > now() - interval '10 minutes') >= 8 THEN
    RETURN jsonb_build_object('success', false, 'message', 'too_many_orders');
  END IF;
  IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 OR jsonb_array_length(p_items) > 30 THEN
    RETURN jsonb_build_object('success', false, 'message', 'items_required');
  END IF;

  FOR v_item IN SELECT * FROM jsonb_array_elements(p_items) LOOP
    v_qty := coalesce((v_item->>'quantity')::INT, 0);
    IF v_qty < 1 OR v_qty > 20 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_quantity'); END IF;
    SELECT * INTO v_p FROM products
     WHERE id = nullif(v_item->>'product_id', '')::UUID AND org_id = v_table.org_id AND is_active AND vertical_type = 'RETAIL';
    IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'item_unavailable'); END IF;
    IF coalesce((v_p.attributes->>'stock_qty')::INT, 1) <= 0 THEN
      RETURN jsonb_build_object('success', false, 'message', 'sold_out', 'product', v_p.name);
    END IF;

    v_unit := v_p.price_cents;
    v_names := '[]'::jsonb;
    v_opt_ids := coalesce(v_item->'option_ids', '[]'::jsonb);
    v_groups := coalesce(v_p.attributes->'modifier_groups', '[]'::jsonb);
    -- Every chosen option must belong to the dish; each group's rules hold.
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
      'product_id', v_p.id, 'name', v_p.name, 'options', v_names, 'quantity', v_qty,
      'unit_price_cents', v_unit, 'tax_rate_bps', v_bps,
      'note', nullif(left(trim(coalesce(v_item->>'note', '')), 120), ''));
  END LOOP;

  SELECT coalesce(sum(round(t * b / 10000.0)), 0)::INT INTO v_tax FROM (
    SELECT (l->>'tax_rate_bps')::INT b, sum((l->>'quantity')::INT * (l->>'unit_price_cents')::INT) t
      FROM jsonb_array_elements(v_lines) l GROUP BY 1) r;

  INSERT INTO dine.scan_orders (org_id, table_id, items, note, subtotal_cents, tax_cents, client_ref)
  VALUES (v_table.org_id, v_table.id, v_lines, nullif(left(trim(coalesce(p_note, '')), 200), ''), v_sub, v_tax, p_client_ref)
  RETURNING id INTO v_id;
  RETURN jsonb_build_object('success', true, 'id', v_id, 'subtotal_cents', v_sub, 'tax_cents', v_tax);
END $$;

-- The customer's orders at this table today, newest first (their status page).
CREATE OR REPLACE FUNCTION public.scan_orders(p_token TEXT, p_ids UUID[]) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
           'id', s.id, 'status', s.status, 'reject_reason', s.reject_reason,
           'items', s.items, 'subtotal_cents', s.subtotal_cents, 'tax_cents', s.tax_cents,
           'created_at', s.created_at) ORDER BY s.created_at DESC), '[]'::jsonb)
    FROM dine.scan_orders s JOIN dine.tables t ON t.id = s.table_id
   WHERE t.qr_token = p_token AND s.id = ANY (p_ids)
$$;

-- ---------------------------------------------------------------- staff (devices)

-- Pending customer orders of this store, oldest first.
CREATE OR REPLACE FUNCTION public.dine_scan_pending() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT coalesce(jsonb_agg(to_jsonb(s) - 'client_ref' || jsonb_build_object('table_name', t.name) ORDER BY s.created_at), '[]'::jsonb)
    FROM dine.scan_orders s JOIN dine.tables t ON t.id = s.table_id
   WHERE s.org_id = dine_device_org() AND s.status = 'PENDING'
$$;

-- Accept: the table's check (opened if needed) gets the order as its next round.
-- Returns what the kitchen ticket needs.
CREATE OR REPLACE FUNCTION public.dine_accept_scan(p_id UUID, p_staff_name TEXT, p_device_sn TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org(); v_s dine.scan_orders%ROWTYPE; v_open JSONB; v_send JSONB; v_check dine.checks%ROWTYPE;
BEGIN
  SELECT * INTO v_s FROM dine.scan_orders WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_s.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'order_not_found'); END IF;
  IF v_s.status <> 'PENDING' THEN RETURN jsonb_build_object('success', false, 'message', 'order_' || lower(v_s.status)); END IF;
  v_open := dine_open_check(v_s.table_id, 1, NULL, p_staff_name, p_device_sn);
  IF NOT (v_open->>'success')::BOOLEAN THEN RETURN v_open; END IF;
  v_send := dine_send_items((v_open->>'id')::UUID, v_s.items, coalesce(nullif(trim(p_staff_name), ''), 'QR') || ' (QR)');
  IF NOT (v_send->>'success')::BOOLEAN THEN RETURN v_send; END IF;
  UPDATE dine.scan_orders SET status = 'ACCEPTED', decided_at = now(), decided_by = nullif(trim(p_staff_name), ''),
         check_id = (v_open->>'id')::UUID, round = (v_send->>'round')::INT
   WHERE id = p_id;
  SELECT * INTO v_check FROM dine.checks WHERE id = (v_open->>'id')::UUID;
  RETURN jsonb_build_object('success', true, 'check_id', v_check.id, 'check_no', v_check.check_no,
    'guests', v_check.guests, 'round', (v_send->>'round')::INT,
    'table_name', (SELECT name FROM dine.tables WHERE id = v_s.table_id),
    'items', v_s.items, 'note', v_s.note);
END $$;

CREATE OR REPLACE FUNCTION public.dine_reject_scan(p_id UUID, p_staff_name TEXT, p_reason TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org(); v_s dine.scan_orders%ROWTYPE;
BEGIN
  SELECT * INTO v_s FROM dine.scan_orders WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_s.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'order_not_found'); END IF;
  IF v_s.status <> 'PENDING' THEN RETURN jsonb_build_object('success', false, 'message', 'order_' || lower(v_s.status)); END IF;
  UPDATE dine.scan_orders SET status = 'REJECTED', decided_at = now(), decided_by = nullif(trim(p_staff_name), ''),
         reject_reason = nullif(left(trim(coalesce(p_reason, '')), 120), '')
   WHERE id = p_id;
  RETURN jsonb_build_object('success', true);
END $$;

-- A new QR for a table (old printed codes stop working).
CREATE OR REPLACE FUNCTION public.dine_rotate_table_qr(p_table_id UUID) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_token TEXT;
BEGIN
  UPDATE dine.tables SET qr_token = encode(gen_random_bytes(16), 'hex')
   WHERE id = p_table_id AND org_id = dine_device_org() RETURNING qr_token INTO v_token;
  IF v_token IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'table_not_found'); END IF;
  RETURN jsonb_build_object('success', true, 'qr_token', v_token);
END $$;

-- The floor also carries the pending customer orders.
CREATE OR REPLACE FUNCTION public.dine_floor() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object(
    'tables', dine_list_tables(),
    'checks', coalesce((
      SELECT jsonb_agg(to_jsonb(c) || jsonb_build_object('total_cents', dine_check_total(c.id)))
        FROM dine.checks c
       WHERE c.org_id = dine_device_org() AND c.status IN ('OPEN', 'BILLED')), '[]'::jsonb),
    'scan_pending', dine_scan_pending())
$$;

REVOKE ALL ON FUNCTION public.scan_menu(TEXT) FROM public;
REVOKE ALL ON FUNCTION public.scan_submit_order(TEXT, JSONB, TEXT, TEXT) FROM public;
REVOKE ALL ON FUNCTION public.scan_orders(TEXT, UUID[]) FROM public;
GRANT EXECUTE ON FUNCTION public.scan_menu(TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_submit_order(TEXT, JSONB, TEXT, TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_orders(TEXT, UUID[]) TO anon, authenticated;

REVOKE ALL ON FUNCTION public.dine_scan_pending() FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_accept_scan(UUID, TEXT, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_reject_scan(UUID, TEXT, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_rotate_table_qr(UUID) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.dine_scan_pending() TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_accept_scan(UUID, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_reject_scan(UUID, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_rotate_table_qr(UUID) TO authenticated;

COMMIT;
