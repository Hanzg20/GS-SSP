-- 2026-10-06: no QR (phone) payment option on a terminal until a real QR
-- payment gateway is live.
--
-- create-qr-session runs whatever PAYMENT_GATEWAY says (stub by default --
-- Nuvei's online gateway is not integrated yet), so a terminal published
-- with payment_method_mode 0 (all) or 2 (scan only) would show customers a
-- "pay by QR" choice that can never take money.
--
-- platform_settings.qr_payment_live is the switch, flipped by a SYS_ADMIN
-- once a real gateway is configured end to end. While it is false:
--   * publishing a config with mode 0 or 2 is refused;
--   * a config without the key gets 1 (card only) -- the terminal reads a
--     missing key as 0 (all).
-- CMP greys out those options with the same explanation.

CREATE TABLE IF NOT EXISTS public.platform_settings (
    key TEXT PRIMARY KEY,
    value JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE public.platform_settings ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Signed-in users can read platform settings" ON public.platform_settings;
CREATE POLICY "Signed-in users can read platform settings" ON public.platform_settings
FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS "Sys admins manage platform settings" ON public.platform_settings;
CREATE POLICY "Sys admins manage platform settings" ON public.platform_settings
FOR ALL TO authenticated USING (public.is_sys_admin()) WITH CHECK (public.is_sys_admin());
REVOKE ALL ON public.platform_settings FROM anon;

INSERT INTO public.platform_settings (key, value) VALUES ('qr_payment_live', 'false'::jsonb)
ON CONFLICT (key) DO NOTHING;

CREATE OR REPLACE FUNCTION public.guard_qr_payment_mode()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_live BOOLEAN;
  v_mode TEXT;
BEGIN
  SELECT (value)::text::boolean INTO v_live FROM public.platform_settings WHERE key = 'qr_payment_live';
  IF COALESCE(v_live, false) THEN
    RETURN NEW;
  END IF;
  v_mode := NEW.settings ->> 'payment_method_mode';
  IF v_mode IS NULL THEN
    NEW.settings := COALESCE(NEW.settings, '{}'::jsonb) || jsonb_build_object('payment_method_mode', 1);
  ELSIF v_mode IN ('0', '2') THEN
    RAISE EXCEPTION 'qr_payment_not_live: QR payment is not available yet; terminals must be card only (payment_method_mode 1)'
      USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_guard_qr_payment_mode ON public.app_configurations;
CREATE TRIGGER trg_guard_qr_payment_mode
BEFORE INSERT OR UPDATE OF settings ON public.app_configurations
FOR EACH ROW EXECUTE FUNCTION public.guard_qr_payment_mode();
