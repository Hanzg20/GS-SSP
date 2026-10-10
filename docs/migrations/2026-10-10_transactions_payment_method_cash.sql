-- 2026-10-10: allow payment_method 'CASH'. Iris records cash taken at the door
-- for a delivery order (and Ourea's cash button always sent CASH): the check
-- constraint from 2026-09-20_card_type.sql rejected every one of those rows.
ALTER TABLE public.transactions DROP CONSTRAINT IF EXISTS transactions_payment_method_check;
ALTER TABLE public.transactions ADD CONSTRAINT transactions_payment_method_check
    CHECK (payment_method IN ('CREDIT_CARD', 'DEBIT_CARD', 'VIP_CARD', 'QR_CODE', 'COUPON', 'CASH'));
