-- Demo dish photos for the E2E store (web/ourea-scan/public/demo, Wikimedia Commons -- see CREDITS.txt there).
-- Served by the scan site itself, so they show once that site is deployed. Safe to run again.
--   psql "$DB" -v ON_ERROR_STOP=1 -f tools/scan-e2e/demo-photos.sql
BEGIN;
UPDATE products p SET attributes = p.attributes || jsonb_build_object('image_url', 'https://scan-order.si-sky.com/demo/' || v.file || '?v=2')
  FROM (VALUES ('拍黄瓜%', 'cucumber.jpg'), ('炸酱面%', 'zhajiang.jpg'), ('红烧牛肉面%', 'beefnoodle.jpg'),
               ('小笼包%', 'xiaolongbao.jpg'), ('猪肉白菜饺子%', 'pork_jiaozi.jpg'), ('素三鲜饺子%', 'veg_jiaozi.jpg'),
               ('可乐%', 'cola.jpg'), ('瓶装水%', 'water.jpg')) AS v(pattern, file)
 WHERE p.org_id = 'e2e5ca00-0000-4000-8000-000000000001' AND p.name LIKE v.pattern;
COMMIT;
