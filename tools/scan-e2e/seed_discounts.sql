-- Discount presets for the E2E store (tools/scan-e2e/seed.sql), matching the design.
BEGIN;
INSERT INTO discount_presets (id, org_id, name, percent_bps, amount_cents, applies_to, requires_manager, sort) VALUES
  ('e2e5ca00-0000-4000-8004-000000000001', 'e2e5ca00-0000-4000-8000-000000000001', 'Senior',     1000,  NULL, 'ANY',   false, 1),
  ('e2e5ca00-0000-4000-8004-000000000002', 'e2e5ca00-0000-4000-8000-000000000001', 'Staff meal', 5000,  NULL, 'ANY',   false, 2),
  ('e2e5ca00-0000-4000-8004-000000000003', 'e2e5ca00-0000-4000-8000-000000000001', 'Loyalty',    NULL,  300,  'ORDER', false, 3),
  ('e2e5ca00-0000-4000-8004-000000000004', 'e2e5ca00-0000-4000-8000-000000000001', 'Comp (on the house)', 10000, NULL, 'ANY', true, 4)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, percent_bps = EXCLUDED.percent_bps, amount_cents = EXCLUDED.amount_cents,
  applies_to = EXCLUDED.applies_to, requires_manager = EXCLUDED.requires_manager, sort = EXCLUDED.sort, is_active = true;
COMMIT;
