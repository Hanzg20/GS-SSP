-- 2026-10-08: devices.payment_test_enabled -- the technician payment test-case
-- runner (PaymentTestActivity / PaymentTestSuite) only runs on a terminal
-- flagged here: its case amounts ($100, $9,999,999.99, 90.01-90.05) are real
-- charges on a production host. Set it only on a UAT-configured terminal.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS payment_test_enabled BOOLEAN NOT NULL DEFAULT false;
