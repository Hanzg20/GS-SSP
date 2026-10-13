-- Ourea Scan end-to-end test data: its own organization, never a real store's.
-- Fixed ids and QR tokens, so it can be run again at any time (it resets the
-- menu and tables to this state). Orders, checks and payments are left alone;
-- reset.sql clears those. Remove everything with teardown.sql.
--
--   psql "$DB" -v ON_ERROR_STOP=1 -f tools/scan-e2e/seed.sql
--
-- QR links (local dev server: cd web/ourea-scan && npm run dev -- --host):
--   T1 主流程      http://<LAN IP>:5173/?t=e2e00000000000000000000000000001
--   T2 Cael 批准   http://<LAN IP>:5173/?t=e2e00000000000000000000000000002
--   T3 拒单        http://<LAN IP>:5173/?t=e2e00000000000000000000000000003
--   T4 限流 (脚本) http://<LAN IP>:5173/?t=e2e00000000000000000000000000004
--   T9 已停用      http://<LAN IP>:5173/?t=e2e00000000000000000000000000009

BEGIN;

INSERT INTO organizations (id, name)
VALUES ('e2e5ca00-0000-4000-8000-000000000001', 'ZZ 扫码点餐测试 Scan E2E')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO locations (id, org_id, name, timezone)
VALUES ('e2e5ca00-0000-4000-8000-000000000002', 'e2e5ca00-0000-4000-8000-000000000001', 'E2E 测试店', 'America/Toronto')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, timezone = EXCLUDED.timezone;

-- Menu: 4 categories, one dish with required + optional modifiers, one sold out,
-- one at 0% tax (zero-rated), the rest at the 13% default.
INSERT INTO products (id, org_id, vertical_type, name, price_cents, is_active, attributes) VALUES
  ('e2e5ca00-0000-4000-8001-000000000001', 'e2e5ca00-0000-4000-8000-000000000001', 'RETAIL',
   '红烧牛肉面 Braised Beef Noodle', 1495, true, '{
     "category": "面食 Noodles",
     "description": "慢炖牛腱，手工拉面。Slow-braised beef shank, hand-pulled noodles.",
     "modifier_groups": [
       {"id":"spice","name":"辣度 Spice","max_select":1,"required":true,"options":[
         {"id":"spice0","name":"不辣 Not spicy"},
         {"id":"spice1","name":"微辣 Mild"},
         {"id":"spice2","name":"中辣 Medium"}]},
       {"id":"addon","name":"加料 Add-ons","max_select":2,"options":[
         {"id":"egg","name":"加蛋 Egg","price_cents":150},
         {"id":"beef","name":"加牛肉 Extra beef","price_cents":300},
         {"id":"veg","name":"加青菜 Greens","price_cents":100}]}
     ]}'::jsonb),
  ('e2e5ca00-0000-4000-8001-000000000002', 'e2e5ca00-0000-4000-8000-000000000001', 'RETAIL',
   '炸酱面 Zhajiang Noodle', 1295, true, '{"category": "面食 Noodles", "description": "肉酱、黄瓜丝。Pork bean sauce, cucumber."}'::jsonb),
  ('e2e5ca00-0000-4000-8001-000000000003', 'e2e5ca00-0000-4000-8000-000000000001', 'RETAIL',
   '猪肉白菜饺子 (12) Pork Dumplings', 1095, true, '{"category": "饺子 Dumplings", "description": "含猪肉、小麦。Contains pork, wheat."}'::jsonb),
  ('e2e5ca00-0000-4000-8001-000000000004', 'e2e5ca00-0000-4000-8000-000000000001', 'RETAIL',
   '素三鲜饺子 (12) Veggie Dumplings', 995, true, '{"category": "饺子 Dumplings", "description": "素食，含鸡蛋、小麦。Vegetarian, contains egg, wheat."}'::jsonb),
  ('e2e5ca00-0000-4000-8001-000000000005', 'e2e5ca00-0000-4000-8000-000000000001', 'RETAIL',
   '小笼包 (售罄) Soup Dumplings — SOLD OUT', 1195, true, '{"category": "饺子 Dumplings", "stock_qty": 0}'::jsonb),
  ('e2e5ca00-0000-4000-8001-000000000006', 'e2e5ca00-0000-4000-8000-000000000001', 'RETAIL',
   '拍黄瓜 Smashed Cucumber', 695, true, '{"category": "凉菜 Cold dishes", "description": "素食，含大蒜。Vegan, garlic."}'::jsonb),
  ('e2e5ca00-0000-4000-8001-000000000007', 'e2e5ca00-0000-4000-8000-000000000001', 'RETAIL',
   '瓶装水 Bottled Water (0% tax)', 200, true, '{"category": "饮料 Drinks", "tax_rate_bps": 0}'::jsonb),
  ('e2e5ca00-0000-4000-8001-000000000008', 'e2e5ca00-0000-4000-8000-000000000001', 'RETAIL',
   '可乐 Coke', 250, true, '{"category": "饮料 Drinks"}'::jsonb),
  -- Off the menu: must never show and can't be ordered (item_unavailable).
  ('e2e5ca00-0000-4000-8001-000000000009', 'e2e5ca00-0000-4000-8000-000000000001', 'RETAIL',
   '下架菜 Retired Dish', 999, false, '{"category": "面食 Noodles"}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
  org_id = EXCLUDED.org_id, vertical_type = EXCLUDED.vertical_type, name = EXCLUDED.name,
  price_cents = EXCLUDED.price_cents, is_active = EXCLUDED.is_active, attributes = EXCLUDED.attributes;

INSERT INTO dine.tables (id, org_id, name, area, seats, sort, is_active, qr_token) VALUES
  ('e2e5ca00-0000-4000-8002-000000000001', 'e2e5ca00-0000-4000-8000-000000000001', 'T1', '大厅 Hall', 4, 1, true,  'e2e00000000000000000000000000001'),
  ('e2e5ca00-0000-4000-8002-000000000002', 'e2e5ca00-0000-4000-8000-000000000001', 'T2', '大厅 Hall', 4, 2, true,  'e2e00000000000000000000000000002'),
  ('e2e5ca00-0000-4000-8002-000000000003', 'e2e5ca00-0000-4000-8000-000000000001', 'T3', '大厅 Hall', 2, 3, true,  'e2e00000000000000000000000000003'),
  ('e2e5ca00-0000-4000-8002-000000000004', 'e2e5ca00-0000-4000-8000-000000000001', 'T4', '包间 Room', 8, 4, true,  'e2e00000000000000000000000000004'),
  ('e2e5ca00-0000-4000-8002-000000000009', 'e2e5ca00-0000-4000-8000-000000000001', 'T9', '包间 Room', 4, 9, false, 'e2e00000000000000000000000000009')
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name, area = EXCLUDED.area, seats = EXCLUDED.seats, sort = EXCLUDED.sort,
  is_active = EXCLUDED.is_active, qr_token = EXCLUDED.qr_token;

-- Staff for the terminal's sign-in: PIN 1234 manager, 5678 cashier
-- (PBKDF2-HMAC-SHA256, same format CMP writes; test-only PINs).
INSERT INTO staff (id, org_id, name, role, pin_salt, pin_hash, pin_iterations) VALUES
  ('e2e5ca00-0000-4000-8003-000000000001', 'e2e5ca00-0000-4000-8000-000000000001', 'E2E 经理 Manager', 'MANAGER',
   'e2e5ca00e2e5ca00e2e5ca00e2e5ca00', 'de6528172d08e1fae0d3b7c1e5be5ceb895de7c06344b0020c002fb446d773e6', 100000),
  ('e2e5ca00-0000-4000-8003-000000000002', 'e2e5ca00-0000-4000-8000-000000000001', 'E2E 服务员 Server', 'CASHIER',
   'e2e5ca00e2e5ca00e2e5ca00e2e5ca00', '3e9797050219f10d9614c8a9a4daac3a9be9072bd824e390def4da45dec4cdda', 100000)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, role = EXCLUDED.role, pin_salt = EXCLUDED.pin_salt,
  pin_hash = EXCLUDED.pin_hash, pin_iterations = EXCLUDED.pin_iterations, is_active = true;

COMMIT;

-- Terminal: tools/scan-e2e/bind-emulator.sql moves the Ourea emulator in and back.
-- For any other test device (never a store's), fill in its serial number:
--
-- UPDATE devices        SET org_id = 'e2e5ca00-0000-4000-8000-000000000001',
--                           loc_id = 'e2e5ca00-0000-4000-8000-000000000002' WHERE sn = '<emulator SN>';
-- UPDATE device_auth_map SET org_id = 'e2e5ca00-0000-4000-8000-000000000001' WHERE device_sn = '<emulator SN>';
