-- 2026-10-21: whether the kiosk screen (Aegis Wash / Timer, Ourea Eye) is in
-- front, reported with each heartbeat. 253 sat on the PAYWizard (OPC) page
-- after reboots and nobody could tell from Cael; BootFrontGuard only covers the
-- first minutes after a boot.
-- NULL = not reported (an attended app, or a build before Wash 1.1.6 / Timer 1.2.5).
-- The terminal also sends a heartbeat when the state changes (covered > 2 min,
-- or back in front), so the value is never older than the last change.
BEGIN;

ALTER TABLE public.heartbeats ADD COLUMN IF NOT EXISTS kiosk_in_front BOOLEAN;
COMMENT ON COLUMN public.heartbeats.kiosk_in_front IS
    'Kiosk screen in front when this heartbeat was sent; NULL = not a kiosk app or an older build.';

ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS kiosk_in_front BOOLEAN;
ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS kiosk_front_reported_at TIMESTAMPTZ;
COMMENT ON COLUMN public.devices.kiosk_in_front IS
    'Last reported: kiosk screen in front (false = another app, e.g. PAYWizard/OPC, covers it). Set from heartbeats.';
COMMENT ON COLUMN public.devices.kiosk_front_reported_at IS
    'When kiosk_in_front was last reported.';

-- Same trigger as before, plus the kiosk state when the heartbeat carries one.
CREATE OR REPLACE FUNCTION public.handle_heartbeat_sync()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path = public
AS $function$
BEGIN
  UPDATE public.devices
  SET last_seen = now(),
      status = 'ONLINE',
      kiosk_in_front = COALESCE(NEW.kiosk_in_front, kiosk_in_front),
      kiosk_front_reported_at = CASE WHEN NEW.kiosk_in_front IS NULL THEN kiosk_front_reported_at ELSE now() END
  WHERE sn = NEW.device_sn;
  RETURN NEW;
END;
$function$;

COMMIT;
