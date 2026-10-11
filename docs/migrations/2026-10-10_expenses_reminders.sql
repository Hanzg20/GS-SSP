-- 2026-10-10: store expenses and reminders, in the cloud.
--
-- expenses: what the store spends -- entered on Iris's Expenses page (by hand
-- or from a receipt photo) or by voice through 小海狸, always confirmed by a
-- manager before it is saved. Until now expenses lived only in the terminal's
-- local database (lost with the device, invisible to CMP). Categories follow
-- what a Canadian small business reports for tax. The id is generated on the
-- terminal so an upload retried after a network drop can't double-book.
--
-- reminders: a manager's to-dos ("call the supplier at 9 tomorrow"), set by
-- voice or typed; the terminal that set one shows a notification when it is
-- due, and 小海狸's brief lists what is due today or overdue.
--
-- Terminals write their own org's rows (device_auth_map), CMP members read
-- them; expenses.manage lets CMP edit/delete expenses.

CREATE TABLE IF NOT EXISTS expenses (
  id           UUID PRIMARY KEY,
  org_id       UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  device_sn    TEXT,
  staff_id     UUID REFERENCES staff(id) ON DELETE SET NULL,
  staff_name   TEXT,
  vendor       TEXT NOT NULL DEFAULT '' CHECK (char_length(vendor) <= 120),
  category     TEXT NOT NULL DEFAULT 'OTHER'
               CHECK (category IN ('INVENTORY', 'RENT', 'UTILITIES', 'PAYROLL', 'REPAIRS', 'MARKETING', 'FEES', 'OTHER')),
  amount_cents INTEGER NOT NULL CHECK (amount_cents > 0 AND amount_cents < 100000000),
  spent_on     DATE NOT NULL,
  note         TEXT CHECK (char_length(note) <= 500),
  source       TEXT NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'receipt', 'voice')),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS expenses_org_day_idx ON expenses (org_id, spent_on DESC);
ALTER TABLE expenses ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS reminders (
  id          UUID PRIMARY KEY,
  org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  device_sn   TEXT,
  staff_id    UUID REFERENCES staff(id) ON DELETE SET NULL,
  staff_name  TEXT,
  text        TEXT NOT NULL CHECK (char_length(trim(text)) BETWEEN 1 AND 300),
  due_at      TIMESTAMPTZ NOT NULL,
  done_at     TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS reminders_org_due_idx ON reminders (org_id, due_at);
ALTER TABLE reminders ENABLE ROW LEVEL SECURITY;

INSERT INTO permissions (key, description, category) VALUES
  ('expenses.manage', 'Edit and delete store expenses recorded on the terminals', 'finance')
ON CONFLICT (key) DO NOTHING;
INSERT INTO capability_permissions (capability, permission) VALUES
  ('admin', 'expenses.manage'), ('decision_maker', 'expenses.manage')
ON CONFLICT DO NOTHING;

-- Terminals: their own org's rows.
DROP POLICY IF EXISTS "Devices manage their org's expenses" ON expenses;
CREATE POLICY "Devices manage their org's expenses" ON expenses FOR ALL TO authenticated
  USING (org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid()))
  WITH CHECK (org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid()));
DROP POLICY IF EXISTS "Devices manage their org's reminders" ON reminders;
CREATE POLICY "Devices manage their org's reminders" ON reminders FOR ALL TO authenticated
  USING (org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid()))
  WITH CHECK (org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid()));

-- CMP.
DROP POLICY IF EXISTS "Members can view expenses" ON expenses;
CREATE POLICY "Members can view expenses" ON expenses FOR SELECT TO authenticated
  USING (is_sys_admin() OR org_id IN (SELECT member_org_ids()));
DROP POLICY IF EXISTS "Managers can update expenses" ON expenses;
CREATE POLICY "Managers can update expenses" ON expenses FOR UPDATE TO authenticated
  USING (has_permission('expenses.manage') AND (is_sys_admin() OR org_id IN (SELECT member_org_ids())));
DROP POLICY IF EXISTS "Managers can delete expenses" ON expenses;
CREATE POLICY "Managers can delete expenses" ON expenses FOR DELETE TO authenticated
  USING (has_permission('expenses.manage') AND (is_sys_admin() OR org_id IN (SELECT member_org_ids())));
DROP POLICY IF EXISTS "Members can view reminders" ON reminders;
CREATE POLICY "Members can view reminders" ON reminders FOR SELECT TO authenticated
  USING (is_sys_admin() OR org_id IN (SELECT member_org_ids()));

NOTIFY pgrst, 'reload schema';
