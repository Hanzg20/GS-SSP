-- 2026-10-09: products.barcode -- Iris's Product model and local Room table
-- already carry `barcode` and scan-to-cart looks products up by it, but the
-- column never existed, so every synced product had a null barcode and no
-- scan could ever match. Unique per org (two products in one store can't share
-- a code); org-less shared products are unconstrained.
ALTER TABLE products ADD COLUMN IF NOT EXISTS barcode TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS products_org_barcode_key
  ON products (org_id, barcode) WHERE barcode IS NOT NULL AND org_id IS NOT NULL;
NOTIFY pgrst, 'reload schema';
