-- 2026-11-08 (owner): each store words its own coupon terms, printed under the QR code of its
-- coupon tickets in Cael (e.g. Eagleson: which bay and vacuums take them). Edited in Cael's
-- Merchant Profile; blank = the ticket shows the general how-to. Up to 300 characters, the
-- ticket grows to show all of it.

BEGIN;

ALTER TABLE public.organizations ADD COLUMN IF NOT EXISTS coupon_terms TEXT;
ALTER TABLE public.organizations DROP CONSTRAINT IF EXISTS organizations_coupon_terms_len;
ALTER TABLE public.organizations ADD CONSTRAINT organizations_coupon_terms_len
  CHECK (coupon_terms IS NULL OR char_length(coupon_terms) <= 300);
COMMENT ON COLUMN public.organizations.coupon_terms IS
  'Printed under the QR code of the store''s coupon tickets (Cael). NULL = general how-to. 2026-11-08.';

-- Eagleson (owner 2026-11-08): coupons work at bay 5 and vacuums 1 & 2.
UPDATE public.organizations
   SET coupon_terms = 'Valid at Bay 5 and Vacuum 1 & 2 only.'
 WHERE id = '3c8e86c7-4c6d-4d1a-aa15-90aeed7aae76' AND coupon_terms IS NULL;

COMMIT;
