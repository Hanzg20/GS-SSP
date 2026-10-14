-- 2026-10-14: a round can't send a sold-out dish to the kitchen.
--
-- Found testing Iris (Q2) and Ourea on one floor: Iris's dish list didn't grey out
-- stock_qty = 0 (Ourea's did), and dine_send_items took the line -- the kitchen got a
-- ticket for a dish the store doesn't have. The terminals now block it in the list;
-- this is the server side, the same rule scan_submit_order already applies.
-- Otherwise unchanged from the live definition (2026-10-13_dine_in.sql / _dine_schema.sql).

BEGIN;

CREATE OR REPLACE FUNCTION public.dine_send_items(p_check_id uuid, p_items jsonb, p_sent_by text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine.checks%ROWTYPE; v_round INT; v_item JSONB; v_sold TEXT;
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_store_terminal'); END IF;
  SELECT * INTO v_check FROM dine.checks WHERE id = p_check_id FOR UPDATE;
  IF NOT FOUND OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'check_not_found'); END IF;
  IF v_check.status NOT IN ('OPEN', 'BILLED') THEN RETURN jsonb_build_object('success', false, 'message', 'check_' || lower(v_check.status)); END IF;
  IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) = 0 THEN
    RETURN jsonb_build_object('success', false, 'message', 'items_required');
  END IF;
  FOR v_item IN SELECT * FROM jsonb_array_elements(p_items) LOOP
    IF coalesce(trim(v_item->>'name'), '') = '' OR coalesce((v_item->>'quantity')::INT, 0) <= 0
       OR coalesce((v_item->>'unit_price_cents')::INT, -1) < 0
       OR coalesce((v_item->>'tax_rate_bps')::INT, 0) NOT BETWEEN 0 AND 10000 THEN
      RETURN jsonb_build_object('success', false, 'message', 'invalid_item');
    END IF;
  END LOOP;
  -- A dish with no stock left can't go to the kitchen, as for QR orders (scan_submit_order)
  -- and Ourea's grid. Open-price lines (no product_id) aren't stock items.
  SELECT p.name INTO v_sold FROM jsonb_array_elements(p_items) i
    JOIN products p ON p.id = nullif(i->>'product_id', '')::UUID
   WHERE p.org_id = v_org AND coalesce((p.attributes->>'stock_qty')::INT, 1) <= 0
   LIMIT 1;
  IF v_sold IS NOT NULL THEN RETURN jsonb_build_object('success', false, 'message', 'sold_out', 'product', v_sold); END IF;
  SELECT coalesce(max(round), 0) + 1 INTO v_round FROM dine.check_items WHERE check_id = p_check_id;
  INSERT INTO dine.check_items (check_id, round, product_id, name, options, quantity, unit_price_cents, tax_rate_bps, note, sent_by)
  SELECT p_check_id, v_round, nullif(i->>'product_id', '')::UUID, trim(i->>'name'),
         coalesce(i->'options', '[]'::jsonb), (i->>'quantity')::INT, (i->>'unit_price_cents')::INT,
         coalesce((i->>'tax_rate_bps')::INT, 0), nullif(trim(i->>'note'), ''), nullif(trim(p_sent_by), '')
    FROM jsonb_array_elements(p_items) i;
  UPDATE dine.checks SET status = 'OPEN', updated_at = now() WHERE id = p_check_id;
  RETURN jsonb_build_object('success', true, 'round', v_round);
END $$;

COMMIT;
