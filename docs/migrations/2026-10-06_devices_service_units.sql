-- 2026-10-06: how many independently sold units a terminal drives, reported
-- by the terminal itself (Aegis Timer: 1 = single, 2 = dual-unit "一拖二";
-- set by the technician on the terminal). CMP uses it for remote start:
-- a dual terminal needs unit 1 or 2, a single one no unit at all.
-- NULL = not reported (other apps, or a Timer build before 1.1.2).
ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS service_units SMALLINT;
ALTER TABLE public.devices DROP CONSTRAINT IF EXISTS devices_service_units_check;
ALTER TABLE public.devices ADD CONSTRAINT devices_service_units_check
    CHECK (service_units IS NULL OR service_units IN (1, 2));
