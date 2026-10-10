-- 2026-10-10: transaction_items -- one row per sale line (what was sold, at
-- what price and tax rate). transactions only held totals, so a sale's tax
-- could not be checked afterwards and a return had nothing to go back to.
-- Keyed to transactions by ecr_ref_num (the terminal knows it, not the uuid);
-- (ecr_ref_num, line_no) unique so an offline-queue replay is idempotent.
-- Per-line tax is NOT stored: tax is rounded once per rate over the whole
-- sale (SalesTax), so transactions.tax_cents stays the authoritative total.
CREATE TABLE IF NOT EXISTS transaction_items (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  ecr_ref_num       TEXT NOT NULL REFERENCES transactions(ecr_ref_num) ON DELETE CASCADE,
  device_sn         TEXT NOT NULL,
  line_no           INTEGER NOT NULL,
  product_id        UUID REFERENCES products(id) ON DELETE SET NULL,
  name              TEXT NOT NULL,
  barcode           TEXT,
  quantity          INTEGER NOT NULL CHECK (quantity > 0),
  unit_price_cents  INTEGER NOT NULL,
  line_total_cents  INTEGER NOT NULL,
  tax_rate_bps      INTEGER NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ DEFAULT now(),
  CONSTRAINT transaction_items_ref_line_key UNIQUE (ecr_ref_num, line_no)
);
CREATE INDEX IF NOT EXISTS transaction_items_device_sn_idx ON transaction_items (device_sn);

ALTER TABLE transaction_items ENABLE ROW LEVEL SECURITY;

-- Same scoping as transactions: a device writes/reads its own rows, org members read their org's.
DROP POLICY IF EXISTS "Devices can insert own transaction items" ON transaction_items;
CREATE POLICY "Devices can insert own transaction items" ON transaction_items FOR INSERT TO authenticated
  WITH CHECK (device_sn IN (SELECT device_sn FROM device_auth_map WHERE auth_user_id = auth.uid()));
DROP POLICY IF EXISTS "Devices can read own transaction items" ON transaction_items;
CREATE POLICY "Devices can read own transaction items" ON transaction_items FOR SELECT TO authenticated
  USING (device_sn IN (SELECT device_sn FROM device_auth_map WHERE auth_user_id = auth.uid()));
DROP POLICY IF EXISTS "Org members can view org transaction items" ON transaction_items;
CREATE POLICY "Org members can view org transaction items" ON transaction_items FOR SELECT TO authenticated
  USING (is_sys_admin() OR device_sn IN (SELECT sn FROM devices WHERE org_id IN (SELECT member_org_ids())));

NOTIFY pgrst, 'reload schema';
