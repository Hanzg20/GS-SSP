-- 2026-11-01: delivery dispatch -- where an order is, apart from whether it's paid.
--
-- status stays the money side (OPEN / PAID / CANCELLED). delivery_stage is the
-- trip: WAITING (in the store) -> OUT (a driver's Iris took it out) ->
-- DELIVERED (handed over: collecting at the door marks it). A driver who can't
-- hand it over brings it back: WAITING again, attempts + 1, the reason kept.
--
-- An order OUT with one terminal is that terminal's alone: other terminals of
-- the store stop listing it and can't collect it, so two drivers never knock on
-- the same door. Devices change the stage only through the RPCs below; CMP
-- managers can put an order back to WAITING (a driver's phone died) by UPDATE.

ALTER TABLE delivery_orders
  ADD COLUMN IF NOT EXISTS delivery_stage    TEXT NOT NULL DEFAULT 'WAITING',
  ADD COLUMN IF NOT EXISTS out_device_sn     TEXT,
  ADD COLUMN IF NOT EXISTS out_staff_name    TEXT,
  ADD COLUMN IF NOT EXISTS out_at            TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS delivered_at      TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS attempts          INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS last_attempt_note TEXT;

ALTER TABLE delivery_orders DROP CONSTRAINT IF EXISTS delivery_orders_stage_check;
ALTER TABLE delivery_orders ADD CONSTRAINT delivery_orders_stage_check
  CHECK (delivery_stage IN ('WAITING', 'OUT', 'DELIVERED'));

-- Orders already collected were handed over when they were paid.
UPDATE delivery_orders SET delivery_stage = 'DELIVERED', delivered_at = paid_at
 WHERE status = 'PAID' AND delivery_stage = 'WAITING';

-- Devices: as before, minus orders out with another terminal.
DROP POLICY IF EXISTS "Devices can view their delivery orders" ON delivery_orders;
CREATE POLICY "Devices can view their delivery orders" ON delivery_orders FOR SELECT TO authenticated
  USING (
    org_id IN (SELECT org_id FROM device_auth_map WHERE auth_user_id = auth.uid())
    AND (assigned_device_sn IS NULL
         OR assigned_device_sn IN (SELECT device_sn FROM device_auth_map WHERE auth_user_id = auth.uid()))
    AND (out_device_sn IS NULL
         OR out_device_sn IN (SELECT device_sn FROM device_auth_map WHERE auth_user_id = auth.uid()))
  );

-- A driver takes an order out. 'ok' (also when it's already out with this
-- terminal), 'not_found', 'not_open' (paid / cancelled), 'taken' (out with another).
CREATE OR REPLACE FUNCTION public.delivery_order_out(p_order_id UUID, p_staff_name TEXT DEFAULT NULL)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_sn TEXT;
  v_org UUID;
  v_order delivery_orders%ROWTYPE;
BEGIN
  SELECT device_sn, org_id INTO v_sn, v_org FROM device_auth_map WHERE auth_user_id = auth.uid();
  IF v_sn IS NULL THEN RETURN 'not_found'; END IF;

  SELECT * INTO v_order FROM delivery_orders WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND OR v_order.org_id IS DISTINCT FROM v_org
     OR (v_order.assigned_device_sn IS NOT NULL AND v_order.assigned_device_sn <> v_sn) THEN
    RETURN 'not_found';
  END IF;
  IF v_order.status <> 'OPEN' THEN RETURN 'not_open'; END IF;
  IF v_order.delivery_stage = 'OUT' THEN
    RETURN CASE WHEN v_order.out_device_sn = v_sn THEN 'ok' ELSE 'taken' END;
  END IF;

  UPDATE delivery_orders
     SET delivery_stage = 'OUT', out_device_sn = v_sn, out_staff_name = nullif(trim(p_staff_name), ''),
         out_at = now(), updated_at = now()
   WHERE id = p_order_id;
  RETURN 'ok';
END;
$$;

-- The driver brings it back (nobody home, wrong address...). 'ok', 'not_found',
-- 'not_out' (not out with this terminal -- e.g. collected, or put back in CMP).
CREATE OR REPLACE FUNCTION public.delivery_order_back(p_order_id UUID, p_note TEXT DEFAULT NULL)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_sn TEXT;
  v_org UUID;
  v_order delivery_orders%ROWTYPE;
BEGIN
  SELECT device_sn, org_id INTO v_sn, v_org FROM device_auth_map WHERE auth_user_id = auth.uid();
  IF v_sn IS NULL THEN RETURN 'not_found'; END IF;

  SELECT * INTO v_order FROM delivery_orders WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND OR v_order.org_id IS DISTINCT FROM v_org THEN RETURN 'not_found'; END IF;
  IF v_order.status <> 'OPEN' OR v_order.delivery_stage <> 'OUT' OR v_order.out_device_sn IS DISTINCT FROM v_sn THEN
    RETURN 'not_out';
  END IF;

  UPDATE delivery_orders
     SET delivery_stage = 'WAITING', out_device_sn = NULL, out_staff_name = NULL, out_at = NULL,
         attempts = attempts + 1, last_attempt_note = nullif(trim(p_note), ''), updated_at = now()
   WHERE id = p_order_id;
  RETURN 'ok';
END;
$$;

-- Collecting at the door also hands it over; refused while out with another terminal.
CREATE OR REPLACE FUNCTION public.mark_delivery_order_paid(p_order_id UUID, p_ecr_ref_num TEXT, p_payment_method TEXT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_sn TEXT;
  v_org UUID;
  v_order delivery_orders%ROWTYPE;
BEGIN
  SELECT device_sn, org_id INTO v_sn, v_org FROM device_auth_map WHERE auth_user_id = auth.uid();
  IF v_sn IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_a_device');
  END IF;

  SELECT * INTO v_order FROM delivery_orders WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND OR v_order.org_id IS DISTINCT FROM v_org
     OR (v_order.assigned_device_sn IS NOT NULL AND v_order.assigned_device_sn <> v_sn) THEN
    RETURN json_build_object('success', false, 'message', 'order_not_found');
  END IF;

  IF v_order.status = 'PAID' THEN
    RETURN json_build_object('success', v_order.paid_ecr_ref_num = p_ecr_ref_num,
                             'message', CASE WHEN v_order.paid_ecr_ref_num = p_ecr_ref_num THEN 'already_marked' ELSE 'already_paid' END);
  END IF;
  IF v_order.status <> 'OPEN' THEN
    RETURN json_build_object('success', false, 'message', 'order_' || lower(v_order.status));
  END IF;
  IF v_order.delivery_stage = 'OUT' AND v_order.out_device_sn <> v_sn THEN
    RETURN json_build_object('success', false, 'message', 'order_out_with_another_terminal');
  END IF;

  UPDATE delivery_orders
     SET status = 'PAID', paid_at = now(), paid_ecr_ref_num = p_ecr_ref_num,
         paid_by_device_sn = v_sn, payment_method = p_payment_method,
         delivery_stage = 'DELIVERED', delivered_at = now(), updated_at = now()
   WHERE id = p_order_id;
  RETURN json_build_object('success', true, 'message', 'marked');
END;
$$;

REVOKE ALL ON FUNCTION public.delivery_order_out(UUID, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.delivery_order_out(UUID, TEXT) TO authenticated;
REVOKE ALL ON FUNCTION public.delivery_order_back(UUID, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.delivery_order_back(UUID, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
