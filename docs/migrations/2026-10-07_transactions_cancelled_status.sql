-- 2026-10-07: CANCELLED payment status.
--
-- A card sale the customer cancelled or let time out on the payment screen
-- (PAYWizard RespCode -139 "cancelled by user") was recorded as DECLINED,
-- which reads as "the bank refused the card". CANCELLED keeps the two apart.
-- When no card was read, payment_method is left NULL (card type unknown)
-- instead of the provisional CREDIT_CARD the PENDING row starts with.

ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_payment_status_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_payment_status_check
    CHECK (payment_status IN ('PENDING', 'PAID', 'DECLINED', 'CANCELLED', 'VOIDED', 'REFUNDED'));
