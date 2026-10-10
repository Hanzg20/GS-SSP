-- 2026-10-09: transactions.subtotal_cents / tax_cents / tip_cents -- Iris
-- (counter checkout) sends the sale's breakdown alongside `amount` (the total
-- charged), but the columns were never created, so every Iris insert failed
-- with "Could not find the 'subtotal_cents' column" (seen on a WizarPOS Q2).
-- Nullable: unattended (wash/timer) rows have no breakdown and omit them.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS subtotal_cents INTEGER;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS tax_cents INTEGER;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS tip_cents INTEGER;
NOTIFY pgrst, 'reload schema';
