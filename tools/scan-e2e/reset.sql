-- Clears the E2E store's orders, checks and payments so a new run starts clean
-- (the abuse limits count a table's last 10 minutes). Menu and tables stay.
--   psql "$DB" -v ON_ERROR_STOP=1 -f tools/scan-e2e/reset.sql

BEGIN;
DELETE FROM dine.scan_orders WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
DELETE FROM dine.payments    WHERE check_id IN (SELECT id FROM dine.checks WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001');
DELETE FROM dine.check_items WHERE check_id IN (SELECT id FROM dine.checks WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001');
DELETE FROM dine.checks      WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
-- Sold-out / retired fixtures back to their seeded state, in case a manual test changed them.
UPDATE products SET attributes = attributes || '{"stock_qty": 0}'::jsonb WHERE id = 'e2e5ca00-0000-4000-8001-000000000005';
UPDATE products SET is_active = false WHERE id = 'e2e5ca00-0000-4000-8001-000000000009';
UPDATE dine.tables SET is_active = false WHERE id = 'e2e5ca00-0000-4000-8002-000000000009';
COMMIT;
