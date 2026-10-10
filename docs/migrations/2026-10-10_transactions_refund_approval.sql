-- 2026-10-10: who approved a refund on an attended terminal, and when.
-- A whole-sale refund keeps the existing model (the sale's payment_status goes
-- VOIDED / REFUNDED, which CMP's reports already exclude); these columns add
-- the manager whose PIN authorised it (cashier asks, manager approves on the
-- spot) and the time it happened.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS refunded_at TIMESTAMPTZ;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS refund_approved_by_staff_id UUID REFERENCES staff(id) ON DELETE SET NULL;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS refund_approved_by_name TEXT;
NOTIFY pgrst, 'reload schema';
