-- Removes the E2E store completely (tables, menu, orders go with it).
-- Devices moved into it fall back to no org (ON DELETE SET NULL) -- reassign them.
-- Card transactions made during tests stay: financial history is never deleted.
--   psql "$DB" -v ON_ERROR_STOP=1 -f tools/scan-e2e/teardown.sql

BEGIN;
DELETE FROM dine.scan_orders WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
DELETE FROM dine.payments    WHERE check_id IN (SELECT id FROM dine.checks WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001');
DELETE FROM dine.check_items WHERE check_id IN (SELECT id FROM dine.checks WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001');
DELETE FROM dine.checks      WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
DELETE FROM staff            WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
DELETE FROM dine.tables      WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
DELETE FROM products         WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
DELETE FROM locations        WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
DELETE FROM organizations    WHERE id     = 'e2e5ca00-0000-4000-8000-000000000001';
COMMIT;
