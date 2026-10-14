-- 2026-10-13: Cael assigns / moves a device between stores through RPCs
-- (sys admins only), instead of writing public.devices directly. Needed
-- before 2026-10-13_devices_rls_lockdown.sql takes those writes away from
-- API clients.
--
-- Moving a device is only allowed while it has no transactions: they carry
-- no org of their own (reports find the store through devices.org_id), so a
-- device's sales history would move to the new store with it.
--
-- A move also:
--   * re-points device_auth_map, so the terminal's store-scoped reads
--     (dine_device_org(), staff, catalog) switch at once instead of at its
--     next app start;
--   * clears the acquirer link -- that account belongs to the old store;
--   * queues SYNC_CONFIG (kiosk shells reload; Ourea / Iris notice the new
--     store on their own periodic identity check);
--   * leaves a row in audit_logs.

BEGIN;

CREATE OR REPLACE FUNCTION public.cmp_assign_device(p_sn TEXT, p_org UUID, p_loc UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_dev devices%ROWTYPE;
  v_txns INT;
  v_moved BOOLEAN;
BEGIN
  IF NOT is_sys_admin() THEN RETURN jsonb_build_object('success', false, 'message', 'not_allowed'); END IF;
  SELECT * INTO v_dev FROM devices WHERE sn = p_sn FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_found'); END IF;
  IF NOT EXISTS (SELECT 1 FROM organizations WHERE id = p_org) THEN
    RETURN jsonb_build_object('success', false, 'message', 'org_not_found');
  END IF;
  IF p_loc IS NOT NULL AND NOT EXISTS (SELECT 1 FROM locations WHERE id = p_loc AND org_id = p_org) THEN
    RETURN jsonb_build_object('success', false, 'message', 'location_not_in_org');
  END IF;

  v_moved := v_dev.org_id IS NOT NULL AND v_dev.org_id IS DISTINCT FROM p_org;
  IF v_moved THEN
    SELECT count(*) INTO v_txns FROM transactions WHERE device_sn = p_sn;
    IF v_txns > 0 THEN
      RETURN jsonb_build_object('success', false, 'message', 'device_has_transactions', 'count', v_txns);
    END IF;
  END IF;

  UPDATE devices SET
    org_id = p_org,
    loc_id = p_loc,
    acquirer_account_id  = CASE WHEN v_moved THEN NULL ELSE acquirer_account_id END,
    acquirer_terminal_id = CASE WHEN v_moved THEN NULL ELSE acquirer_terminal_id END
   WHERE sn = p_sn;
  UPDATE device_auth_map SET org_id = p_org WHERE device_sn = p_sn;

  IF v_moved THEN
    INSERT INTO device_commands (device_sn, command, payload)
    VALUES (p_sn, 'SYNC_CONFIG', jsonb_build_object('reason', 'store_changed'));
  END IF;

  INSERT INTO audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), p_org, CASE WHEN v_moved THEN 'device.move' ELSE 'device.assign' END, 'devices', p_sn,
          jsonb_build_object('from_org', v_dev.org_id, 'to_org', p_org, 'from_loc', v_dev.loc_id, 'to_loc', p_loc,
                             'cleared_acquirer', v_moved AND v_dev.acquirer_account_id IS NOT NULL));

  RETURN jsonb_build_object('success', true, 'moved', v_moved);
END $$;

-- The acquirer account this device takes payments into (null = none). The
-- account must belong to the device's own store.
CREATE OR REPLACE FUNCTION public.cmp_set_device_acquirer(p_sn TEXT, p_account UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_dev devices%ROWTYPE;
BEGIN
  IF NOT is_sys_admin() THEN RETURN jsonb_build_object('success', false, 'message', 'not_allowed'); END IF;
  SELECT * INTO v_dev FROM devices WHERE sn = p_sn FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_found'); END IF;
  IF p_account IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM merchant_acquirer_accounts WHERE id = p_account AND org_id = v_dev.org_id) THEN
    RETURN jsonb_build_object('success', false, 'message', 'account_not_in_org');
  END IF;
  UPDATE devices SET acquirer_account_id = p_account WHERE sn = p_sn;
  INSERT INTO audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), v_dev.org_id, 'device.acquirer', 'devices', p_sn,
          jsonb_build_object('from', v_dev.acquirer_account_id, 'to', p_account));
  RETURN jsonb_build_object('success', true);
END $$;

REVOKE ALL ON FUNCTION public.cmp_assign_device(TEXT, UUID, UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_set_device_acquirer(TEXT, UUID) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_assign_device(TEXT, UUID, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_set_device_acquirer(TEXT, UUID) TO authenticated;

COMMIT;
