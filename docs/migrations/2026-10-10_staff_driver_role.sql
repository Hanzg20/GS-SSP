-- 2026-10-10: staff role DRIVER -- delivery drivers: on Iris they only get the
-- DELIVERY tab (collect assigned/unassigned orders by card or cash), not the
-- keypad, so a driver can't charge an arbitrary amount; cash they take is
-- attributed to them by transactions.staff_id for the end-of-day count.
ALTER TABLE staff DROP CONSTRAINT IF EXISTS staff_role_check;
ALTER TABLE staff ADD CONSTRAINT staff_role_check CHECK (role IN ('CASHIER', 'MANAGER', 'DRIVER'));
