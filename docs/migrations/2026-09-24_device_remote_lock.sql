-- 2026-09-24: make a terminal's remote LOCK state visible in CMP.
-- The lock lived only in the terminal's own SharedPreferences
-- (DeviceAccessManager), so CMP could send LOCK but had no way to show
-- whether a terminal was actually locked -- and no UNLOCK button either.
-- The terminal now writes this column itself after every LOCK/UNLOCK and
-- at startup (devices already allows device-side UPDATE, see
-- "devices can update own row").
-- Idempotent; run once against the live database.

ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS remote_locked BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS remote_lock_changed_at TIMESTAMPTZ;
