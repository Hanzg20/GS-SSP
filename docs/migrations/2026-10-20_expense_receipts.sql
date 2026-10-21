-- 2026-10-20: a receipt photo behind an expense, and the HST paid on it -- the Journal (手账).
--
-- One photo per receipt: a long receipt is folded so its head (store, date, HST number)
-- meets its foot (total, tax) -- no stitching, no line items (owner, 2026-10-20). The
-- terminal reads the store, date, total and tax from it; the owner confirms before saving.
--
--   expenses.tax_cents      GST/HST paid, for input tax credits (NULL = not recorded)
--   expenses.receipt_path   the photo in storage bucket `receipts`: <org_id>/<yyyy-MM>/<expense id>.jpg
--   expenses.receipt_text   what the terminal read off it (search later; never edited)
--
-- Bucket `receipts` is private. A terminal uploads and reads only its own store's folder
-- and can neither replace nor delete a photo: receipts are tax records (CRA: keep 6 years).
-- Org members read them in Cael. expenses stays in `public` for now (database_design.md §2,
-- moves to `office` later). Idempotent; one transaction.

BEGIN;

ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS tax_cents    INTEGER CHECK (tax_cents IS NULL OR tax_cents >= 0);
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS receipt_path TEXT CHECK (receipt_path IS NULL OR char_length(receipt_path) <= 200);
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS receipt_text TEXT CHECK (receipt_text IS NULL OR char_length(receipt_text) <= 20000);
COMMENT ON COLUMN public.expenses.tax_cents    IS 'GST/HST paid on the purchase (input tax credits); NULL = not recorded';
COMMENT ON COLUMN public.expenses.receipt_path IS 'Receipt photo in storage bucket receipts: <org_id>/<yyyy-MM>/<expense id>.jpg';
COMMENT ON COLUMN public.expenses.receipt_text IS 'Text the terminal read off the receipt photo (OCR), as read';

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('receipts', 'receipts', false, 3145728, ARRAY['image/jpeg'])
ON CONFLICT (id) DO UPDATE SET public = false, file_size_limit = 3145728, allowed_mime_types = ARRAY['image/jpeg'];

-- Terminals: upload into their own store's folder only. No UPDATE / DELETE policy:
-- a stored receipt can't be overwritten or removed from a terminal.
DROP POLICY IF EXISTS "Terminals upload their store's receipts" ON storage.objects;
CREATE POLICY "Terminals upload their store's receipts" ON storage.objects
  FOR INSERT TO authenticated
  WITH CHECK (
    bucket_id = 'receipts'
    AND (storage.foldername(name))[1] IN (SELECT org_id::text FROM device_auth_map WHERE auth_user_id = auth.uid() AND org_id IS NOT NULL)
  );

DROP POLICY IF EXISTS "Terminals read their store's receipts" ON storage.objects;
CREATE POLICY "Terminals read their store's receipts" ON storage.objects
  FOR SELECT TO authenticated
  USING (
    bucket_id = 'receipts'
    AND (storage.foldername(name))[1] IN (SELECT org_id::text FROM device_auth_map WHERE auth_user_id = auth.uid() AND org_id IS NOT NULL)
  );

-- Cael: members of the store read its receipts; system admins read all.
DROP POLICY IF EXISTS "Members read their org's receipts" ON storage.objects;
CREATE POLICY "Members read their org's receipts" ON storage.objects
  FOR SELECT TO authenticated
  USING (
    bucket_id = 'receipts'
    AND ((storage.foldername(name))[1] IN (SELECT id::text FROM member_org_ids() AS id) OR is_sys_admin())
  );

COMMIT;
