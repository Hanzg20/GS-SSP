-- 2026-10-07: organizations.support_phone -- the number customers text when
-- the photo of their VIP pass won't scan (printed on the pass, terminal and
-- CMP). Published to terminals as branding.support_phone.
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS support_phone TEXT;

-- Eagleson: the number already in its welcome message.
UPDATE organizations SET support_phone = '(343) 777-9666'
WHERE id = '3c8e86c7-4c6d-4d1a-aa15-90aeed7aae76' AND support_phone IS NULL;
