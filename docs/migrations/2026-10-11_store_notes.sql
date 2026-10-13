-- 2026-10-11: store notes (memos) in the cloud.
--
-- notes: store journal memos, shift handovers, and voice notes recorded by staff or
-- through 小海狸 (Beaver). Displayed alongside expenses and reminders in Store Journal (手账).

CREATE TABLE IF NOT EXISTS notes (
  id          UUID PRIMARY KEY,
  org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  device_sn   TEXT,
  staff_id    UUID REFERENCES staff(id) ON DELETE SET NULL,
  staff_name  TEXT,
  title       TEXT NOT NULL DEFAULT '',
  content     TEXT NOT NULL CHECK (char_length(trim(content)) > 0),
  tags        TEXT[] NOT NULL DEFAULT '{}',
  pinned      BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS notes_org_pinned_created_idx ON notes (org_id, pinned DESC, created_at DESC);
ALTER TABLE notes ENABLE ROW LEVEL SECURITY;

-- Permissions for CMP
INSERT INTO permissions (key, description, category) VALUES
  ('notes.manage', 'Create, edit and delete store notes/memos', 'operations')
ON CONFLICT (key) DO NOTHING;

INSERT INTO capability_permissions (capability, permission) VALUES
  ('admin', 'notes.manage'), ('decision_maker', 'notes.manage')
ON CONFLICT DO NOTHING;

-- Terminals: read and write their own org's notes
DROP POLICY IF EXISTS "Devices manage their org's notes" ON notes;
CREATE POLICY "Devices manage their org's notes" ON notes FOR ALL TO authenticated
  USING (org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid()))
  WITH CHECK (org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid()));

-- CMP: members can view notes
DROP POLICY IF EXISTS "Members can view notes" ON notes;
CREATE POLICY "Members can view notes" ON notes FOR SELECT TO authenticated
  USING (is_sys_admin() OR org_id IN (SELECT member_org_ids()));

-- CMP: managers can edit/delete notes
DROP POLICY IF EXISTS "Members can manage notes" ON notes;
CREATE POLICY "Members can manage notes" ON notes FOR ALL TO authenticated
  USING (has_permission('notes.manage') AND (is_sys_admin() OR org_id IN (SELECT member_org_ids())))
  WITH CHECK (has_permission('notes.manage') AND (is_sys_admin() OR org_id IN (SELECT member_org_ids())));

NOTIFY pgrst, 'reload schema';
