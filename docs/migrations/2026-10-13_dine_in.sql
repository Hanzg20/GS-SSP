-- 2026-10-13: dine-in (table service) -- Settings > Business type = Dine-in.
--
-- dining_tables: the floor, managed from Ourea Settings (no CMP screen).
-- dine_checks: one open check per table at a time (OPEN -> BILLED -> PAID, or
--   VOID for a table opened by mistake). check_no counts per store per day.
-- dine_check_items: lines sent to the kitchen, by round (1st order, 2nd = "add"
--   ...). The order being taken lives on the device until it is sent: there is
--   no unsent state in the cloud, so two servers can't print each other's lines.
-- dine_check_payments: what has been paid on a check (split bills: by items or
--   in equal shares, cash and card mixed). The check closes when the payments
--   cover its total.
--
-- Devices read their own store's rows (RLS) and write only through the
-- SECURITY DEFINER functions below, which take the store from device_auth_map.
-- Tax: once per rate, half up (SalesTax.kt), over the non-void lines.

CREATE TABLE IF NOT EXISTS dining_tables (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  area        TEXT,
  seats       INTEGER NOT NULL DEFAULT 4 CHECK (seats BETWEEN 1 AND 50),
  sort        INTEGER NOT NULL DEFAULT 0,
  is_active   BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS dining_tables_org_name_key ON dining_tables (org_id, lower(name)) WHERE is_active;

CREATE TABLE IF NOT EXISTS dine_checks (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  table_id      UUID NOT NULL REFERENCES dining_tables(id),
  check_no      INTEGER NOT NULL,
  business_day  DATE NOT NULL DEFAULT (now() AT TIME ZONE 'America/Toronto')::date,
  status        TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'BILLED', 'PAID', 'VOID')),
  guests        INTEGER NOT NULL DEFAULT 1 CHECK (guests BETWEEN 1 AND 99),
  server_id     UUID,
  server_name   TEXT,
  note          TEXT,
  opened_by_sn  TEXT,
  opened_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at     TIMESTAMPTZ,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- One live check per table.
CREATE UNIQUE INDEX IF NOT EXISTS dine_checks_one_open_per_table ON dine_checks (table_id) WHERE status IN ('OPEN', 'BILLED');
CREATE INDEX IF NOT EXISTS dine_checks_org_status_idx ON dine_checks (org_id, status);

CREATE TABLE IF NOT EXISTS dine_check_items (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  check_id          UUID NOT NULL REFERENCES dine_checks(id) ON DELETE CASCADE,
  round             INTEGER NOT NULL,
  product_id        UUID REFERENCES products(id) ON DELETE SET NULL,
  name              TEXT NOT NULL,
  options           JSONB NOT NULL DEFAULT '[]'::jsonb,   -- ["Mild", "Egg"]
  quantity          INTEGER NOT NULL CHECK (quantity > 0),
  unit_price_cents  INTEGER NOT NULL CHECK (unit_price_cents >= 0),  -- options included
  tax_rate_bps      INTEGER NOT NULL DEFAULT 0 CHECK (tax_rate_bps BETWEEN 0 AND 10000),
  note              TEXT,
  status            TEXT NOT NULL DEFAULT 'SENT' CHECK (status IN ('SENT', 'VOID')),
  paid_ecr_ref_num  TEXT,          -- set when paid on its own (split by items)
  sent_by           TEXT,
  sent_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  voided_by         TEXT,
  voided_at         TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS dine_check_items_check_idx ON dine_check_items (check_id);

CREATE TABLE IF NOT EXISTS dine_check_payments (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  check_id       UUID NOT NULL REFERENCES dine_checks(id) ON DELETE CASCADE,
  ecr_ref_num    TEXT NOT NULL UNIQUE,      -- the transaction; replays are no-ops
  amount_cents   INTEGER NOT NULL CHECK (amount_cents > 0),  -- food + tax, without tip
  tip_cents      INTEGER NOT NULL DEFAULT 0,
  method         TEXT,
  paid_by        TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS dine_check_payments_check_idx ON dine_check_payments (check_id);

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS check_id UUID;

ALTER TABLE dining_tables ENABLE ROW LEVEL SECURITY;
ALTER TABLE dine_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE dine_check_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE dine_check_payments ENABLE ROW LEVEL SECURITY;

-- Read: the store's devices, and org members (CMP reports later).
DROP POLICY IF EXISTS "Store reads dining tables" ON dining_tables;
CREATE POLICY "Store reads dining tables" ON dining_tables FOR SELECT TO authenticated USING (
  org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid())
  OR is_sys_admin() OR org_id IN (SELECT member_org_ids()));
DROP POLICY IF EXISTS "Store reads dine checks" ON dine_checks;
CREATE POLICY "Store reads dine checks" ON dine_checks FOR SELECT TO authenticated USING (
  org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid())
  OR is_sys_admin() OR org_id IN (SELECT member_org_ids()));
DROP POLICY IF EXISTS "Visible checks' items" ON dine_check_items;
CREATE POLICY "Visible checks' items" ON dine_check_items FOR SELECT TO authenticated USING (check_id IN (SELECT id FROM dine_checks));
DROP POLICY IF EXISTS "Visible checks' payments" ON dine_check_payments;
CREATE POLICY "Visible checks' payments" ON dine_check_payments FOR SELECT TO authenticated USING (check_id IN (SELECT id FROM dine_checks));

-- The calling terminal's store, or NULL.
CREATE OR REPLACE FUNCTION public.dine_device_org() RETURNS UUID
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT org_id FROM device_auth_map
   WHERE auth_user_id = auth.uid() AND org_id IS NOT NULL
   ORDER BY created_at DESC LIMIT 1
$$;

-- A check's food + tax (non-void lines), tax once per rate half up.
CREATE OR REPLACE FUNCTION public.dine_check_total(p_check_id UUID) RETURNS INTEGER
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT coalesce(sum(rate_total + round(rate_total * rate_bps / 10000.0)), 0)::INT FROM (
    SELECT tax_rate_bps AS rate_bps, sum(quantity * unit_price_cents) AS rate_total
      FROM dine_check_items WHERE check_id = p_check_id AND status <> 'VOID'
     GROUP BY tax_rate_bps) r
$$;

-- Add, rename, move or retire a table (Ourea Settings, managers).
CREATE OR REPLACE FUNCTION public.dine_save_table(p_id UUID, p_name TEXT, p_area TEXT, p_seats INT, p_sort INT, p_active BOOLEAN)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_org UUID := dine_device_org(); v_id UUID;
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal'); END IF;
  IF coalesce(trim(p_name), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'name_required'); END IF;
  IF p_id IS NULL THEN
    INSERT INTO dining_tables (org_id, name, area, seats, sort, is_active)
    VALUES (v_org, trim(p_name), nullif(trim(p_area), ''), greatest(1, least(coalesce(p_seats, 4), 50)), coalesce(p_sort, 0), true)
    RETURNING id INTO v_id;
  ELSE
    IF NOT coalesce(p_active, true) AND EXISTS (SELECT 1 FROM dine_checks WHERE table_id = p_id AND status IN ('OPEN', 'BILLED')) THEN
      RETURN jsonb_build_object('success', false, 'message', 'table_in_use');
    END IF;
    UPDATE dining_tables SET name = trim(p_name), area = nullif(trim(p_area), ''),
           seats = greatest(1, least(coalesce(p_seats, 4), 50)), sort = coalesce(p_sort, sort), is_active = coalesce(p_active, true)
     WHERE id = p_id AND org_id = v_org RETURNING id INTO v_id;
    IF v_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'table_not_found'); END IF;
  END IF;
  RETURN jsonb_build_object('success', true, 'id', v_id);
EXCEPTION WHEN unique_violation THEN
  RETURN jsonb_build_object('success', false, 'message', 'name_taken');
END $$;

-- Seat a table. Already open: returns that check (two servers seating the same table at once).
CREATE OR REPLACE FUNCTION public.dine_open_check(p_table_id UUID, p_guests INT, p_server_id UUID, p_server_name TEXT, p_device_sn TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine_checks%ROWTYPE; v_no INT; v_day DATE := (now() AT TIME ZONE 'America/Toronto')::date;
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal'); END IF;
  PERFORM 1 FROM dining_tables WHERE id = p_table_id AND org_id = v_org AND is_active FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'table_not_found'); END IF;
  SELECT * INTO v_check FROM dine_checks WHERE table_id = p_table_id AND status IN ('OPEN', 'BILLED');
  IF FOUND THEN RETURN jsonb_build_object('success', true, 'id', v_check.id, 'check_no', v_check.check_no, 'existing', true); END IF;
  PERFORM pg_advisory_xact_lock(hashtext('dine_check_no:' || v_org::text));
  SELECT coalesce(max(check_no), 0) + 1 INTO v_no FROM dine_checks WHERE org_id = v_org AND business_day = v_day;
  INSERT INTO dine_checks (org_id, table_id, check_no, business_day, guests, server_id, server_name, opened_by_sn)
  VALUES (v_org, p_table_id, v_no, v_day, greatest(1, least(coalesce(p_guests, 1), 99)), p_server_id, nullif(trim(p_server_name), ''), nullif(trim(p_device_sn), ''))
  RETURNING * INTO v_check;
  RETURN jsonb_build_object('success', true, 'id', v_check.id, 'check_no', v_check.check_no, 'existing', false);
END $$;

-- Send the lines just taken to the kitchen as the next round. Returns the round number.
-- p_items: [{product_id, name, options[], quantity, unit_price_cents, tax_rate_bps, note}]
CREATE OR REPLACE FUNCTION public.dine_send_items(p_check_id UUID, p_items JSONB, p_sent_by TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine_checks%ROWTYPE; v_round INT; v_item JSONB;
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal'); END IF;
  SELECT * INTO v_check FROM dine_checks WHERE id = p_check_id FOR UPDATE;
  IF NOT FOUND OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'check_not_found'); END IF;
  IF v_check.status NOT IN ('OPEN', 'BILLED') THEN RETURN jsonb_build_object('success', false, 'message', 'check_' || lower(v_check.status)); END IF;
  IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN
    RETURN jsonb_build_object('success', false, 'message', 'items_required');
  END IF;
  FOR v_item IN SELECT * FROM jsonb_array_elements(p_items) LOOP
    IF coalesce(trim(v_item->>'name'), '') = '' OR coalesce((v_item->>'quantity')::INT, 0) <= 0
       OR coalesce((v_item->>'unit_price_cents')::INT, -1) < 0
       OR coalesce((v_item->>'tax_rate_bps')::INT, 0) NOT BETWEEN 0 AND 10000 THEN
      RETURN jsonb_build_object('success', false, 'message', 'invalid_item');
    END IF;
  END LOOP;
  SELECT coalesce(max(round), 0) + 1 INTO v_round FROM dine_check_items WHERE check_id = p_check_id;
  INSERT INTO dine_check_items (check_id, round, product_id, name, options, quantity, unit_price_cents, tax_rate_bps, note, sent_by)
  SELECT p_check_id, v_round, nullif(i->>'product_id', '')::UUID, trim(i->>'name'),
         coalesce(i->'options', '[]'::jsonb), (i->>'quantity')::INT, (i->>'unit_price_cents')::INT,
         coalesce((i->>'tax_rate_bps')::INT, 0), nullif(trim(i->>'note'), ''), nullif(trim(p_sent_by), '')
    FROM jsonb_array_elements(p_items) i;
  -- New food on a billed check: the bill is stale.
  UPDATE dine_checks SET status = 'OPEN', updated_at = now() WHERE id = p_check_id;
  RETURN jsonb_build_object('success', true, 'round', v_round);
END $$;

-- Void a line already sent (the app asks for a manager first).
CREATE OR REPLACE FUNCTION public.dine_void_item(p_item_id UUID, p_voided_by TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_org UUID := dine_device_org(); v_item dine_check_items%ROWTYPE; v_check dine_checks%ROWTYPE;
BEGIN
  SELECT * INTO v_item FROM dine_check_items WHERE id = p_item_id FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'item_not_found'); END IF;
  SELECT * INTO v_check FROM dine_checks WHERE id = v_item.check_id FOR UPDATE;
  IF v_org IS NULL OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'item_not_found'); END IF;
  IF v_check.status NOT IN ('OPEN', 'BILLED') THEN RETURN jsonb_build_object('success', false, 'message', 'check_' || lower(v_check.status)); END IF;
  IF v_item.paid_ecr_ref_num IS NOT NULL THEN RETURN jsonb_build_object('success', false, 'message', 'item_paid'); END IF;
  UPDATE dine_check_items SET status = 'VOID', voided_by = nullif(trim(p_voided_by), ''), voided_at = now() WHERE id = p_item_id;
  UPDATE dine_checks SET updated_at = now() WHERE id = v_check.id;
  RETURN jsonb_build_object('success', true);
END $$;

-- Bill printed (BILLED), guests changed, note, or cancel an empty table (VOID).
CREATE OR REPLACE FUNCTION public.dine_update_check(p_check_id UUID, p_status TEXT, p_guests INT, p_note TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine_checks%ROWTYPE;
BEGIN
  SELECT * INTO v_check FROM dine_checks WHERE id = p_check_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'check_not_found'); END IF;
  IF v_check.status NOT IN ('OPEN', 'BILLED') THEN RETURN jsonb_build_object('success', false, 'message', 'check_' || lower(v_check.status)); END IF;
  IF p_status = 'VOID' AND (EXISTS (SELECT 1 FROM dine_check_items WHERE check_id = p_check_id AND status <> 'VOID')
                            OR EXISTS (SELECT 1 FROM dine_check_payments WHERE check_id = p_check_id)) THEN
    RETURN jsonb_build_object('success', false, 'message', 'check_not_empty');
  END IF;
  IF p_status IS NOT NULL AND p_status NOT IN ('OPEN', 'BILLED', 'VOID') THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_status');
  END IF;
  UPDATE dine_checks SET status = coalesce(p_status, status),
         guests = coalesce(greatest(1, least(p_guests, 99)), guests),
         note = CASE WHEN p_note IS NULL THEN note ELSE nullif(trim(p_note), '') END,
         closed_at = CASE WHEN p_status = 'VOID' THEN now() ELSE closed_at END,
         updated_at = now()
   WHERE id = p_check_id;
  RETURN jsonb_build_object('success', true);
END $$;

-- Record a payment on a check (after the money is taken). p_item_ids: the lines
-- this payment covers when splitting by items. Closes the check once covered.
-- Idempotent per ecr_ref_num (offline replay).
CREATE OR REPLACE FUNCTION public.dine_record_payment(p_check_id UUID, p_ecr_ref_num TEXT, p_amount_cents INT, p_tip_cents INT,
                                                      p_method TEXT, p_paid_by TEXT, p_item_ids UUID[])
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine_checks%ROWTYPE; v_total INT; v_paid INT;
BEGIN
  SELECT * INTO v_check FROM dine_checks WHERE id = p_check_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'check_not_found'); END IF;
  IF coalesce(p_amount_cents, 0) <= 0 OR coalesce(trim(p_ecr_ref_num), '') = '' THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_payment');
  END IF;
  INSERT INTO dine_check_payments (check_id, ecr_ref_num, amount_cents, tip_cents, method, paid_by)
  VALUES (p_check_id, trim(p_ecr_ref_num), p_amount_cents, greatest(coalesce(p_tip_cents, 0), 0), p_method, nullif(trim(p_paid_by), ''))
  ON CONFLICT (ecr_ref_num) DO NOTHING;
  IF p_item_ids IS NOT NULL THEN
    UPDATE dine_check_items SET paid_ecr_ref_num = trim(p_ecr_ref_num)
     WHERE check_id = p_check_id AND id = ANY (p_item_ids) AND paid_ecr_ref_num IS NULL;
  END IF;
  v_total := dine_check_total(p_check_id);
  SELECT coalesce(sum(amount_cents), 0) INTO v_paid FROM dine_check_payments WHERE check_id = p_check_id;
  IF v_paid >= v_total AND v_total > 0 THEN
    UPDATE dine_checks SET status = 'PAID', closed_at = coalesce(closed_at, now()), updated_at = now() WHERE id = p_check_id;
  ELSE
    UPDATE dine_checks SET updated_at = now() WHERE id = p_check_id;
  END IF;
  UPDATE transactions SET check_id = p_check_id WHERE ecr_ref_num = trim(p_ecr_ref_num) AND check_id IS NULL;
  RETURN jsonb_build_object('success', true, 'total_cents', v_total, 'paid_cents', v_paid,
                            'balance_cents', greatest(v_total - v_paid, 0), 'closed', v_paid >= v_total AND v_total > 0);
END $$;

REVOKE ALL ON FUNCTION public.dine_device_org() FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_check_total(UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_save_table(UUID, TEXT, TEXT, INT, INT, BOOLEAN) FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_open_check(UUID, INT, UUID, TEXT, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_send_items(UUID, JSONB, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_void_item(UUID, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_update_check(UUID, TEXT, INT, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_record_payment(UUID, TEXT, INT, INT, TEXT, TEXT, UUID[]) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.dine_check_total(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_save_table(UUID, TEXT, TEXT, INT, INT, BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_open_check(UUID, INT, UUID, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_send_items(UUID, JSONB, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_void_item(UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_update_check(UUID, TEXT, INT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_record_payment(UUID, TEXT, INT, INT, TEXT, TEXT, UUID[]) TO authenticated;
