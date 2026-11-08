-- 2026-11-08: Ourea Scan orders as a TABLE, not as one phone (owner: "点餐模式和布局模仿 Chowbus").
-- docs/database_design.md §5, docs/system_architecture.md §17.6.
--
-- * One shared cart per table (dine.scan_cart_items): everyone at the table adds
--   to it from their own phone, each line remembers who added it (a per-phone
--   guest id and an optional name), anyone can change or remove a line, and one
--   person sends the whole cart. dine.tables.scan_cart_rev goes up on every
--   change, so a phone sending a cart it hasn't seen yet is told to look again
--   (cart_changed) instead of sending something else than what it showed.
--   Lines left untouched for 6 hours are dropped.
-- * The cart is sent through the same server pricing as before
--   (scan_submit_order); the order keeps who sent it (guest_name) and each
--   line keeps who added it (items[].guest).
-- * Each store chooses whether QR orders still wait for staff (default) or go
--   straight to the kitchen (dine.settings.scan_auto_kitchen). "Straight" marks
--   the order approved; the first Ourea / Iris showing the floor accepts it and
--   prints the kitchen ticket (the Cael-approval path, DineViewModel
--   .acceptApproved) -- so the customer still only sees "in the kitchen" once a
--   terminal really took it.
-- * scan_table_orders: the table's own picture for every phone at it -- the
--   QR orders of the open check (and any still waiting / just refused), plus
--   every line on the check, staff rounds included.

BEGIN;

CREATE TABLE IF NOT EXISTS dine.settings (
  org_id             UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
  scan_auto_kitchen  BOOLEAN NOT NULL DEFAULT false,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by         TEXT
);
COMMENT ON TABLE dine.settings IS 'Per-store dine-in settings; scan_auto_kitchen = QR orders go to the kitchen without a staff tap';
ALTER TABLE dine.settings ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON dine.settings FROM public, anon, authenticated;

ALTER TABLE dine.tables ADD COLUMN IF NOT EXISTS scan_cart_rev BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dine.scan_orders ADD COLUMN IF NOT EXISTS guest_name TEXT;

CREATE TABLE IF NOT EXISTS dine.scan_cart_items (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  table_id    UUID NOT NULL REFERENCES dine.tables(id) ON DELETE CASCADE,
  guest_id    TEXT NOT NULL,                 -- the phone that added it (random, kept on the phone)
  guest_name  TEXT,                          -- optional name typed at the door
  product_id  UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  option_ids  JSONB NOT NULL DEFAULT '[]'::jsonb,
  note        TEXT,
  quantity    INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 20),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS scan_cart_items_table_idx ON dine.scan_cart_items (table_id, created_at);
COMMENT ON TABLE dine.scan_cart_items IS 'Ourea Scan shared table cart: lines added from any phone at the table, sent together; untouched 6 h = dropped';
ALTER TABLE dine.scan_cart_items ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON dine.scan_cart_items FROM public, anon, authenticated;

-- ---------------------------------------------------------------- customer (anon)

-- The table's cart: revision and lines (display only; the server prices at send).
CREATE OR REPLACE FUNCTION public.scan_cart(p_token TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object('rev', t.scan_cart_rev,
    'lines', coalesce((SELECT jsonb_agg(jsonb_build_object('id', i.id, 'guest_id', i.guest_id, 'guest_name', i.guest_name,
                                                           'product_id', i.product_id, 'option_ids', i.option_ids,
                                                           'note', i.note, 'quantity', i.quantity) ORDER BY i.created_at)
                         FROM dine.scan_cart_items i
                        WHERE i.table_id = t.id AND i.updated_at > now() - interval '6 hours'), '[]'::jsonb))
    FROM dine.tables t WHERE t.qr_token = p_token AND t.is_active
$$;

-- Add to the table's cart (same dish + options + note from the same phone = more of that line).
CREATE OR REPLACE FUNCTION public.scan_cart_add(p_token TEXT, p_guest_id TEXT, p_guest_name TEXT, p_product_id UUID,
                                                p_option_ids JSONB, p_note TEXT, p_quantity INT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_t dine.tables%ROWTYPE; v_p products%ROWTYPE; v_opts JSONB; v_note TEXT; v_line UUID; v_all JSONB;
BEGIN
  SELECT * INTO v_t FROM dine.tables WHERE qr_token = p_token AND is_active FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_table'); END IF;
  IF coalesce(trim(p_guest_id), '') = '' OR length(p_guest_id) > 64 OR p_quantity IS NULL OR p_quantity < 1 OR p_quantity > 20 THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_request');
  END IF;
  SELECT * INTO v_p FROM products WHERE id = p_product_id AND org_id = v_t.org_id AND is_active AND vertical_type = 'RETAIL';
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'item_unavailable'); END IF;
  IF coalesce((v_p.attributes->>'stock_qty')::INT, 1) <= 0 THEN
    RETURN jsonb_build_object('success', false, 'message', 'sold_out', 'product', v_p.name);
  END IF;
  -- Options must be the dish's own (the group rules are checked again when the cart is sent).
  v_opts := coalesce((SELECT jsonb_agg(o ORDER BY o) FROM jsonb_array_elements_text(coalesce(p_option_ids, '[]'::jsonb)) o), '[]'::jsonb);
  SELECT coalesce(jsonb_agg(x->>'id'), '[]'::jsonb) INTO v_all
    FROM jsonb_array_elements(coalesce(v_p.attributes->'modifier_groups', '[]'::jsonb)) g, jsonb_array_elements(g->'options') x;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements_text(v_opts) o WHERE NOT v_all ? o) THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_option', 'product', v_p.name);
  END IF;
  v_note := nullif(left(trim(coalesce(p_note, '')), 120), '');

  DELETE FROM dine.scan_cart_items WHERE table_id = v_t.id AND updated_at <= now() - interval '6 hours';
  SELECT id INTO v_line FROM dine.scan_cart_items
   WHERE table_id = v_t.id AND guest_id = p_guest_id AND product_id = p_product_id AND option_ids = v_opts
     AND note IS NOT DISTINCT FROM v_note;
  IF FOUND THEN
    UPDATE dine.scan_cart_items SET quantity = least(quantity + p_quantity, 20), updated_at = now(),
           guest_name = coalesce(nullif(trim(left(p_guest_name, 30)), ''), guest_name)
     WHERE id = v_line;
  ELSE
    IF (SELECT count(*) FROM dine.scan_cart_items WHERE table_id = v_t.id) >= 40 THEN
      RETURN jsonb_build_object('success', false, 'message', 'cart_full');
    END IF;
    INSERT INTO dine.scan_cart_items (org_id, table_id, guest_id, guest_name, product_id, option_ids, note, quantity)
    VALUES (v_t.org_id, v_t.id, p_guest_id, nullif(trim(left(p_guest_name, 30)), ''), p_product_id, v_opts, v_note, p_quantity);
  END IF;
  UPDATE dine.tables SET scan_cart_rev = scan_cart_rev + 1 WHERE id = v_t.id;
  RETURN jsonb_build_object('success', true, 'cart', scan_cart(p_token));
END $$;

-- Change a line's quantity (0 = remove). Anyone at the table may: it's the table's cart.
CREATE OR REPLACE FUNCTION public.scan_cart_set(p_token TEXT, p_line_id UUID, p_quantity INT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_t dine.tables%ROWTYPE;
BEGIN
  SELECT * INTO v_t FROM dine.tables WHERE qr_token = p_token AND is_active FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_table'); END IF;
  IF p_quantity IS NULL OR p_quantity < 0 OR p_quantity > 20 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_request'); END IF;
  IF p_quantity = 0 THEN
    DELETE FROM dine.scan_cart_items WHERE id = p_line_id AND table_id = v_t.id;
  ELSE
    UPDATE dine.scan_cart_items SET quantity = p_quantity, updated_at = now() WHERE id = p_line_id AND table_id = v_t.id;
  END IF;
  UPDATE dine.tables SET scan_cart_rev = scan_cart_rev + 1 WHERE id = v_t.id;
  RETURN jsonb_build_object('success', true, 'cart', scan_cart(p_token));
END $$;

CREATE OR REPLACE FUNCTION public.scan_cart_clear(p_token TEXT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_t dine.tables%ROWTYPE;
BEGIN
  SELECT * INTO v_t FROM dine.tables WHERE qr_token = p_token AND is_active FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_table'); END IF;
  DELETE FROM dine.scan_cart_items WHERE table_id = v_t.id;
  UPDATE dine.tables SET scan_cart_rev = scan_cart_rev + 1 WHERE id = v_t.id;
  RETURN jsonb_build_object('success', true, 'cart', scan_cart(p_token));
END $$;

-- Send the whole cart as one order. p_rev = the revision the phone showed; a
-- different one = someone changed the cart meanwhile (cart_changed, nothing sent).
CREATE OR REPLACE FUNCTION public.scan_submit_cart(p_token TEXT, p_rev BIGINT, p_note TEXT, p_client_ref TEXT, p_guest_name TEXT)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_t dine.tables%ROWTYPE; v_existing UUID; v_items JSONB; v_guests JSONB; v_ids UUID[]; v_r JSONB; v_auto BOOLEAN;
BEGIN
  SELECT * INTO v_t FROM dine.tables WHERE qr_token = p_token AND is_active FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_table'); END IF;
  -- The same send again (double tap, retry after a lost answer): the order it already made.
  SELECT id INTO v_existing FROM dine.scan_orders WHERE table_id = v_t.id AND client_ref = p_client_ref;
  IF FOUND THEN RETURN jsonb_build_object('success', true, 'id', v_existing, 'duplicate', true); END IF;
  IF p_rev IS DISTINCT FROM v_t.scan_cart_rev THEN
    RETURN jsonb_build_object('success', false, 'message', 'cart_changed', 'cart', scan_cart(p_token));
  END IF;

  SELECT coalesce(jsonb_agg(jsonb_build_object('product_id', i.product_id, 'quantity', i.quantity, 'option_ids', i.option_ids, 'note', i.note)
                            ORDER BY i.created_at), '[]'::jsonb),
         coalesce(jsonb_agg(to_jsonb(coalesce(i.guest_name, '')) ORDER BY i.created_at), '[]'::jsonb),
         array_agg(i.id)
    INTO v_items, v_guests, v_ids
    FROM dine.scan_cart_items i WHERE i.table_id = v_t.id AND i.updated_at > now() - interval '6 hours';
  IF jsonb_array_length(v_items) = 0 THEN RETURN jsonb_build_object('success', false, 'message', 'items_required'); END IF;

  v_r := scan_submit_order(p_token, v_items, p_note, p_client_ref);
  IF NOT coalesce((v_r->>'success')::BOOLEAN, false) THEN RETURN v_r; END IF;

  SELECT coalesce(s.scan_auto_kitchen, false) INTO v_auto FROM dine.settings s WHERE s.org_id = v_t.org_id;
  -- Who sent it, who added each line (same order as the cart), and the store's kitchen choice.
  UPDATE dine.scan_orders o SET
         guest_name = nullif(trim(left(p_guest_name, 30)), ''),
         items = (SELECT jsonb_agg(CASE WHEN g.v #>> '{}' <> '' THEN l.v || jsonb_build_object('guest', g.v #>> '{}') ELSE l.v END ORDER BY l.n)
                    FROM jsonb_array_elements(o.items) WITH ORDINALITY l(v, n)
                    JOIN jsonb_array_elements(v_guests) WITH ORDINALITY g(v, n) USING (n)),
         approved_at = CASE WHEN coalesce(v_auto, false) THEN now() END,
         approved_by = CASE WHEN coalesce(v_auto, false) THEN 'QR' END
   WHERE o.id = (v_r->>'id')::UUID;
  DELETE FROM dine.scan_cart_items WHERE id = ANY (v_ids);
  UPDATE dine.tables SET scan_cart_rev = scan_cart_rev + 1 WHERE id = v_t.id;
  RETURN v_r || jsonb_build_object('auto_kitchen', coalesce(v_auto, false));
END $$;

-- Everything the table has ordered, for every phone at it: the QR orders of the
-- open check (and ones still waiting / refused in the last 2 hours), and the
-- check's lines, staff rounds included (product_id for the "ordered" marks).
CREATE OR REPLACE FUNCTION public.scan_table_orders(p_token TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  WITH t AS (SELECT * FROM dine.tables WHERE qr_token = p_token AND is_active),
  c AS (SELECT c.* FROM dine.checks c JOIN t ON c.table_id = t.id WHERE c.status IN ('OPEN', 'BILLED') ORDER BY c.opened_at DESC LIMIT 1)
  SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM t) THEN NULL ELSE jsonb_build_object(
    'orders', coalesce((
      SELECT jsonb_agg(jsonb_build_object('id', s.id, 'status', s.status, 'reject_reason', s.reject_reason, 'items', s.items,
                                          'subtotal_cents', s.subtotal_cents, 'tax_cents', s.tax_cents, 'created_at', s.created_at,
                                          'guest_name', s.guest_name, 'round', s.round) ORDER BY s.created_at DESC)
        FROM dine.scan_orders s JOIN t ON s.table_id = t.id
       WHERE (s.check_id IS NOT NULL AND s.check_id = (SELECT id FROM c))
          OR (s.status <> 'ACCEPTED' AND s.created_at > now() - interval '2 hours')), '[]'::jsonb),
    'check', (SELECT jsonb_build_object('check_no', c.check_no, 'status', c.status,
        'lines', coalesce((SELECT jsonb_agg(jsonb_build_object('product_id', i.product_id, 'name', i.name, 'options', i.options,
                                                               'quantity', i.quantity, 'round', i.round) ORDER BY i.round, i.sent_at)
                             FROM dine.check_items i WHERE i.check_id = c.id AND i.status <> 'VOID'), '[]'::jsonb))
      FROM c)) END
$$;

-- ---------------------------------------------------------------- staff (devices)

CREATE OR REPLACE FUNCTION public.dine_settings() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object('scan_auto_kitchen', coalesce((SELECT s.scan_auto_kitchen FROM dine.settings s WHERE s.org_id = dine_device_org()), false))
$$;

CREATE OR REPLACE FUNCTION public.dine_set_scan_auto_kitchen(p_on BOOLEAN, p_staff_name TEXT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org();
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'no_store'); END IF;
  INSERT INTO dine.settings (org_id, scan_auto_kitchen, updated_at, updated_by)
  VALUES (v_org, coalesce(p_on, false), now(), nullif(trim(left(p_staff_name, 60)), ''))
  ON CONFLICT (org_id) DO UPDATE SET scan_auto_kitchen = EXCLUDED.scan_auto_kitchen, updated_at = now(), updated_by = EXCLUDED.updated_by;
  RETURN jsonb_build_object('success', true);
END $$;

REVOKE ALL ON FUNCTION public.scan_cart(TEXT) FROM public;
REVOKE ALL ON FUNCTION public.scan_cart_add(TEXT, TEXT, TEXT, UUID, JSONB, TEXT, INT) FROM public;
REVOKE ALL ON FUNCTION public.scan_cart_set(TEXT, UUID, INT) FROM public;
REVOKE ALL ON FUNCTION public.scan_cart_clear(TEXT) FROM public;
REVOKE ALL ON FUNCTION public.scan_submit_cart(TEXT, BIGINT, TEXT, TEXT, TEXT) FROM public;
REVOKE ALL ON FUNCTION public.scan_table_orders(TEXT) FROM public;
GRANT EXECUTE ON FUNCTION public.scan_cart(TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_cart_add(TEXT, TEXT, TEXT, UUID, JSONB, TEXT, INT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_cart_set(TEXT, UUID, INT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_cart_clear(TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_submit_cart(TEXT, BIGINT, TEXT, TEXT, TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_table_orders(TEXT) TO anon, authenticated;

REVOKE ALL ON FUNCTION public.dine_settings() FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_set_scan_auto_kitchen(BOOLEAN, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.dine_settings() TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_set_scan_auto_kitchen(BOOLEAN, TEXT) TO authenticated;

COMMIT;
