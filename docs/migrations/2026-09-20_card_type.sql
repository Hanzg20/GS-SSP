-- 2026-09-20: distinguish debit from credit on card payments.
-- Idempotent; run once against the live database.

ALTER TABLE public.transactions DROP CONSTRAINT IF EXISTS transactions_payment_method_check;
ALTER TABLE public.transactions ADD CONSTRAINT transactions_payment_method_check
    CHECK (payment_method IN ('CREDIT_CARD', 'DEBIT_CARD', 'VIP_CARD', 'QR_CODE', 'COUPON'));

-- EMV AID and first 6 PAN digits (BIN; never the full PAN), kept so cards can be
-- re-classified later if the terminal doesn't report credit/debit itself.
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS card_aid TEXT;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS card_bin TEXT;
