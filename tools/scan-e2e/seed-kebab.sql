-- Ourea Scan scenario store: a Turkish döner / kebab restaurant in Kanata (Ottawa), for the full
-- flow (QR -> Beaver -> cart -> kitchen on the Ourea -> call a server -> bill) on a halal menu
-- (owner 2026-11-09). Test store, "ZZ" name, generic dishes -- not any real restaurant's menu.
-- Safe to run again; teardown at the bottom.
--
--   psql "$DB" -v ON_ERROR_STOP=1 -f tools/scan-e2e/seed-kebab.sql
--   psql "$DB" -v ON_ERROR_STOP=1 -v store=kebab -f tools/scan-e2e/bind-emulator.sql   (Ourea emulator in)
--   QR: <site>/?t=kbab00000000000000000000000000NN  (NN = 01-04)
--
-- What it exercises: halal / vegan labels, required meat and side choices, sauces with a hot one,
-- a platter for two, a sold-out dessert, Turkish names with English, Arabic and Chinese
-- translations (many Arabic-speaking customers), yogurt / sesame / nut allergens.

BEGIN;

INSERT INTO organizations (id, name)
VALUES ('e2e5ca02-0000-4000-8000-000000000001', 'ZZ Kanata 土耳其烤肉测试 Döner House')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO locations (id, org_id, name, timezone)
VALUES ('e2e5ca02-0000-4000-8000-000000000002', 'e2e5ca02-0000-4000-8000-000000000001', 'Kanata (test)', 'America/Toronto')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, timezone = EXCLUDED.timezone;

INSERT INTO products (id, org_id, vertical_type, name, price_cents, is_active, attributes) VALUES
  -- Wraps
  ('e2e5ca02-0000-4000-8001-000000000001', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Döner Wrap (Dürüm)', 1399, true, '{
     "category": "Wraps", "description": "Shaved döner in thin lavash with tomato, onion, parsley and sumac.",
     "allergens": ["wheat", "milk"], "dietary": ["halal"],
     "name_i18n": {"zh": "土耳其烤肉卷", "ar": "دونر دورم (لفافة)", "fr": "Wrap döner (dürüm)"},
     "modifier_groups": [
       {"id":"meat","name":"Meat","required":true,"max_select":1,"options":[
         {"id":"chicken","name":"Chicken"},{"id":"beef","name":"Beef & lamb"},{"id":"mixed","name":"Mixed","price_cents":100}]},
       {"id":"sauce","name":"Sauces","max_select":2,"options":[
         {"id":"garlic","name":"Garlic yogurt"},{"id":"tahini","name":"Tahini"},{"id":"hot","name":"Hot sauce"}]}]}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000002', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Falafel Wrap', 1249, true, '{
     "category": "Wraps", "description": "Crispy chickpea falafel, hummus, pickles, salad.",
     "allergens": ["wheat", "sesame"], "dietary": ["vegan", "vegetarian", "halal"],
     "name_i18n": {"zh": "炸鹰嘴豆丸子卷", "ar": "لفافة فلافل", "fr": "Wrap falafel"}}'::jsonb),
  -- Plates
  ('e2e5ca02-0000-4000-8001-000000000003', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Döner Plate', 1899, true, '{
     "category": "Plates", "description": "Döner over rice or fries with salad, garlic sauce and pita.",
     "allergens": ["wheat", "milk"], "dietary": ["halal"],
     "name_i18n": {"zh": "土耳其烤肉盘", "ar": "طبق دونر", "fr": "Assiette döner"},
     "modifier_groups": [
       {"id":"meat","name":"Meat","required":true,"max_select":1,"options":[
         {"id":"chicken","name":"Chicken"},{"id":"beef","name":"Beef & lamb"},{"id":"mixed","name":"Mixed","price_cents":100}]},
       {"id":"base","name":"Base","required":true,"max_select":1,"options":[
         {"id":"rice","name":"Rice"},{"id":"fries","name":"Fries"},{"id":"half","name":"Half & half"}]}]}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000004', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Adana Kebab Plate', 2199, true, '{
     "category": "Plates", "description": "Hand-minced lamb skewer with red pepper, grilled over charcoal, bulgur, grilled tomato and pepper.",
     "allergens": ["wheat"], "dietary": ["halal"], "spice_level": 2,
     "guide": "Wrap pieces of kebab in the bread with onion and sumac salad.",
     "name_i18n": {"zh": "阿达纳烤肉串套餐", "ar": "طبق كباب أضنة", "fr": "Assiette kebab Adana"}}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000005', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Iskender Kebab', 2299, true, '{
     "category": "Plates", "description": "Döner over pide bread cubes, tomato sauce, browned butter, yogurt.",
     "allergens": ["wheat", "milk"], "dietary": ["halal"],
     "name_i18n": {"zh": "伊斯坎德尔烤肉", "ar": "إسكندر كباب", "fr": "Iskender kebab"}}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000006', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Mixed Grill Platter (for 2)', 4499, true, '{
     "category": "Plates", "description": "Adana, chicken shish, lamb chops and döner with rice, salad and bread. Serves two.",
     "allergens": ["wheat", "milk"], "dietary": ["halal"], "spice_level": 1,
     "name_i18n": {"zh": "双人烤肉拼盘", "ar": "مشاوي مشكلة (لشخصين)", "fr": "Grillades mixtes (pour 2)"}}'::jsonb),
  -- Pide & Lahmacun
  ('e2e5ca02-0000-4000-8001-000000000007', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Lahmacun', 999, true, '{
     "category": "Pide & Lahmacun", "description": "Thin crispy flatbread with spiced minced meat. Roll it with lemon and parsley.",
     "allergens": ["wheat"], "dietary": ["halal"], "spice_level": 1,
     "name_i18n": {"zh": "土耳其薄饼 Lahmacun", "ar": "لحم بعجين", "fr": "Lahmacun"}}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000008', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Cheese Pide', 1699, true, '{
     "category": "Pide & Lahmacun", "description": "Boat-shaped oven bread with kaşar cheese and egg.",
     "allergens": ["wheat", "milk", "egg"], "dietary": ["vegetarian", "halal"],
     "name_i18n": {"zh": "芝士船形饼", "ar": "بيدا بالجبن", "fr": "Pide au fromage"}}'::jsonb),
  -- Meze & Soup
  ('e2e5ca02-0000-4000-8001-000000000009', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Hummus', 899, true, '{
     "category": "Meze & Soup", "description": "Chickpeas, tahini, lemon, olive oil, with warm pita.",
     "allergens": ["sesame", "wheat"], "dietary": ["vegan", "vegetarian", "halal"],
     "name_i18n": {"zh": "鹰嘴豆泥", "ar": "حمص", "fr": "Houmous"}}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000010', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Shepherd Salad', 949, true, '{
     "category": "Meze & Soup", "description": "Tomato, cucumber, onion, parsley, lemon and olive oil.",
     "allergens": [], "dietary": ["vegan", "vegetarian", "gluten_free", "halal"],
     "name_i18n": {"zh": "牧羊人沙拉", "ar": "سلطة الراعي", "fr": "Salade du berger"}}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000011', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Red Lentil Soup', 699, true, '{
     "category": "Meze & Soup", "description": "Turkish mercimek soup with lemon.",
     "allergens": [], "dietary": ["vegan", "vegetarian", "gluten_free", "halal"],
     "name_i18n": {"zh": "红扁豆汤", "ar": "شوربة عدس", "fr": "Soupe aux lentilles rouges"}}'::jsonb),
  -- Desserts
  ('e2e5ca02-0000-4000-8001-000000000012', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Baklava (3 pcs)', 799, true, '{
     "category": "Desserts", "description": "Layers of filo, pistachio and syrup.",
     "allergens": ["wheat", "tree_nut", "milk"], "dietary": ["vegetarian", "halal"],
     "name_i18n": {"zh": "开心果千层酥（3块）", "ar": "بقلاوة (٣ قطع)", "fr": "Baklava (3 morceaux)"}}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000013', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Künefe', 1199, true, '{
     "category": "Desserts", "description": "Hot shredded pastry with melted cheese, syrup and pistachio.",
     "allergens": ["wheat", "milk", "tree_nut"], "dietary": ["vegetarian", "halal"],
     "name_i18n": {"zh": "库纳法芝士甜饼", "ar": "كنافة", "fr": "Künefe"}}'::jsonb),
  -- Drinks
  ('e2e5ca02-0000-4000-8001-000000000014', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Ayran', 349, true, '{
     "category": "Drinks", "description": "Salted yogurt drink.", "allergens": ["milk"], "dietary": ["vegetarian", "gluten_free", "halal"],
     "name_i18n": {"zh": "土耳其酸奶饮 Ayran", "ar": "عيران", "fr": "Ayran"}}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000015', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Turkish Tea', 249, true, '{
     "category": "Drinks", "allergens": [], "dietary": ["vegan", "gluten_free", "halal"],
     "name_i18n": {"zh": "土耳其红茶", "ar": "شاي تركي", "fr": "Thé turc"}}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000016', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Turkish Coffee', 449, true, '{
     "category": "Drinks", "allergens": [], "dietary": ["vegan", "gluten_free", "halal"],
     "name_i18n": {"zh": "土耳其咖啡", "ar": "قهوة تركية", "fr": "Café turc"}}'::jsonb),
  ('e2e5ca02-0000-4000-8001-000000000017', 'e2e5ca02-0000-4000-8000-000000000001', 'RETAIL', 'Coca-Cola', 299, true, '{
     "category": "Drinks", "allergens": [], "dietary": ["vegan", "gluten_free", "halal"],
     "name_i18n": {"zh": "可口可乐", "ar": "كوكاكولا"}}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
  org_id = EXCLUDED.org_id, vertical_type = EXCLUDED.vertical_type, name = EXCLUDED.name,
  price_cents = EXCLUDED.price_cents, is_active = EXCLUDED.is_active, attributes = EXCLUDED.attributes;
-- Sold out (set after the insert: the stock trigger logs a move that needs the product to exist).
UPDATE products SET attributes = attributes || '{"stock_qty": 0}'::jsonb WHERE id = 'e2e5ca02-0000-4000-8001-000000000013';

INSERT INTO dine.tables (id, org_id, name, area, seats, sort, is_active, qr_token) VALUES
  ('e2e5ca02-0000-4000-8002-000000000001', 'e2e5ca02-0000-4000-8000-000000000001', 'T1', 'Main', 4, 1, true, 'kbab0000000000000000000000000001'),
  ('e2e5ca02-0000-4000-8002-000000000002', 'e2e5ca02-0000-4000-8000-000000000001', 'T2', 'Main', 4, 2, true, 'kbab0000000000000000000000000002'),
  ('e2e5ca02-0000-4000-8002-000000000003', 'e2e5ca02-0000-4000-8000-000000000001', 'T3', 'Window', 2, 3, true, 'kbab0000000000000000000000000003'),
  ('e2e5ca02-0000-4000-8002-000000000004', 'e2e5ca02-0000-4000-8000-000000000001', 'F1', 'Family', 6, 4, true, 'kbab0000000000000000000000000004')
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name, area = EXCLUDED.area, seats = EXCLUDED.seats, sort = EXCLUDED.sort,
  is_active = EXCLUDED.is_active, qr_token = EXCLUDED.qr_token;

-- Terminal sign-in: PIN 1234 manager, 5678 server (same test hashes as seed.sql).
INSERT INTO staff (id, org_id, name, role, pin_salt, pin_hash, pin_iterations) VALUES
  ('e2e5ca02-0000-4000-8003-000000000001', 'e2e5ca02-0000-4000-8000-000000000001', 'Kebab Manager', 'MANAGER',
   'e2e5ca00e2e5ca00e2e5ca00e2e5ca00', 'de6528172d08e1fae0d3b7c1e5be5ceb895de7c06344b0020c002fb446d773e6', 100000),
  ('e2e5ca02-0000-4000-8003-000000000002', 'e2e5ca02-0000-4000-8000-000000000001', 'Kebab Server', 'CASHIER',
   'e2e5ca00e2e5ca00e2e5ca00e2e5ca00', '3e9797050219f10d9614c8a9a4daac3a9be9072bd824e390def4da45dec4cdda', 100000)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, role = EXCLUDED.role, pin_salt = EXCLUDED.pin_salt,
  pin_hash = EXCLUDED.pin_hash, pin_iterations = EXCLUDED.pin_iterations, is_active = true;

COMMIT;

-- Teardown: as in seed-western.sql, with org e2e5ca02-0000-4000-8000-000000000001.
