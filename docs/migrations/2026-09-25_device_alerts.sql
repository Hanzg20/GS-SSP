-- 2026-09-25: make terminal alerts (app_error_logs) visible and actionable in CMP.
-- Terminals already write CRITICAL/WARNING rows here (VOID_AND_REFUND_FAILED,
-- HARDWARE_PULSE_FAIL, TIMER_OUTPUT_START_FAIL, PENDING_APPROVED_REVERSAL_FAILED,
-- BATCH_CLOSE_GAVE_UP, ...), but no CMP user could read the table at all, so a
-- customer charged without service had nobody who would ever find out.
-- Idempotent; run once against the live database.

-- 1. Who handled an alert, and when.
ALTER TABLE public.app_error_logs ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;
ALTER TABLE public.app_error_logs ADD COLUMN IF NOT EXISTS resolved_by UUID;
ALTER TABLE public.app_error_logs ADD COLUMN IF NOT EXISTS resolution_note TEXT;
CREATE INDEX IF NOT EXISTS idx_app_error_logs_open
    ON public.app_error_logs (severity, created_at DESC) WHERE resolved_at IS NULL;

-- 2. Read: sys admins everything; org members their own org's devices.
DROP POLICY IF EXISTS "Org members can view their devices' error logs" ON public.app_error_logs;
CREATE POLICY "Org members can view their devices' error logs" ON public.app_error_logs
FOR SELECT TO authenticated
USING (
    public.is_sys_admin()
    OR device_sn IN (SELECT d.sn FROM public.devices d WHERE d.org_id IN (SELECT public.member_org_ids()))
);

-- 3. Resolve: through a function (no UPDATE policy), so only the three
-- resolution columns can ever change -- the alert itself stays as the
-- terminal wrote it. Gated on the existing devices.command permission.
CREATE OR REPLACE FUNCTION public.resolve_device_alert(p_alert_id BIGINT, p_note TEXT DEFAULT NULL)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_org UUID;
BEGIN
  SELECT d.org_id INTO v_org
  FROM public.app_error_logs l
  LEFT JOIN public.devices d ON d.sn = l.device_sn
  WHERE l.id = p_alert_id;
  IF NOT FOUND THEN
    RETURN FALSE;
  END IF;

  IF NOT (public.is_sys_admin() OR (v_org IS NOT NULL AND public.has_permission_for_org('devices.command', v_org))) THEN
    RAISE EXCEPTION 'not_allowed';
  END IF;

  UPDATE public.app_error_logs
  SET resolved_at = now(), resolved_by = auth.uid(), resolution_note = p_note
  WHERE id = p_alert_id AND resolved_at IS NULL;
  RETURN FOUND;
END;
$$;

GRANT EXECUTE ON FUNCTION public.resolve_device_alert(BIGINT, TEXT) TO authenticated;
