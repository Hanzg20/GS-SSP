-- 2026-10-07: ad-media storage policies compared the org id with
-- storage.foldername(o.name) -- the ORGANIZATION's name -- instead of the
-- object's path, so no merchant account could ever upload (or delete) its own
-- ad files; only SYS_ADMIN (is_sys_admin short-circuit) could. Found when
-- Eagleson's admin couldn't replace a video in CMP. Use objects.name.

DROP POLICY IF EXISTS "Publishers upload own org ad media" ON storage.objects;
CREATE POLICY "Publishers upload own org ad media" ON storage.objects
  FOR INSERT TO authenticated
  WITH CHECK (
    bucket_id = 'ad-media'
    AND (
      is_sys_admin()
      OR EXISTS (
        SELECT 1 FROM organizations o
        WHERE o.id::text = (storage.foldername(objects.name))[1]
          AND has_permission_for_org('config.publish', o.id)
      )
    )
  );

DROP POLICY IF EXISTS "Publishers delete own org ad media" ON storage.objects;
CREATE POLICY "Publishers delete own org ad media" ON storage.objects
  FOR DELETE TO authenticated
  USING (
    bucket_id = 'ad-media'
    AND (
      is_sys_admin()
      OR EXISTS (
        SELECT 1 FROM organizations o
        WHERE o.id::text = (storage.foldername(objects.name))[1]
          AND has_permission_for_org('config.publish', o.id)
      )
    )
  );
