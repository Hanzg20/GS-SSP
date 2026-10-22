-- 2026-10-22: public.devices grants SELECT per column (2026-10-13_devices_rls_lockdown.sql),
-- so a new column is invisible to Cael until granted -- and selecting it fails the whole
-- device list with 42501 (Cael showed "Failed to load devices" after
-- 2026-10-21_kiosk_in_front.sql and 2026-10-22_voice_volume.sql). Read-only: the terminal
-- writes these through the heartbeat trigger, nobody through the API.
BEGIN;

GRANT SELECT (kiosk_in_front, kiosk_front_reported_at, voice_volume, voice_volume_max)
    ON public.devices TO authenticated;

COMMIT;
