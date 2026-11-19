-- Arke: keep each paid-order email's outcome on its payment (2026-11-19).
-- The email failed silently for two days (SMTP_PORT was 456, not 465) and the only trace was the Edge
-- Function log. Now arke-pay / arke-pay-dmn write SENT, FAILED: <error> or SKIPPED: <why> here after each send.

ALTER TABLE online.payments ADD COLUMN IF NOT EXISTS mail_status TEXT;
ALTER TABLE online.payments ADD COLUMN IF NOT EXISTS mail_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION public.online_pay_mail_result(p_id UUID, p_status TEXT)
RETURNS VOID LANGUAGE sql SECURITY DEFINER SET search_path = public, online AS $$
  UPDATE online.payments SET mail_status = left(p_status, 300), mail_at = now()
   WHERE id = p_id AND mail_status IS DISTINCT FROM 'SENT';   -- a sent email stays sent
$$;
REVOKE ALL ON FUNCTION public.online_pay_mail_result(UUID, TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_pay_mail_result(UUID, TEXT) TO service_role;
