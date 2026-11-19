-- Arke (online ordering) on the Scan E2E test store: its link is ?s=zz-scan-e2e.
-- The store already has the Nuvei sandbox account (env int) the table page pays with,
-- so arke-pay uses the same. Safe to run again. Never a real store.
INSERT INTO online.stores (org_id, slug, is_open, pickup_minutes, hours, address, phone, notice)
VALUES ('e2e5ca00-0000-4000-8000-000000000001', 'zz-scan-e2e', true, 15, NULL,
        '123 Test St, Ottawa, ON', '(613) 555-0100', '测试店 -- Arke online ordering tests')
ON CONFLICT (org_id) DO UPDATE SET slug = EXCLUDED.slug, is_open = true, pickup_minutes = EXCLUDED.pickup_minutes,
       hours = NULL, address = EXCLUDED.address, phone = EXCLUDED.phone, notice = EXCLUDED.notice, updated_at = now();

-- Phase 2: the store delivers (2026-11-17). Three zones: K2K / K2L near, K2M far (higher fee and
-- minimum), and K2K1X "next door" -- longer than K2K, so it wins for K2K 1X?.
UPDATE online.stores SET delivery_enabled = true, delivery_minutes = 40, delivery_tax_bps = 1300
 WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
DELETE FROM online.delivery_zones WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
INSERT INTO online.delivery_zones (org_id, name, postal_prefixes, fee_cents, min_order_cents, sort) VALUES
  ('e2e5ca00-0000-4000-8000-000000000001', 'Near',      '{K2K,K2L}', 399, 1500, 1),
  ('e2e5ca00-0000-4000-8000-000000000001', 'Far',       '{K2M}',     699, 3000, 2),
  ('e2e5ca00-0000-4000-8000-000000000001', 'Next door', '{K2K1X}',   100,    0, 3);

-- Coupons for online orders (2026-11-19_arke_coupons.sql): 10% off (1000 uses -- only PAID orders count one),
-- one expired, one wash-only. Serials 99xx stay clear of the store's own numbering.
INSERT INTO coupons (code, org_id, serial, type, value, max_uses, uses_count, expires_at, is_active, issued_reason, note) VALUES
  ('ARKETEST10', 'e2e5ca00-0000-4000-8000-000000000001', 9901, 'PERCENT_OFF', 10, 1000, 0, NULL, true, 'MARKETING', 'Arke E2E'),
  ('ARKEOLD5',   'e2e5ca00-0000-4000-8000-000000000001', 9902, 'FIXED_OFF', 500, 100, 0, '2026-01-01', true, 'MARKETING', 'Arke E2E: expired'),
  ('ARKEWASH',   'e2e5ca00-0000-4000-8000-000000000001', 9903, 'FREE_WASH', 0, 100, 0, NULL, true, 'MARKETING', 'Arke E2E: not for online')
ON CONFLICT (code) DO UPDATE SET type = EXCLUDED.type, value = EXCLUDED.value, max_uses = EXCLUDED.max_uses,
  expires_at = EXCLUDED.expires_at, is_active = true;

-- Store rewards (2026-11-01_loyalty.sql; online orders earn them, 2026-11-19_arke_loyalty.sql): 1 point per dollar,
-- 100 points = $5 off at the counter.
INSERT INTO member.settings (org_id, enabled, points_per_dollar, reward_points, reward_cents, updated_by)
VALUES ('e2e5ca00-0000-4000-8000-000000000001', true, 1, 100, 500, 'Arke E2E')
ON CONFLICT (org_id) DO UPDATE SET enabled = true, points_per_dollar = 1, reward_points = 100, reward_cents = 500;

-- Review link (2026-11-19_arke_review_link.sql): a placeholder -- the test store has no Google profile.
UPDATE online.stores SET review_url = 'https://example.com/zz-scan-e2e/review' WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';

-- Reset: the store's online orders and their payment attempts (abuse limits count the last 10 min).
-- psql -v reset=1 ... to run it.
\if :{?reset}
DELETE FROM online.orders WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
UPDATE online.stores SET last_pickup_no = 0, last_pickup_day = NULL WHERE org_id = 'e2e5ca00-0000-4000-8000-000000000001';
\endif
