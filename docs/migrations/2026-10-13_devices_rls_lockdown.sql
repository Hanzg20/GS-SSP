-- 2026-10-13: public.devices was writable by anyone holding the public API key.
-- The UPDATE policies were USING (true) for anon and authenticated, with
-- column grants covering org_id, loc_id, acquirer_account_id,
-- acquirer_terminal_id, is_active and payment_test_enabled -- so any client
-- could move any terminal to another store, point its card payments at another
-- acquirer account, or switch payment test mode on. SELECT was open to every
-- anonymous session too.
--
-- After this:
--   * a terminal (anonymous session) sees and updates only its own row, i.e.
--     the sn its session is linked to in device_auth_map, and only the columns
--     it reports itself: app_version, status, last_seen, service_units,
--     remote_locked / remote_lock_changed_at. registerDevice()'s upsert also
--     sends sn, so sn is updatable too (WITH CHECK keeps it the terminal's own).
--   * a new terminal can still register itself (INSERT of those columns), with
--     no store -- a sys admin assigns it in Cael.
--   * store, location and acquirer change only through SECURITY DEFINER RPCs
--     (cmp_assign_device, cmp_set_device_acquirer -- 2026-10-13_cmp_assign_device.sql),
--     alias through update_device_alias, last_seen/status also through the
--     heartbeat trigger.
--   * Cael members keep reading their stores' devices ("Org members can view
--     org devices", unchanged).
--   * the anon role (no session at all) gets nothing.
--
-- Known gap left: a session that just signed in (new anonymous user) isn't in
-- device_auth_map until sync_device_identity runs, and the terminals call
-- registerDevice() before that -- so on such a launch the app_version update
-- is refused (logged, harmless) and lands on the next launch.

BEGIN;

DROP POLICY IF EXISTS "Anon device can insert own row" ON public.devices;
DROP POLICY IF EXISTS "Anon device can update own row" ON public.devices;
DROP POLICY IF EXISTS "Anon device can view own row" ON public.devices;
DROP POLICY IF EXISTS "Anonymous devices can view own row for upsert" ON public.devices;
DROP POLICY IF EXISTS "devices can update own row" ON public.devices;
DROP POLICY IF EXISTS "devices can insert own row" ON public.devices;

CREATE POLICY "devices view own row" ON public.devices FOR SELECT TO authenticated
  USING (sn IN (SELECT device_sn FROM public.device_auth_map WHERE auth_user_id = auth.uid()));
CREATE POLICY "devices update own row" ON public.devices FOR UPDATE TO authenticated
  USING (sn IN (SELECT device_sn FROM public.device_auth_map WHERE auth_user_id = auth.uid()))
  WITH CHECK (sn IN (SELECT device_sn FROM public.device_auth_map WHERE auth_user_id = auth.uid()));
CREATE POLICY "devices register" ON public.devices FOR INSERT TO authenticated
  WITH CHECK (org_id IS NULL AND loc_id IS NULL AND acquirer_account_id IS NULL);

REVOKE ALL ON public.devices FROM anon, authenticated;
GRANT SELECT (sn, loc_id, org_id, vertical_type, remote_locked, remote_lock_changed_at, status, app_version,
              config_version, is_active, last_seen, created_at, hardware_vendor, acquirer_account_id,
              acquirer_terminal_id, alias, payment_test_enabled, service_units)
  ON public.devices TO authenticated;
GRANT INSERT (sn, app_version, status, last_seen, service_units) ON public.devices TO authenticated;
GRANT UPDATE (sn, app_version, status, last_seen, service_units, remote_locked, remote_lock_changed_at)
  ON public.devices TO authenticated;

COMMIT;
