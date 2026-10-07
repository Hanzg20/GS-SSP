-- 2026-10-07: storage bucket for terminal log uploads (FETCH_LOGS / tech
-- panel "Export logs"). DiagnosticManager.uploadLogs has always written to
-- "device-logs", but the bucket never existed, so every remote log fetch
-- failed (found while diagnosing a scanner fault on production unit 253,
-- which has no USB debugging).
--
-- Private. A terminal may only write logs/<its own sn>_*.txt (its auth user
-- is mapped to the sn in device_auth_map); SYS_ADMIN reads.

INSERT INTO storage.buckets (id, name, public)
VALUES ('device-logs', 'device-logs', false)
ON CONFLICT (id) DO NOTHING;

DROP POLICY IF EXISTS "Terminals upload their own logs" ON storage.objects;
CREATE POLICY "Terminals upload their own logs" ON storage.objects
  FOR INSERT TO authenticated
  WITH CHECK (
    bucket_id = 'device-logs'
    AND EXISTS (
      SELECT 1 FROM device_auth_map m
      WHERE m.auth_user_id = auth.uid()
        AND storage.objects.name LIKE 'logs/' || m.device_sn || '\_%'
    )
  );

DROP POLICY IF EXISTS "Sys admins read device logs" ON storage.objects;
CREATE POLICY "Sys admins read device logs" ON storage.objects
  FOR SELECT TO authenticated
  USING (bucket_id = 'device-logs' AND is_sys_admin());
