-- 2026-10-13: dine-in tables move into their own schema, `dine`
-- (docs/database_design.md §3: one schema per business domain).
--
--   public.dining_tables       -> dine.tables
--   public.dine_checks         -> dine.checks
--   public.dine_check_items    -> dine.check_items
--   public.dine_check_payments -> dine.payments
--
-- The `dine` schema is NOT exposed through the API: devices read and write
-- only through the public dine_* functions (SECURITY DEFINER, store taken
-- from the calling device), which are the dine-in API. Rows, indexes,
-- constraints and RLS move with the tables.

BEGIN;

CREATE SCHEMA IF NOT EXISTS dine;
COMMENT ON SCHEMA dine IS 'Dine-in (table service): floor, checks, rounds, check payments. Access through public.dine_* functions only.';
REVOKE ALL ON SCHEMA dine FROM public, anon, authenticated;

ALTER TABLE public.dining_tables       SET SCHEMA dine;
ALTER TABLE public.dine_checks         SET SCHEMA dine;
ALTER TABLE public.dine_check_items    SET SCHEMA dine;
ALTER TABLE public.dine_check_payments SET SCHEMA dine;
ALTER TABLE dine.dining_tables       RENAME TO tables;
ALTER TABLE dine.dine_checks         RENAME TO checks;
ALTER TABLE dine.dine_check_items    RENAME TO check_items;
ALTER TABLE dine.dine_check_payments RENAME TO payments;
REVOKE ALL ON ALL TABLES IN SCHEMA dine FROM public, anon, authenticated;

COMMENT ON TABLE dine.tables      IS 'The floor: tables by area, managed from Ourea Settings';
COMMENT ON TABLE dine.checks      IS 'One live check per table (OPEN -> BILLED -> PAID / VOID), check_no daily per store';
COMMENT ON TABLE dine.check_items IS 'Lines sent to the kitchen, by round; VOID needs a manager in the app';
COMMENT ON TABLE dine.payments    IS 'Payments on a check (split bills): food+tax, its tax part, tip';

-- ---------------------------------------------------------------- helpers

CREATE OR REPLACE FUNCTION public.dine_check_total(p_check_id UUID) RETURNS INTEGER
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT coalesce(sum(rate_total + round(rate_total * rate_bps / 10000.0)), 0)::INT FROM (
    SELECT tax_rate_bps AS rate_bps, sum(quantity * unit_price_cents) AS rate_total
      FROM dine.check_items WHERE check_id = p_check_id AND status <> 'VOID'
     GROUP BY tax_rate_bps) r
$$;

-- ---------------------------------------------------------------- reads

-- The store's active tables, in floor order.
CREATE OR REPLACE FUNCTION public.dine_list_tables() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.area NULLS FIRST, t.sort, t.name), '[]'::jsonb)
    FROM dine.tables t
   WHERE t.org_id = dine_device_org() AND t.is_active
$$;

-- The floor: tables, and each live check with its total.
CREATE OR REPLACE FUNCTION public.dine_floor() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object(
    'tables', dine_list_tables(),
    'checks', coalesce((
      SELECT jsonb_agg(to_jsonb(c) || jsonb_build_object('total_cents', dine_check_total(c.id)))
        FROM dine.checks c
       WHERE c.org_id = dine_device_org() AND c.status IN ('OPEN', 'BILLED')), '[]'::jsonb))
$$;

-- One check with its table, lines (by round) and payments; null when not this store's.
CREATE OR REPLACE FUNCTION public.dine_get_check(p_check_id UUID) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object(
    'check', to_jsonb(c),
    'table', (SELECT to_jsonb(t) FROM dine.tables t WHERE t.id = c.table_id),
    'items', coalesce((SELECT jsonb_agg(to_jsonb(i) ORDER BY i.round, i.sent_at) FROM dine.check_items i WHERE i.check_id = c.id), '[]'::jsonb),
    'payments', coalesce((SELECT jsonb_agg(to_jsonb(p) ORDER BY p.created_at) FROM dine.payments p WHERE p.check_id = c.id), '[]'::jsonb))
    FROM dine.checks c
   WHERE c.id = p_check_id AND c.org_id = dine_device_org()
$$;

-- ---------------------------------------------------------------- writes

CREATE OR REPLACE FUNCTION public.dine_save_table(p_id UUID, p_name TEXT, p_area TEXT, p_seats INT, p_sort INT, p_active BOOLEAN)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org(); v_id UUID;
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal'); END IF;
  IF coalesce(trim(p_name), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'name_required'); END IF;
  IF p_id IS NULL THEN
    INSERT INTO dine.tables (org_id, name, area, seats, sort, is_active)
    VALUES (v_org, trim(p_name), nullif(trim(p_area), ''), greatest(1, least(coalesce(p_seats, 4), 50)), coalesce(p_sort, 0), true)
    RETURNING id INTO v_id;
  ELSE
    IF NOT coalesce(p_active, true) AND EXISTS (SELECT 1 FROM dine.checks WHERE table_id = p_id AND status IN ('OPEN', 'BILLED')) THEN
      RETURN jsonb_build_object('success', false, 'message', 'table_in_use');
    END IF;
    UPDATE dine.tables SET name = trim(p_name), area = nullif(trim(p_area), ''),
           seats = greatest(1, least(coalesce(p_seats, 4), 50)), sort = coalesce(p_sort, sort), is_active = coalesce(p_active, true)
     WHERE id = p_id AND org_id = v_org RETURNING id INTO v_id;
    IF v_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'table_not_found'); END IF;
  END IF;
  RETURN jsonb_build_object('success', true, 'id', v_id);
EXCEPTION WHEN unique_violation THEN
  RETURN jsonb_build_object('success', false, 'message', 'name_taken');
END $$;

CREATE OR REPLACE FUNCTION public.dine_open_check(p_table_id UUID, p_guests INT, p_server_id UUID, p_server_name TEXT, p_device_sn TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine.checks%ROWTYPE; v_no INT; v_day DATE := (now() AT TIME ZONE 'America/Toronto')::date;
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal'); END IF;
  PERFORM 1 FROM dine.tables WHERE id = p_table_id AND org_id = v_org AND is_active FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'table_not_found'); END IF;
  SELECT * INTO v_check FROM dine.checks WHERE table_id = p_table_id AND status IN ('OPEN', 'BILLED');
  IF FOUND THEN RETURN jsonb_build_object('success', true, 'id', v_check.id, 'check_no', v_check.check_no, 'existing', true); END IF;
  PERFORM pg_advisory_xact_lock(hashtext('dine_check_no:' || v_org::text));
  SELECT coalesce(max(check_no), 0) + 1 INTO v_no FROM dine.checks WHERE org_id = v_org AND business_day = v_day;
  INSERT INTO dine.checks (org_id, table_id, check_no, business_day, guests, server_id, server_name, opened_by_sn)
  VALUES (v_org, p_table_id, v_no, v_day, greatest(1, least(coalesce(p_guests, 1), 99)), p_server_id, nullif(trim(p_server_name), ''), nullif(trim(p_device_sn), ''))
  RETURNING * INTO v_check;
  RETURN jsonb_build_object('success', true, 'id', v_check.id, 'check_no', v_check.check_no, 'existing', false);
END $$;

CREATE OR REPLACE FUNCTION public.dine_send_items(p_check_id UUID, p_items JSONB, p_sent_by TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine.checks%ROWTYPE; v_round INT; v_item JSONB;
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal'); END IF;
  SELECT * INTO v_check FROM dine.checks WHERE id = p_check_id FOR UPDATE;
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
  SELECT coalesce(max(round), 0) + 1 INTO v_round FROM dine.check_items WHERE check_id = p_check_id;
  INSERT INTO dine.check_items (check_id, round, product_id, name, options, quantity, unit_price_cents, tax_rate_bps, note, sent_by)
  SELECT p_check_id, v_round, nullif(i->>'product_id', '')::UUID, trim(i->>'name'),
         coalesce(i->'options', '[]'::jsonb), (i->>'quantity')::INT, (i->>'unit_price_cents')::INT,
         coalesce((i->>'tax_rate_bps')::INT, 0), nullif(trim(i->>'note'), ''), nullif(trim(p_sent_by), '')
    FROM jsonb_array_elements(p_items) i;
  UPDATE dine.checks SET status = 'OPEN', updated_at = now() WHERE id = p_check_id;
  RETURN jsonb_build_object('success', true, 'round', v_round);
END $$;

CREATE OR REPLACE FUNCTION public.dine_void_item(p_item_id UUID, p_voided_by TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org(); v_item dine.check_items%ROWTYPE; v_check dine.checks%ROWTYPE;
BEGIN
  SELECT * INTO v_item FROM dine.check_items WHERE id = p_item_id FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'item_not_found'); END IF;
  SELECT * INTO v_check FROM dine.checks WHERE id = v_item.check_id FOR UPDATE;
  IF v_org IS NULL OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'item_not_found'); END IF;
  IF v_check.status NOT IN ('OPEN', 'BILLED') THEN RETURN jsonb_build_object('success', false, 'message', 'check_' || lower(v_check.status)); END IF;
  IF v_item.paid_ecr_ref_num IS NOT NULL THEN RETURN jsonb_build_object('success', false, 'message', 'item_paid'); END IF;
  UPDATE dine.check_items SET status = 'VOID', voided_by = nullif(trim(p_voided_by), ''), voided_at = now() WHERE id = p_item_id;
  UPDATE dine.checks SET updated_at = now() WHERE id = v_check.id;
  RETURN jsonb_build_object('success', true);
END $$;

CREATE OR REPLACE FUNCTION public.dine_update_check(p_check_id UUID, p_status TEXT, p_guests INT, p_note TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine.checks%ROWTYPE;
BEGIN
  SELECT * INTO v_check FROM dine.checks WHERE id = p_check_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'check_not_found'); END IF;
  IF v_check.status NOT IN ('OPEN', 'BILLED') THEN RETURN jsonb_build_object('success', false, 'message', 'check_' || lower(v_check.status)); END IF;
  IF p_status = 'VOID' AND (EXISTS (SELECT 1 FROM dine.check_items WHERE check_id = p_check_id AND status <> 'VOID')
                            OR EXISTS (SELECT 1 FROM dine.payments WHERE check_id = p_check_id)) THEN
    RETURN jsonb_build_object('success', false, 'message', 'check_not_empty');
  END IF;
  IF p_status IS NOT NULL AND p_status NOT IN ('OPEN', 'BILLED', 'VOID') THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_status');
  END IF;
  UPDATE dine.checks SET status = coalesce(p_status, status),
         guests = coalesce(greatest(1, least(p_guests, 99)), guests),
         note = CASE WHEN p_note IS NULL THEN note ELSE nullif(trim(p_note), '') END,
         closed_at = CASE WHEN p_status = 'VOID' THEN now() ELSE closed_at END,
         updated_at = now()
   WHERE id = p_check_id;
  RETURN jsonb_build_object('success', true);
END $$;

CREATE OR REPLACE FUNCTION public.dine_record_payment(p_check_id UUID, p_ecr_ref_num TEXT, p_amount_cents INT, p_tax_cents INT, p_tip_cents INT,
                                                      p_method TEXT, p_paid_by TEXT, p_item_ids UUID[])
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine.checks%ROWTYPE; v_total INT; v_paid INT;
BEGIN
  SELECT * INTO v_check FROM dine.checks WHERE id = p_check_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'check_not_found'); END IF;
  IF coalesce(p_amount_cents, 0) <= 0 OR coalesce(trim(p_ecr_ref_num), '') = '' THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_payment');
  END IF;
  INSERT INTO dine.payments (check_id, ecr_ref_num, amount_cents, tax_cents, tip_cents, method, paid_by)
  VALUES (p_check_id, trim(p_ecr_ref_num), p_amount_cents, greatest(coalesce(p_tax_cents, 0), 0), greatest(coalesce(p_tip_cents, 0), 0), p_method, nullif(trim(p_paid_by), ''))
  ON CONFLICT (ecr_ref_num) DO NOTHING;
  IF p_item_ids IS NOT NULL THEN
    UPDATE dine.check_items SET paid_ecr_ref_num = trim(p_ecr_ref_num)
     WHERE check_id = p_check_id AND id = ANY (p_item_ids) AND paid_ecr_ref_num IS NULL;
  END IF;
  v_total := dine_check_total(p_check_id);
  SELECT coalesce(sum(amount_cents), 0) INTO v_paid FROM dine.payments WHERE check_id = p_check_id;
  IF v_paid >= v_total AND v_total > 0 THEN
    UPDATE dine.checks SET status = 'PAID', closed_at = coalesce(closed_at, now()), updated_at = now() WHERE id = p_check_id;
  ELSE
    UPDATE dine.checks SET updated_at = now() WHERE id = p_check_id;
  END IF;
  UPDATE public.transactions SET check_id = p_check_id WHERE ecr_ref_num = trim(p_ecr_ref_num) AND check_id IS NULL;
  RETURN jsonb_build_object('success', true, 'total_cents', v_total, 'paid_cents', v_paid,
                            'balance_cents', greatest(v_total - v_paid, 0), 'closed', v_paid >= v_total AND v_total > 0);
END $$;

REVOKE ALL ON FUNCTION public.dine_list_tables() FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_floor() FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_get_check(UUID) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.dine_list_tables() TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_floor() TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_get_check(UUID) TO authenticated;

COMMIT;
