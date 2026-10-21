-- 2026-10-21: "scan" on the Aegis Wash payment page can now mean the customer
-- holding a coupon / VIP code up to the terminal's camera
-- (settings.scan_pays_with = 'CODE', the default from Wash 1.1.6) instead of a
-- phone QR payment ('MOBILE_QR', still not live -- see 2026-10-06_qr_payment_guard.sql).
--
-- guard_qr_payment_mode keeps refusing modes 0 / 2 while qr_payment_live is
-- false, EXCEPT when scan means a code AND every wash terminal of the org runs
-- Aegis Wash 1.1.6 or later: an older build reads mode 0 / 2 as the phone-QR
-- page, which cannot take money.
BEGIN;

-- '1.1.6' >= '1.1.5' compared as numbers; NULL / unparseable = old.
CREATE OR REPLACE FUNCTION public.app_version_at_least(p_version TEXT, p_min TEXT)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
SET search_path = public
AS $$
  SELECT CASE
    WHEN p_version IS NULL OR p_version !~ '^\d+(\.\d+)*$' THEN false
    ELSE string_to_array(p_version, '.')::int[] >= string_to_array(p_min, '.')::int[]
  END;
$$;
COMMENT ON FUNCTION public.app_version_at_least(TEXT, TEXT) IS
    'True when a dotted version (devices.app_version) is at least p_min; NULL or unparseable is false.';

CREATE OR REPLACE FUNCTION public.guard_qr_payment_mode()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_live BOOLEAN;
  v_mode TEXT;
  v_old  TEXT;
BEGIN
  SELECT (value)::text::boolean INTO v_live FROM public.platform_settings WHERE key = 'qr_payment_live';
  IF COALESCE(v_live, false) THEN
    RETURN NEW;
  END IF;
  v_mode := NEW.settings ->> 'payment_method_mode';
  IF v_mode IS NULL THEN
    NEW.settings := COALESCE(NEW.settings, '{}'::jsonb) || jsonb_build_object('payment_method_mode', 1);
  ELSIF v_mode IN ('0', '2') THEN
    IF COALESCE(NEW.settings ->> 'scan_pays_with', 'CODE') <> 'CODE' THEN
      RAISE EXCEPTION 'qr_payment_not_live: QR payment is not available yet; terminals must be card only (payment_method_mode 1)'
        USING ERRCODE = 'P0001';
    END IF;
    IF NEW.vertical_type = 'WASH' THEN
      SELECT string_agg(d.sn || ' (' || COALESCE(d.app_version, '?') || ')', ', ') INTO v_old
      FROM public.devices d
      WHERE d.org_id = NEW.org_id AND d.vertical_type = 'WASH'
        AND NOT public.app_version_at_least(d.app_version, '1.1.6');
      IF v_old IS NOT NULL THEN
        RAISE EXCEPTION 'scan_code_needs_wash_1_1_6: update these wash terminals to Aegis Wash 1.1.6 first: %', v_old
          USING ERRCODE = 'P0001';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
COMMENT ON FUNCTION public.guard_qr_payment_mode() IS
    'app_configurations: card only while phone QR payment is not live, except scan = coupon / VIP code (scan_pays_with CODE) on Aegis Wash 1.1.6+.';

COMMIT;
