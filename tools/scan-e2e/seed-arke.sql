-- Arke (online ordering) on the Scan E2E test store: its link is ?s=zz-scan-e2e.
-- The store already has the Nuvei sandbox account (env int) the table page pays with,
-- so arke-pay uses the same. Safe to run again. Never a real store.
INSERT INTO online.stores (org_id, slug, is_open, pickup_minutes, hours, address, phone, notice)
VALUES ('e2e5ca00-0000-4000-8000-000000000001', 'zz-scan-e2e', true, 15, NULL,
        '123 Test St, Ottawa, ON', '(613) 555-0100', 'ZZ test store -- Arke online ordering tests')
ON CONFLICT (org_id) DO UPDATE SET slug = EXCLUDED.slug, is_open = true, pickup_minutes = EXCLUDED.pickup_minutes,
       hours = NULL, address = EXCLUDED.address, phone = EXCLUDED.phone, notice = EXCLUDED.notice, updated_at = now();

-- Reset: the store's online orders and their payment attempts (abuse limits count the last 10 min).
-- psql -v reset=1 ... to run it.
\if :{?reset}
DELETE FROM online.orders WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
UPDATE online.stores SET last_pickup_no = 0, last_pickup_day = NULL WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
\endif
