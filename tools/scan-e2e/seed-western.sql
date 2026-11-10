-- Ourea Scan scenario store: a Canadian casual-dining Western restaurant (burgers, steaks, pasta,
-- poutine), for trying Beaver on a non-Chinese menu (owner 2026-11-09). Test store, "ZZ" name,
-- generic dishes -- not any real restaurant's menu. Safe to run again; teardown at the bottom.
--
--   psql "$DB" -v ON_ERROR_STOP=1 -f tools/scan-e2e/seed-western.sql
--   QR: <site>/?t=west00000000000000000000000000NN  (NN = 01-04)
--
-- What it exercises: required doneness and side choices, optional add-ons with prices, full
-- allergen / diet data (gluten-free, vegetarian, vegan), a sold-out dessert, a kids dish, alcohol,
-- English names with French and Chinese translations (Ottawa is bilingual).

BEGIN;

INSERT INTO organizations (id, name)
VALUES ('e2e5ca01-0000-4000-8000-000000000001', 'ZZ 西餐测试 Western Grill')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO locations (id, org_id, name, timezone)
VALUES ('e2e5ca01-0000-4000-8000-000000000002', 'e2e5ca01-0000-4000-8000-000000000001', 'Western Grill (test)', 'America/Toronto')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, timezone = EXCLUDED.timezone;

-- Shared option groups, written out per dish (the menu keeps them in attributes.modifier_groups).
INSERT INTO products (id, org_id, vertical_type, name, price_cents, is_active, attributes) VALUES
  -- Appetizers
  ('e2e5ca01-0000-4000-8001-000000000001', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Crispy Calamari', 1699, true, '{
     "category": "Appetizers", "description": "Lightly breaded squid, lemon, chipotle mayo.",
     "allergens": ["mollusc", "wheat", "egg"], "dietary": [],
     "name_i18n": {"zh": "酥炸鱿鱼圈", "fr": "Calmars croustillants"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000002', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Spinach & Artichoke Dip', 1549, true, '{
     "category": "Appetizers", "description": "Warm, cheesy, with tortilla chips. Great to share.",
     "allergens": ["milk", "wheat"], "dietary": ["vegetarian"],
     "name_i18n": {"zh": "菠菜洋蓟热蘸酱", "fr": "Trempette épinards et artichauts"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000003', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Chicken Wings (1 lb)', 1799, true, '{
     "category": "Appetizers", "description": "Crispy wings, celery, blue cheese dip.",
     "allergens": ["milk"], "dietary": ["gluten_free"], "spice_level": 1,
     "name_i18n": {"zh": "鸡翅（1磅）", "fr": "Ailes de poulet (1 lb)"},
     "modifier_groups": [{"id":"sauce","name":"Sauce","required":true,"max_select":1,"options":[
       {"id":"honey","name":"Honey garlic"},{"id":"buffalo","name":"Buffalo (hot)"},{"id":"saltpep","name":"Salt & pepper"}]}]}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000004', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Poutine', 1199, true, '{
     "category": "Appetizers", "description": "Fries, Quebec cheese curds, rich gravy.",
     "ingredients": "potatoes, cheese curds, beef gravy", "allergens": ["milk", "wheat"], "dietary": [],
     "guide": "A Canadian classic from Quebec: eat it hot with a fork, before the curds melt.",
     "name_i18n": {"zh": "肉汁奶酪薯条 Poutine", "fr": "Poutine"}}'::jsonb),
  -- Soups & Salads
  ('e2e5ca01-0000-4000-8001-000000000005', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'French Onion Soup', 949, true, '{
     "category": "Soups & Salads", "description": "Caramelized onions, beef broth, baked Gruyère crouton.",
     "allergens": ["milk", "wheat"], "dietary": [],
     "name_i18n": {"zh": "法式洋葱汤", "fr": "Soupe à l''oignon gratinée"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000006', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Caesar Salad', 1399, true, '{
     "category": "Soups & Salads", "description": "Romaine, parmesan, croutons, classic Caesar dressing.",
     "allergens": ["milk", "egg", "fish", "wheat"], "dietary": [],
     "name_i18n": {"zh": "凯撒沙拉", "fr": "Salade César"},
     "modifier_groups": [{"id":"protein","name":"Add protein","max_select":1,"options":[
       {"id":"chicken","name":"Grilled chicken","price_cents":600},{"id":"shrimp","name":"Garlic shrimp","price_cents":700}]}]}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000007', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Quinoa Power Bowl', 1749, true, '{
     "category": "Soups & Salads", "description": "Quinoa, roasted squash, kale, avocado, tahini dressing.",
     "allergens": ["sesame"], "dietary": ["vegan", "vegetarian", "gluten_free"],
     "name_i18n": {"zh": "藜麦能量碗", "fr": "Bol de quinoa"}}'::jsonb),
  -- Mains
  ('e2e5ca01-0000-4000-8001-000000000008', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Classic Burger', 1999, true, '{
     "category": "Mains", "description": "8 oz beef patty, cheddar, lettuce, tomato, house sauce, brioche bun.",
     "allergens": ["wheat", "milk", "egg", "sesame"], "dietary": [],
     "name_i18n": {"zh": "经典牛肉汉堡", "fr": "Burger classique"},
     "modifier_groups": [
       {"id":"side","name":"Side","required":true,"max_select":1,"options":[
         {"id":"fries","name":"Fries"},{"id":"salad","name":"Green salad"},{"id":"sweet","name":"Sweet potato fries","price_cents":200}]},
       {"id":"extras","name":"Extras","max_select":3,"options":[
         {"id":"bacon","name":"Bacon","price_cents":250},{"id":"avocado","name":"Avocado","price_cents":200},{"id":"gfbun","name":"Gluten-free bun","price_cents":200}]}]}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000009', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Steak Frites (8 oz striploin)', 3499, true, '{
     "category": "Mains", "description": "AAA striploin, peppercorn sauce, fries.",
     "allergens": ["milk"], "dietary": ["gluten_free"],
     "name_i18n": {"zh": "西冷牛排配薯条（8盎司）", "fr": "Steak frites (contre-filet 8 oz)"},
     "modifier_groups": [
       {"id":"done","name":"Doneness","required":true,"max_select":1,"options":[
         {"id":"rare","name":"Rare"},{"id":"mrare","name":"Medium rare"},{"id":"medium","name":"Medium"},{"id":"mwell","name":"Medium well"},{"id":"well","name":"Well done"}]},
       {"id":"sside","name":"Side","required":true,"max_select":1,"options":[
         {"id":"fries","name":"Fries"},{"id":"mash","name":"Garlic mash"},{"id":"veg","name":"Seasonal vegetables"}]}]}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000010', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Atlantic Salmon', 2999, true, '{
     "category": "Mains", "description": "Grilled salmon, lemon butter, rice pilaf, green beans.",
     "allergens": ["fish", "milk"], "dietary": ["gluten_free"],
     "name_i18n": {"zh": "烤大西洋三文鱼", "fr": "Saumon de l''Atlantique"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000011', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Chicken Fettuccine Alfredo', 2499, true, '{
     "category": "Mains", "description": "Creamy parmesan sauce, grilled chicken, garlic bread.",
     "allergens": ["wheat", "milk", "egg"], "dietary": [],
     "name_i18n": {"zh": "鸡肉奶油宽面", "fr": "Fettuccine Alfredo au poulet"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000012', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Fish & Chips', 2199, true, '{
     "category": "Mains", "description": "Beer-battered haddock, fries, coleslaw, tartar sauce.",
     "allergens": ["fish", "wheat", "egg"], "dietary": [],
     "name_i18n": {"zh": "炸鱼薯条", "fr": "Poisson-frites"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000013', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Wild Mushroom Risotto', 2249, true, '{
     "category": "Mains", "description": "Arborio rice, wild mushrooms, parmesan, truffle oil.",
     "allergens": ["milk"], "dietary": ["vegetarian", "gluten_free"],
     "name_i18n": {"zh": "野菌烩饭", "fr": "Risotto aux champignons sauvages"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000014', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Kids Mac & Cheese', 999, true, '{
     "category": "Kids", "description": "For 12 and under. Comes with apple slices.",
     "allergens": ["wheat", "milk"], "dietary": ["vegetarian"],
     "name_i18n": {"zh": "儿童芝士通心粉", "fr": "Macaroni au fromage (enfants)"}}'::jsonb),
  -- Desserts
  ('e2e5ca01-0000-4000-8001-000000000015', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Chocolate Lava Cake', 1099, true, '{
     "category": "Desserts", "description": "Warm molten centre, vanilla ice cream.",
     "allergens": ["wheat", "milk", "egg"], "dietary": ["vegetarian"],
     "name_i18n": {"zh": "熔岩巧克力蛋糕", "fr": "Gâteau fondant au chocolat"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000016', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Crème Brûlée', 949, true, '{
     "category": "Desserts", "description": "Vanilla custard, burnt sugar top.",
     "allergens": ["milk", "egg"], "dietary": ["vegetarian", "gluten_free"],
     "name_i18n": {"zh": "法式焦糖布丁", "fr": "Crème brûlée"}}'::jsonb),
  -- Drinks
  ('e2e5ca01-0000-4000-8001-000000000017', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Coca-Cola', 349, true, '{
     "category": "Drinks", "allergens": [], "dietary": ["vegan", "gluten_free"], "name_i18n": {"zh": "可口可乐"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000018', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Fresh Lemonade', 449, true, '{
     "category": "Drinks", "allergens": [], "dietary": ["vegan", "gluten_free"], "name_i18n": {"zh": "鲜榨柠檬水", "fr": "Limonade maison"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000019', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Local Craft Lager (16 oz)', 849, true, '{
     "category": "Drinks", "description": "Ontario lager. 19+ only, ID may be asked.", "allergens": ["wheat"], "dietary": ["vegan"],
     "name_i18n": {"zh": "本地精酿啤酒（16盎司）", "fr": "Lager artisanale locale (16 oz)"}}'::jsonb),
  ('e2e5ca01-0000-4000-8001-000000000020', 'e2e5ca01-0000-4000-8000-000000000001', 'RETAIL', 'Coffee', 329, true, '{
     "category": "Drinks", "allergens": [], "dietary": ["vegan", "gluten_free"], "name_i18n": {"zh": "咖啡", "fr": "Café"}}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
  org_id = EXCLUDED.org_id, vertical_type = EXCLUDED.vertical_type, name = EXCLUDED.name,
  price_cents = EXCLUDED.price_cents, is_active = EXCLUDED.is_active, attributes = EXCLUDED.attributes;
-- Sold out (set after the insert: the stock trigger logs a move that needs the product to exist).
UPDATE products SET attributes = attributes || '{"stock_qty": 0}'::jsonb WHERE id = 'e2e5ca01-0000-4000-8001-000000000016';

INSERT INTO dine.tables (id, org_id, name, area, seats, sort, is_active, qr_token) VALUES
  ('e2e5ca01-0000-4000-8002-000000000001', 'e2e5ca01-0000-4000-8000-000000000001', 'T1', 'Dining room', 4, 1, true, 'west0000000000000000000000000001'),
  ('e2e5ca01-0000-4000-8002-000000000002', 'e2e5ca01-0000-4000-8000-000000000001', 'T2', 'Dining room', 4, 2, true, 'west0000000000000000000000000002'),
  ('e2e5ca01-0000-4000-8002-000000000003', 'e2e5ca01-0000-4000-8000-000000000001', 'T3', 'Patio', 2, 3, true, 'west0000000000000000000000000003'),
  ('e2e5ca01-0000-4000-8002-000000000004', 'e2e5ca01-0000-4000-8000-000000000001', 'B1', 'Booth', 6, 4, true, 'west0000000000000000000000000004')
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name, area = EXCLUDED.area, seats = EXCLUDED.seats, sort = EXCLUDED.sort,
  is_active = EXCLUDED.is_active, qr_token = EXCLUDED.qr_token;

-- Terminal sign-in: PIN 1234 manager, 5678 server (same test hashes as seed.sql).
INSERT INTO staff (id, org_id, name, role, pin_salt, pin_hash, pin_iterations) VALUES
  ('e2e5ca01-0000-4000-8003-000000000001', 'e2e5ca01-0000-4000-8000-000000000001', 'Grill Manager', 'MANAGER',
   'e2e5ca00e2e5ca00e2e5ca00e2e5ca00', 'de6528172d08e1fae0d3b7c1e5be5ceb895de7c06344b0020c002fb446d773e6', 100000),
  ('e2e5ca01-0000-4000-8003-000000000002', 'e2e5ca01-0000-4000-8000-000000000001', 'Grill Server', 'CASHIER',
   'e2e5ca00e2e5ca00e2e5ca00e2e5ca00', '3e9797050219f10d9614c8a9a4daac3a9be9072bd824e390def4da45dec4cdda', 100000)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, role = EXCLUDED.role, pin_salt = EXCLUDED.pin_salt,
  pin_hash = EXCLUDED.pin_hash, pin_iterations = EXCLUDED.pin_iterations, is_active = true;

COMMIT;

-- Teardown (whole store; card transactions are kept):
-- DELETE FROM dine.scan_cart_items WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001';
-- DELETE FROM dine.scan_orders WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001';
-- DELETE FROM dine.scan_chats WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001';
-- DELETE FROM dine.service_calls WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001';
-- DELETE FROM dine.payments WHERE check_id IN (SELECT id FROM dine.checks WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001');
-- DELETE FROM dine.check_items WHERE check_id IN (SELECT id FROM dine.checks WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001');
-- DELETE FROM dine.checks WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001';
-- DELETE FROM dine.tables WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001';
-- DELETE FROM staff WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001';
-- DELETE FROM products WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001';
-- DELETE FROM locations WHERE org_id = 'e2e5ca01-0000-4000-8000-000000000001';
-- DELETE FROM organizations WHERE id = 'e2e5ca01-0000-4000-8000-000000000001';
