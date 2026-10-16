-- 2026-10-16: store checklists (opening / closing / any routine) -- the Journal (手账)
-- on Iris / Ourea. A new domain schema, `office` (store back office;
-- docs/database_design.md §3.1). expenses, reminders and notes belong here too
-- and move later (§2 "→ office"); this file doesn't touch them.
--
--   office.checklists       a routine: name, due time, weekdays it runs
--   office.checklist_items  its steps, in order; a removed step is deactivated, not
--                           deleted, so past days' ticks keep their text
--   office.checklist_ticks  a step done on a business day: who, when, which terminal
--
-- Not exposed through the API (§3.2): terminals call the public office_* functions
-- (SECURITY DEFINER, store taken from the calling device's device_auth_map row).
-- Editing a checklist is managers-only in the app (as voiding a dish is); ticking
-- is any signed-in staff member. Business day: America/Toronto, as dine.checks.

BEGIN;

CREATE SCHEMA IF NOT EXISTS office;
COMMENT ON SCHEMA office IS 'Store back office (the Journal): checklists; expenses, reminders, notes to move here. Access through public.office_* functions only.';
REVOKE ALL ON SCHEMA office FROM public, anon, authenticated;

CREATE TABLE IF NOT EXISTS office.checklists (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  name        TEXT NOT NULL CHECK (char_length(trim(name)) BETWEEN 1 AND 60),
  due_time    TIME NOT NULL,                  -- store-local; the app shows it as due from then
  -- Weekdays it runs: bit 0 = Monday ... bit 6 = Sunday (ISO); 127 = every day.
  days        SMALLINT NOT NULL DEFAULT 127 CHECK (days BETWEEN 1 AND 127),
  sort        INTEGER NOT NULL DEFAULT 0,
  is_active   BOOLEAN NOT NULL DEFAULT true,
  created_by  TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS checklists_org_name_key ON office.checklists (org_id, lower(name)) WHERE is_active;

CREATE TABLE IF NOT EXISTS office.checklist_items (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  checklist_id  UUID NOT NULL REFERENCES office.checklists(id) ON DELETE CASCADE,
  text          TEXT NOT NULL CHECK (char_length(trim(text)) BETWEEN 1 AND 200),
  sort          INTEGER NOT NULL DEFAULT 0,
  is_active     BOOLEAN NOT NULL DEFAULT true,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS checklist_items_checklist_idx ON office.checklist_items (checklist_id);

CREATE TABLE IF NOT EXISTS office.checklist_ticks (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  checklist_id  UUID NOT NULL REFERENCES office.checklists(id) ON DELETE CASCADE,
  item_id       UUID NOT NULL REFERENCES office.checklist_items(id) ON DELETE CASCADE,
  business_day  DATE NOT NULL,
  staff_id      UUID REFERENCES staff(id) ON DELETE SET NULL,
  staff_name    TEXT,
  device_sn     TEXT,
  done_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- A step is done once a day: two terminals ticking it at once is one tick.
CREATE UNIQUE INDEX IF NOT EXISTS checklist_ticks_item_day_key ON office.checklist_ticks (item_id, business_day);
CREATE INDEX IF NOT EXISTS checklist_ticks_org_day_idx ON office.checklist_ticks (org_id, business_day);

ALTER TABLE office.checklists ENABLE ROW LEVEL SECURITY;
ALTER TABLE office.checklist_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE office.checklist_ticks ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON ALL TABLES IN SCHEMA office FROM public, anon, authenticated;

COMMENT ON TABLE office.checklists      IS 'Store routines (opening, closing...): due time and weekdays; edited by managers in the Journal (Iris / Ourea)';
COMMENT ON TABLE office.checklist_items IS 'Steps of a checklist, in order; removed steps are deactivated so past ticks keep their text';
COMMENT ON TABLE office.checklist_ticks IS 'A step done on a business day (America/Toronto): staff, terminal, time; written by Iris / Ourea';

-- ---------------------------------------------------------------- helpers

-- The calling terminal's store (as dine_device_org).
CREATE OR REPLACE FUNCTION public.office_device_org() RETURNS UUID
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT org_id FROM device_auth_map
   WHERE auth_user_id = auth.uid() AND org_id IS NOT NULL
   ORDER BY created_at DESC LIMIT 1
$$;

CREATE OR REPLACE FUNCTION public.office_business_day() RETURNS DATE
LANGUAGE sql STABLE AS $$ SELECT (now() AT TIME ZONE 'America/Toronto')::date $$;

-- ---------------------------------------------------------------- reads

-- Every active checklist of the store with its active steps (the managers' editor).
CREATE OR REPLACE FUNCTION public.office_checklists() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, office AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
           'id', c.id, 'name', c.name, 'due_time', to_char(c.due_time, 'HH24:MI'), 'days', c.days, 'sort', c.sort,
           'items', coalesce((SELECT jsonb_agg(jsonb_build_object('id', i.id, 'text', i.text) ORDER BY i.sort, i.created_at)
                                FROM office.checklist_items i WHERE i.checklist_id = c.id AND i.is_active), '[]'::jsonb))
         ORDER BY c.due_time, c.sort, c.name), '[]'::jsonb)
    FROM office.checklists c
   WHERE c.org_id = office_device_org() AND c.is_active
$$;

-- Today's checklists (those whose weekdays include today) with each step and, when
-- done today, who did it and when. Also the business day it answers for.
CREATE OR REPLACE FUNCTION public.office_today() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, office AS $$
  SELECT jsonb_build_object(
    'business_day', office_business_day(),
    'checklists', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'id', c.id, 'name', c.name, 'due_time', to_char(c.due_time, 'HH24:MI'), 'days', c.days,
               'items', coalesce((
                 SELECT jsonb_agg(jsonb_build_object('id', i.id, 'text', i.text,
                          'done_at', t.done_at, 'staff_name', t.staff_name) ORDER BY i.sort, i.created_at)
                   FROM office.checklist_items i
                   LEFT JOIN office.checklist_ticks t ON t.item_id = i.id AND t.business_day = office_business_day()
                  WHERE i.checklist_id = c.id AND i.is_active), '[]'::jsonb))
             ORDER BY c.due_time, c.sort, c.name)
        FROM office.checklists c
       WHERE c.org_id = office_device_org() AND c.is_active
         AND (c.days >> (extract(isodow FROM office_business_day())::int - 1)) & 1 = 1), '[]'::jsonb))
$$;

-- ---------------------------------------------------------------- writes

-- Create (p_id NULL) or edit a checklist. p_items: [{"id": <uuid or null>, "text": "..."}]
-- in order -- an id keeps that step (and its ticks), no id adds one, a step left out is
-- deactivated. p_active false removes the checklist (its history stays).
CREATE OR REPLACE FUNCTION public.office_save_checklist(
  p_id UUID, p_name TEXT, p_due_time TEXT, p_days INT, p_items JSONB,
  p_active BOOLEAN DEFAULT true, p_staff_name TEXT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, office AS $$
DECLARE v_org UUID := office_device_org(); v_id UUID; v_keep UUID[] := '{}'; v_item JSONB; v_item_id UUID; v_sort INT := 0;
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal'); END IF;
  IF p_id IS NOT NULL AND NOT coalesce(p_active, true) THEN
    UPDATE office.checklists SET is_active = false, updated_at = now() WHERE id = p_id AND org_id = v_org RETURNING id INTO v_id;
    IF v_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'checklist_not_found'); END IF;
    RETURN jsonb_build_object('success', true, 'id', v_id);
  END IF;
  IF coalesce(trim(p_name), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'name_required'); END IF;
  IF p_due_time IS NULL OR p_due_time !~ '^([01]?[0-9]|2[0-3]):[0-5][0-9]$' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_time'); END IF;
  IF p_days IS NULL OR p_days NOT BETWEEN 1 AND 127 THEN RETURN jsonb_build_object('success', false, 'message', 'no_days'); END IF;
  IF jsonb_typeof(p_items) IS DISTINCT FROM 'array'
     OR NOT EXISTS (SELECT 1 FROM jsonb_array_elements(p_items) e WHERE coalesce(trim(e->>'text'), '') <> '') THEN
    RETURN jsonb_build_object('success', false, 'message', 'items_required');
  END IF;

  IF p_id IS NULL THEN
    INSERT INTO office.checklists (org_id, name, due_time, days, created_by)
    VALUES (v_org, trim(p_name), p_due_time::time, p_days, nullif(trim(p_staff_name), ''))
    RETURNING id INTO v_id;
  ELSE
    UPDATE office.checklists SET name = trim(p_name), due_time = p_due_time::time, days = p_days, updated_at = now()
     WHERE id = p_id AND org_id = v_org AND is_active RETURNING id INTO v_id;
    IF v_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'checklist_not_found'); END IF;
  END IF;

  FOR v_item IN SELECT value FROM jsonb_array_elements(p_items) LOOP
    CONTINUE WHEN coalesce(trim(v_item->>'text'), '') = '';
    v_sort := v_sort + 1;
    v_item_id := NULL;
    IF coalesce(v_item->>'id', '') <> '' THEN
      UPDATE office.checklist_items SET text = left(trim(v_item->>'text'), 200), sort = v_sort, is_active = true
       WHERE id = (v_item->>'id')::uuid AND checklist_id = v_id RETURNING id INTO v_item_id;
    END IF;
    IF v_item_id IS NULL THEN
      INSERT INTO office.checklist_items (org_id, checklist_id, text, sort)
      VALUES (v_org, v_id, left(trim(v_item->>'text'), 200), v_sort) RETURNING id INTO v_item_id;
    END IF;
    v_keep := v_keep || v_item_id;
  END LOOP;
  UPDATE office.checklist_items SET is_active = false WHERE checklist_id = v_id AND is_active AND NOT (id = ANY (v_keep));
  RETURN jsonb_build_object('success', true, 'id', v_id);
EXCEPTION WHEN unique_violation THEN
  RETURN jsonb_build_object('success', false, 'message', 'name_taken');
END $$;

-- Tick (p_done true) or untick a step for today. Ticking twice is one tick, the first one kept.
CREATE OR REPLACE FUNCTION public.office_tick(
  p_item_id UUID, p_done BOOLEAN, p_staff_id UUID DEFAULT NULL, p_staff_name TEXT DEFAULT NULL, p_device_sn TEXT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, office AS $$
DECLARE v_org UUID := office_device_org(); v_item office.checklist_items%ROWTYPE; v_day DATE := office_business_day();
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal'); END IF;
  SELECT * INTO v_item FROM office.checklist_items WHERE id = p_item_id AND org_id = v_org AND is_active;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'item_not_found'); END IF;
  IF coalesce(p_done, true) THEN
    INSERT INTO office.checklist_ticks (org_id, checklist_id, item_id, business_day, staff_id, staff_name, device_sn)
    VALUES (v_org, v_item.checklist_id, v_item.id, v_day,
            (SELECT s.id FROM staff s WHERE s.id = p_staff_id AND s.org_id = v_org), nullif(trim(p_staff_name), ''), nullif(trim(p_device_sn), ''))
    ON CONFLICT (item_id, business_day) DO NOTHING;
  ELSE
    DELETE FROM office.checklist_ticks WHERE item_id = v_item.id AND business_day = v_day;
  END IF;
  RETURN jsonb_build_object('success', true, 'business_day', v_day);
END $$;

-- ---------------------------------------------------------------- grants

REVOKE ALL ON FUNCTION public.office_device_org() FROM public, anon;
REVOKE ALL ON FUNCTION public.office_business_day() FROM public, anon;
REVOKE ALL ON FUNCTION public.office_checklists() FROM public, anon;
REVOKE ALL ON FUNCTION public.office_today() FROM public, anon;
REVOKE ALL ON FUNCTION public.office_save_checklist(UUID, TEXT, TEXT, INT, JSONB, BOOLEAN, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.office_tick(UUID, BOOLEAN, UUID, TEXT, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.office_device_org() TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_business_day() TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_checklists() TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_today() TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_save_checklist(UUID, TEXT, TEXT, INT, JSONB, BOOLEAN, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_tick(UUID, BOOLEAN, UUID, TEXT, TEXT) TO authenticated;

COMMIT;
