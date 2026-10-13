-- 2026-10-11: terminals (小海狸's confirmation card) can create delivery orders.
--
-- create_delivery_order(): SECURITY DEFINER, so terminals need no INSERT
-- policy on delivery_orders / delivery_order_items. The store is always the
-- calling device's own (device_auth_map) -- never taken from the caller and
-- never guessed (a first draft fell back to "any org the user belongs to",
-- which for a multi-store CMP login could file an order under the wrong
-- store). CMP keeps creating orders through its own page.
--
-- Totals are computed here: subtotal per line, tax once per rate, half up --
-- the same rule as the terminal's checkout (SalesTax.kt), so the order total
-- and the amount charged when it is collected agree to the cent.

-- A draft of this migration added device INSERT / ALL policies; not needed.
DROP POLICY IF EXISTS "Devices can insert delivery orders" ON delivery_orders;
DROP POLICY IF EXISTS "Devices can insert delivery order items" ON delivery_order_items;

-- Optional arguments default to NULL: the terminal's JSON leaves out null
-- fields, and PostgREST only matches a function whose missing arguments have
-- defaults (a first version without them was "not found" for an order with no note).
DROP FUNCTION IF EXISTS public.create_delivery_order(TEXT, TEXT, TEXT, TEXT, JSONB, TEXT);
CREATE OR REPLACE FUNCTION public.create_delivery_order(
  p_customer_name TEXT,
  p_address TEXT,
  p_items JSONB,
  p_customer_phone TEXT DEFAULT NULL,
  p_note TEXT DEFAULT NULL,
  p_assigned_device_sn TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_org UUID;
  v_order_id UUID := gen_random_uuid();
  v_order_no BIGINT;
  v_item JSONB;
  v_line_no INT := 1;
  v_qty INT;
  v_price INT;
  v_subtotal INT := 0;
  v_tax INT := 0;
BEGIN
  SELECT org_id INTO v_org FROM device_auth_map
   WHERE auth_user_id = auth.uid() AND org_id IS NOT NULL
   ORDER BY created_at DESC LIMIT 1;
  IF v_org IS NULL THEN
    RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal');
  END IF;

  IF coalesce(trim(p_customer_name), '') = '' OR coalesce(trim(p_address), '') = '' THEN
    RETURN jsonb_build_object('success', false, 'message', 'customer_and_address_required');
  END IF;
  IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN
    RETURN jsonb_build_object('success', false, 'message', 'items_required');
  END IF;
  FOR v_item IN SELECT * FROM jsonb_array_elements(p_items) LOOP
    IF coalesce(trim(v_item->>'name'), '') = ''
       OR coalesce((v_item->>'quantity')::INT, 0) <= 0
       OR coalesce((v_item->>'unit_price_cents')::INT, 0) <= 0
       OR coalesce((v_item->>'tax_rate_bps')::INT, 0) NOT BETWEEN 0 AND 10000 THEN
      RETURN jsonb_build_object('success', false, 'message', 'invalid_item');
    END IF;
  END LOOP;

  INSERT INTO delivery_orders (
    id, org_id, customer_name, customer_phone, address, note,
    assigned_device_sn, status, subtotal_cents, tax_cents
  ) VALUES (
    v_order_id, v_org, trim(p_customer_name), nullif(trim(p_customer_phone), ''),
    trim(p_address), nullif(trim(p_note), ''),
    nullif(trim(p_assigned_device_sn), ''), 'OPEN', 0, 0
  ) RETURNING order_no INTO v_order_no;

  FOR v_item IN SELECT * FROM jsonb_array_elements(p_items) LOOP
    v_qty := (v_item->>'quantity')::INT;
    v_price := (v_item->>'unit_price_cents')::INT;
    v_subtotal := v_subtotal + v_qty * v_price;
    INSERT INTO delivery_order_items (
      order_id, line_no, product_id, name, barcode, quantity, unit_price_cents, tax_rate_bps
    ) VALUES (
      v_order_id, v_line_no,
      nullif(v_item->>'product_id', '')::UUID,
      trim(v_item->>'name'),
      nullif(v_item->>'barcode', ''),
      v_qty, v_price,
      coalesce((v_item->>'tax_rate_bps')::INT, 0)
    );
    v_line_no := v_line_no + 1;
  END LOOP;

  -- Tax once per rate, half up (SalesTax.kt).
  SELECT coalesce(sum(round(rate_total * rate_bps / 10000.0)), 0)::INT INTO v_tax
    FROM (
      SELECT tax_rate_bps AS rate_bps, sum(quantity * unit_price_cents) AS rate_total
        FROM delivery_order_items WHERE order_id = v_order_id AND tax_rate_bps > 0
       GROUP BY tax_rate_bps
    ) r;

  UPDATE delivery_orders SET subtotal_cents = v_subtotal, tax_cents = v_tax WHERE id = v_order_id;

  RETURN jsonb_build_object(
    'success', true, 'id', v_order_id, 'order_no', v_order_no,
    'subtotal_cents', v_subtotal, 'tax_cents', v_tax
  );
END;
$$;

REVOKE ALL ON FUNCTION public.create_delivery_order(TEXT, TEXT, JSONB, TEXT, TEXT, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.create_delivery_order(TEXT, TEXT, JSONB, TEXT, TEXT, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
