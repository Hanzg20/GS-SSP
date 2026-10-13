-- 2026-10-13: Cael (CMP) manages dine-in and QR ordering for a store: tables,
-- their QR codes (table cards to print), and later the live order console.
-- dine.* is not exposed through the API, so CMP goes through these functions
-- (docs/database_design.md §3.2): an org member reads, `dine.manage` writes.

BEGIN;

INSERT INTO permissions (key, description, category) VALUES
  ('dine.manage', 'Manage dine-in tables, QR codes and QR orders', 'dine')
ON CONFLICT (key) DO NOTHING;
INSERT INTO capability_permissions (capability, permission) VALUES
  ('admin', 'dine.manage'), ('decision_maker', 'dine.manage')
ON CONFLICT DO NOTHING;

-- Can the signed-in CMP user see / manage this store's dine-in?
CREATE OR REPLACE FUNCTION public.cmp_dine_can(p_org UUID, p_write BOOLEAN) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT is_sys_admin()
      OR (p_org IN (SELECT member_org_ids()) AND (NOT p_write OR has_permission('dine.manage')))
$$;

-- The store's tables (active and retired), with their QR secret and whether a check is open.
CREATE OR REPLACE FUNCTION public.cmp_dine_tables(p_org UUID) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
BEGIN
  IF NOT cmp_dine_can(p_org, false) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN coalesce((
    SELECT jsonb_agg(to_jsonb(t) || jsonb_build_object(
             'in_use', EXISTS (SELECT 1 FROM dine.checks c WHERE c.table_id = t.id AND c.status IN ('OPEN', 'BILLED')))
           ORDER BY t.is_active DESC, t.area NULLS FIRST, t.sort, t.name)
      FROM dine.tables t WHERE t.org_id = p_org), '[]'::jsonb);
END $$;

-- Add (p_id null) or change a table; same rules as the terminals' dine_save_table.
CREATE OR REPLACE FUNCTION public.cmp_dine_save_table(p_org UUID, p_id UUID, p_name TEXT, p_area TEXT, p_seats INT, p_sort INT, p_active BOOLEAN)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_id UUID;
BEGIN
  IF NOT cmp_dine_can(p_org, true) THEN RETURN jsonb_build_object('success', false, 'message', 'not_allowed'); END IF;
  IF coalesce(trim(p_name), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'name_required'); END IF;
  IF p_id IS NULL THEN
    INSERT INTO dine.tables (org_id, name, area, seats, sort, is_active)
    VALUES (p_org, trim(p_name), nullif(trim(p_area), ''), greatest(1, least(coalesce(p_seats, 4), 50)), coalesce(p_sort, 0), coalesce(p_active, true))
    RETURNING id INTO v_id;
  ELSE
    IF NOT coalesce(p_active, true) AND EXISTS (SELECT 1 FROM dine.checks WHERE table_id = p_id AND status IN ('OPEN', 'BILLED')) THEN
      RETURN jsonb_build_object('success', false, 'message', 'table_in_use');
    END IF;
    UPDATE dine.tables SET name = trim(p_name), area = nullif(trim(p_area), ''),
           seats = greatest(1, least(coalesce(p_seats, 4), 50)), sort = coalesce(p_sort, sort), is_active = coalesce(p_active, true)
     WHERE id = p_id AND org_id = p_org RETURNING id INTO v_id;
    IF v_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'table_not_found'); END IF;
  END IF;
  RETURN jsonb_build_object('success', true, 'id', v_id);
EXCEPTION WHEN unique_violation THEN
  RETURN jsonb_build_object('success', false, 'message', 'name_taken');
END $$;

-- A new QR secret: the printed card of this table stops working.
CREATE OR REPLACE FUNCTION public.cmp_dine_rotate_qr(p_org UUID, p_table_id UUID) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_token TEXT;
BEGIN
  IF NOT cmp_dine_can(p_org, true) THEN RETURN jsonb_build_object('success', false, 'message', 'not_allowed'); END IF;
  UPDATE dine.tables SET qr_token = encode(gen_random_bytes(16), 'hex')
   WHERE id = p_table_id AND org_id = p_org RETURNING qr_token INTO v_token;
  IF v_token IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'table_not_found'); END IF;
  RETURN jsonb_build_object('success', true, 'qr_token', v_token);
END $$;

REVOKE ALL ON FUNCTION public.cmp_dine_can(UUID, BOOLEAN) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_dine_tables(UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_dine_save_table(UUID, UUID, TEXT, TEXT, INT, INT, BOOLEAN) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_dine_rotate_qr(UUID, UUID) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_dine_tables(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_dine_save_table(UUID, UUID, TEXT, TEXT, INT, INT, BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_dine_rotate_qr(UUID, UUID) TO authenticated;

COMMIT;
