-- 2026-10-14: a terminal proves who it is with a per-device secret.
--
-- sync_device_identity(p_sn) linked the caller's session to ANY serial number
-- it named: any anonymous session that knew (or guessed) a terminal's SN became
-- that terminal for every device-scoped RLS policy -- its store's staff PIN
-- hashes, catalog, dine floor, writing transactions in its name. Serials are
-- printed on the hardware and some are shared defaults ("unknown").
--
-- Now: the first terminal to link a device after this migration is handed a
-- random secret (trust on first use) -- every shipped app already sends the
-- secret it holds (p_secret_key) and keeps the one returned (DeviceRepository,
-- since 2026-09-18), it just never got one. From then on linking needs that
-- secret. A wrong or missing one is refused and recorded on the device
-- (identity_rejected_at / identity_rejections), which Cael shows; a sys admin
-- resets it there (cmp_reset_device_identity) after a reinstall / data wipe,
-- and the next terminal to link claims it again.
--
-- Only a SHA-256 of the secret is stored, in device_secrets, which no API role
-- can read. Existing device_auth_map links are left alone: dropping them would
-- cut live terminals off from their RLS until their next sync (card payments
-- recorded as PENDING, seen 2026-09-20). A reset drops them for that device.

BEGIN;

CREATE TABLE IF NOT EXISTS public.device_secrets (
  device_sn    TEXT PRIMARY KEY REFERENCES public.devices(sn) ON DELETE CASCADE ON UPDATE CASCADE,
  secret_hash  TEXT NOT NULL,
  claimed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_by   UUID
);
COMMENT ON TABLE public.device_secrets IS 'SHA-256 of each terminal''s identity secret (sync_device_identity). No API access.';
ALTER TABLE public.device_secrets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.device_secrets FROM public, anon, authenticated;

ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS identity_claimed_at  TIMESTAMPTZ;
ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS identity_rejected_at TIMESTAMPTZ;
ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS identity_rejections  INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN public.devices.identity_rejected_at IS 'Last time a session tried to link to this device without its secret (reinstall, or someone else)';
GRANT SELECT (identity_claimed_at, identity_rejected_at, identity_rejections) ON public.devices TO authenticated;

-- Replaces sync_device_identity(text): with both signatures PostgREST couldn't pick one for a p_sn-only call.
DROP FUNCTION IF EXISTS public.sync_device_identity(TEXT);

CREATE OR REPLACE FUNCTION public.sync_device_identity(p_sn TEXT, p_secret_key TEXT DEFAULT NULL)
RETURNS JSON LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_dev    devices%ROWTYPE;
  v_hash   TEXT;
  v_owner  UUID;
  v_secret TEXT;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;
  SELECT * INTO v_dev FROM devices WHERE sn = p_sn FOR UPDATE;
  IF NOT FOUND THEN
    RETURN json_build_object('success', false, 'message', 'device_not_registered');
  END IF;

  SELECT secret_hash, claimed_by INTO v_hash, v_owner FROM device_secrets WHERE device_sn = p_sn;
  IF v_hash IS NULL THEN
    -- First link since the secret existed (or since a reset): this terminal gets it.
    v_secret := encode(extensions.gen_random_bytes(32), 'hex');
    INSERT INTO device_secrets (device_sn, secret_hash, claimed_by)
    VALUES (p_sn, encode(extensions.digest(v_secret, 'sha256'), 'hex'), auth.uid());
    UPDATE devices SET identity_claimed_at = now() WHERE sn = p_sn;
  ELSIF v_owner = auth.uid() AND p_secret_key IS NULL THEN
    -- The session that just claimed it, from a call sent before the secret came back
    -- (a terminal syncs from several places at start-up). It already has the secret.
    v_secret := NULL;
  ELSIF p_secret_key IS NULL OR encode(extensions.digest(p_secret_key, 'sha256'), 'hex') <> v_hash THEN
    UPDATE devices SET identity_rejected_at = now(), identity_rejections = identity_rejections + 1 WHERE sn = p_sn;
    RETURN json_build_object('success', false, 'message', 'invalid_secret');
  ELSE
    v_secret := p_secret_key;
  END IF;

  INSERT INTO device_auth_map (auth_user_id, device_sn, org_id)
  VALUES (auth.uid(), p_sn, v_dev.org_id)
  ON CONFLICT (auth_user_id) DO UPDATE SET device_sn = excluded.device_sn, org_id = excluded.org_id;

  RETURN json_build_object('success', true, 'org_id', v_dev.org_id, 'is_active', v_dev.is_active, 'secret_key', v_secret);
END $$;

REVOKE ALL ON FUNCTION public.sync_device_identity(TEXT, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.sync_device_identity(TEXT, TEXT) TO authenticated;

-- Sys admin, Cael: forget a device's secret (the terminal was reinstalled or wiped, or
-- someone else claimed it first) and unlink every session linked to it. The next
-- terminal to link claims it.
CREATE OR REPLACE FUNCTION public.cmp_reset_device_identity(p_sn TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_dev devices%ROWTYPE; v_links INT;
BEGIN
  IF NOT is_sys_admin() THEN RETURN jsonb_build_object('success', false, 'message', 'not_allowed'); END IF;
  SELECT * INTO v_dev FROM devices WHERE sn = p_sn FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_found'); END IF;
  DELETE FROM device_secrets WHERE device_sn = p_sn;
  DELETE FROM device_auth_map WHERE device_sn = p_sn;
  GET DIAGNOSTICS v_links = ROW_COUNT;
  UPDATE devices SET identity_claimed_at = NULL, identity_rejected_at = NULL, identity_rejections = 0 WHERE sn = p_sn;
  INSERT INTO audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), v_dev.org_id, 'device.identity_reset', 'devices', p_sn,
          jsonb_build_object('unlinked_sessions', v_links, 'rejections', v_dev.identity_rejections));
  RETURN jsonb_build_object('success', true, 'unlinked_sessions', v_links);
END $$;

REVOKE ALL ON FUNCTION public.cmp_reset_device_identity(TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_reset_device_identity(TEXT) TO authenticated;

COMMIT;
