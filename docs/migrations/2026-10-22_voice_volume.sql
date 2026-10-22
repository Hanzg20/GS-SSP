-- 2026-10-22: the voice prompt volume, reported with each heartbeat. Customers at
-- Eagleson said the voice was too quiet; 255 sat at 10/15 and nobody could see
-- 253's from Cael. Wash 1.1.7 / Timer 1.2.6 also hold it at full (VoiceVolume),
-- so a value below max here means the terminal refused (Do Not Disturb, a
-- fixed-volume device).
-- NULL = not reported (a build before Wash 1.1.7 / Timer 1.2.6).
-- Those builds resend a heartbeat without the volume while these columns are
-- missing (PostgREST rejects unknown columns), so the order does not matter.
BEGIN;

ALTER TABLE public.heartbeats ADD COLUMN IF NOT EXISTS voice_volume SMALLINT;
ALTER TABLE public.heartbeats ADD COLUMN IF NOT EXISTS voice_volume_max SMALLINT;
COMMENT ON COLUMN public.heartbeats.voice_volume IS
    'Voice prompt (media stream) volume when this heartbeat was sent; NULL = older build.';
COMMENT ON COLUMN public.heartbeats.voice_volume_max IS
    'Max of that stream on this terminal (15 on a Q3mini).';

ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS voice_volume SMALLINT;
ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS voice_volume_max SMALLINT;
COMMENT ON COLUMN public.devices.voice_volume IS
    'Last reported voice prompt volume (see voice_volume_max). Set from heartbeats.';
COMMENT ON COLUMN public.devices.voice_volume_max IS
    'Last reported max of the voice prompt stream.';

-- Same trigger as 2026-10-21_kiosk_in_front.sql, plus the volume when the heartbeat carries one.
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
      kiosk_front_reported_at = CASE WHEN NEW.kiosk_in_front IS NULL THEN kiosk_front_reported_at ELSE now() END,
      voice_volume = COALESCE(NEW.voice_volume, voice_volume),
      voice_volume_max = COALESCE(NEW.voice_volume_max, voice_volume_max)
  WHERE sn = NEW.device_sn;
  RETURN NEW;
END;
$function$;

COMMIT;
