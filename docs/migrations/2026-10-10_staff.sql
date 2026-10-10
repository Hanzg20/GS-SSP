-- 2026-10-10: staff -- the people who operate a store's Iris handhelds
-- (cashiers, drivers, managers). Not CMP logins: no email, just a name, a role
-- and a 4-digit PIN that is unique within the org, so the PIN alone says who
-- is signing in. Replaces Iris's hard-coded PIN 1234.
--
-- PIN storage: PBKDF2-HMAC-SHA256 (hex salt + hex hash + iterations), hashed
-- in CMP's browser (WebCrypto) and verified on the terminal, which keeps a
-- copy of its org's staff list so sign-in works offline (delivery). A 4-digit
-- PIN can't resist offline guessing whatever the hash; what protects the hash
-- is RLS -- only the org's own members and terminals can read these rows.
--
-- transactions.staff_id / staff_name: who took the payment (name copied at
-- sale time so the record survives a rename or removal).

CREATE TABLE IF NOT EXISTS staff (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  name            TEXT NOT NULL CHECK (char_length(trim(name)) BETWEEN 1 AND 60),
  role            TEXT NOT NULL DEFAULT 'CASHIER' CHECK (role IN ('CASHIER', 'MANAGER')),
  pin_salt        TEXT NOT NULL,
  pin_hash        TEXT NOT NULL,
  pin_iterations  INTEGER NOT NULL CHECK (pin_iterations > 0),
  is_active       BOOLEAN NOT NULL DEFAULT true,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS staff_org_idx ON staff (org_id);

ALTER TABLE staff ENABLE ROW LEVEL SECURITY;

INSERT INTO permissions (key, description, category) VALUES
  ('staff.manage', 'Add/edit/disable terminal staff and reset their PINs', 'team')
ON CONFLICT (key) DO NOTHING;
INSERT INTO capability_permissions (capability, permission) VALUES
  ('admin', 'staff.manage'), ('decision_maker', 'staff.manage')
ON CONFLICT DO NOTHING;

DROP POLICY IF EXISTS "Org members can view staff" ON staff;
CREATE POLICY "Org members can view staff" ON staff FOR SELECT TO authenticated
  USING (is_sys_admin() OR org_id IN (SELECT member_org_ids()));
DROP POLICY IF EXISTS "Staff managers can insert staff" ON staff;
CREATE POLICY "Staff managers can insert staff" ON staff FOR INSERT TO authenticated
  WITH CHECK (has_permission('staff.manage') AND (is_sys_admin() OR org_id IN (SELECT member_org_ids())));
DROP POLICY IF EXISTS "Staff managers can update staff" ON staff;
CREATE POLICY "Staff managers can update staff" ON staff FOR UPDATE TO authenticated
  USING (has_permission('staff.manage') AND (is_sys_admin() OR org_id IN (SELECT member_org_ids())));
DROP POLICY IF EXISTS "Staff managers can delete staff" ON staff;
CREATE POLICY "Staff managers can delete staff" ON staff FOR DELETE TO authenticated
  USING (has_permission('staff.manage') AND (is_sys_admin() OR org_id IN (SELECT member_org_ids())));

-- Terminals: their org's active staff (with PIN hashes, for offline sign-in).
DROP POLICY IF EXISTS "Devices can view their org's active staff" ON staff;
CREATE POLICY "Devices can view their org's active staff" ON staff FOR SELECT TO authenticated
  USING (is_active AND org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid()));

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS staff_id UUID REFERENCES staff(id) ON DELETE SET NULL;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS staff_name TEXT;

NOTIFY pgrst, 'reload schema';
