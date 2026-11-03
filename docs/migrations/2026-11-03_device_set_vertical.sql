-- 2026-11-03: a terminal reports which Aegis app it runs (devices.vertical_type).
--
-- vertical_type was only ever set by hand, default WASH. 255 moved from Aegis Wash to Aegis Vend
-- and kept WASH, so publishing "card + scan" was refused: guard_qr_payment_mode checked it as a
-- wash terminal older than Wash 1.1.6. Devices can't write the column (column grants), so:
--
--   device_set_vertical(p_vertical)  sets vertical_type on the CALLING device's own row (its
--       device_auth_map link, made by sync_device_identity), to one of the unattended Aegis apps.
--       Nothing else changes; the same value again is a no-op.

BEGIN;

CREATE OR REPLACE FUNCTION public.device_set_vertical(p_vertical text)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
  v_sn TEXT;
  v_old TEXT;
BEGIN
  IF p_vertical IS NULL OR p_vertical NOT IN ('WASH', 'VEND', 'TIMER', 'EV', 'LAUNDRY') THEN
    RETURN json_build_object('success', false, 'message', 'invalid_vertical');
  END IF;
  SELECT device_sn INTO v_sn FROM public.device_auth_map WHERE auth_user_id = auth.uid();
  IF v_sn IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'device_not_linked');
  END IF;
  SELECT vertical_type INTO v_old FROM public.devices WHERE sn = v_sn FOR UPDATE;
  IF v_old IS DISTINCT FROM p_vertical THEN
    UPDATE public.devices SET vertical_type = p_vertical WHERE sn = v_sn;
  END IF;
  RETURN json_build_object('success', true, 'sn', v_sn, 'vertical_type', p_vertical, 'changed', v_old IS DISTINCT FROM p_vertical);
END;
$$;

COMMENT ON FUNCTION public.device_set_vertical(text) IS
  'Device: report the Aegis app it runs (devices.vertical_type) on its own row; WASH/VEND/TIMER/EV/LAUNDRY. Aegis Vend, 2026-11-03.';

REVOKE ALL ON FUNCTION public.device_set_vertical(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.device_set_vertical(text) TO authenticated, service_role;

COMMIT;
