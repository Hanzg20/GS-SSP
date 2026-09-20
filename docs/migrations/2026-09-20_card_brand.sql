-- 2026-09-20: keep the card brand the terminal reports (VISA, MASTERCARD, CUP...) next to
-- card_aid/card_bin. Idempotent.
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS card_brand TEXT;
