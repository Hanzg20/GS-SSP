-- 2026-11-09: the party size from the QR door screen reaches the check (Ourea showed "1 位" for a
-- table that said 3 at the door -- Kanata kebab full-flow test). dine.scan_orders.guests, set by
-- scan_submit_cart (new optional p_guests; older pages call without it), used by dine_accept_scan:
-- a new check opens with it, an open check takes the larger of the two.

BEGIN;

ALTER TABLE dine.scan_orders ADD COLUMN IF NOT EXISTS guests INT CHECK (guests BETWEEN 1 AND 30);
COMMENT ON COLUMN dine.scan_orders.guests IS 'party size the customer gave at the door (optional)';

DROP FUNCTION IF EXISTS public.scan_submit_cart(TEXT, BIGINT, TEXT, TEXT, TEXT);
CREATE OR REPLACE FUNCTION public.scan_submit_cart(p_token TEXT, p_rev BIGINT, p_note TEXT, p_client_ref TEXT, p_guest_name TEXT, p_guests INT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_t dine.tables%ROWTYPE; v_existing UUID; v_items JSONB; v_guests JSONB; v_ids UUID[]; v_r JSONB; v_auto BOOLEAN;
BEGIN
  SELECT * INTO v_t FROM dine.tables WHERE qr_token = p_token AND is_active FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_table'); END IF;
  -- The same send again (double tap, retry after a lost answer): the order it already made.
  SELECT id INTO v_existing FROM dine.scan_orders WHERE table_id = v_t.id AND client_ref = p_client_ref;
  IF FOUND THEN RETURN jsonb_build_object('success', true, 'id', v_existing, 'duplicate', true); END IF;
  IF p_rev IS DISTINCT FROM v_t.scan_cart_rev THEN
    RETURN jsonb_build_object('success', false, 'message', 'cart_changed', 'cart', scan_cart(p_token));
  END IF;

  SELECT coalesce(jsonb_agg(jsonb_build_object('product_id', i.product_id, 'quantity', i.quantity, 'option_ids', i.option_ids, 'note', i.note)
                            ORDER BY i.created_at), '[]'::jsonb),
         coalesce(jsonb_agg(to_jsonb(coalesce(i.guest_name, '')) ORDER BY i.created_at), '[]'::jsonb),
         array_agg(i.id)
    INTO v_items, v_guests, v_ids
    FROM dine.scan_cart_items i WHERE i.table_id = v_t.id AND i.updated_at > now() - interval '6 hours';
  IF jsonb_array_length(v_items) = 0 THEN RETURN jsonb_build_object('success', false, 'message', 'items_required'); END IF;

  v_r := scan_submit_order(p_token, v_items, p_note, p_client_ref);
  IF NOT coalesce((v_r->>'success')::BOOLEAN, false) THEN RETURN v_r; END IF;

  SELECT coalesce(s.scan_auto_kitchen, true) INTO v_auto FROM dine.settings s WHERE s.org_id = v_t.org_id;
  -- Who sent it, who added each line (same order as the cart), and the store's kitchen choice.
  UPDATE dine.scan_orders o SET
         guest_name = nullif(trim(left(p_guest_name, 30)), ''),
         guests = CASE WHEN p_guests BETWEEN 1 AND 30 THEN p_guests END,
         items = (SELECT jsonb_agg(CASE WHEN g.v #>> '{}' <> '' THEN l.v || jsonb_build_object('guest', g.v #>> '{}') ELSE l.v END ORDER BY l.n)
                    FROM jsonb_array_elements(o.items) WITH ORDINALITY l(v, n)
                    JOIN jsonb_array_elements(v_guests) WITH ORDINALITY g(v, n) USING (n)),
         approved_at = CASE WHEN coalesce(v_auto, true) THEN now() END,
         approved_by = CASE WHEN coalesce(v_auto, true) THEN 'QR' END
   WHERE o.id = (v_r->>'id')::UUID;
  DELETE FROM dine.scan_cart_items WHERE id = ANY (v_ids);
  UPDATE dine.tables SET scan_cart_rev = scan_cart_rev + 1 WHERE id = v_t.id;
  RETURN v_r || jsonb_build_object('auto_kitchen', coalesce(v_auto, true));
END $$;
REVOKE ALL ON FUNCTION public.scan_submit_cart(TEXT, BIGINT, TEXT, TEXT, TEXT, INT) FROM public;
GRANT EXECUTE ON FUNCTION public.scan_submit_cart(TEXT, BIGINT, TEXT, TEXT, TEXT, INT) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.dine_accept_scan(p_id uuid, p_staff_name text, p_device_sn text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$
DECLARE v_org UUID := dine_device_org(); v_s dine.scan_orders%ROWTYPE; v_open JSONB; v_send JSONB; v_check dine.checks%ROWTYPE;
BEGIN
  SELECT * INTO v_s FROM dine.scan_orders WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_s.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'order_not_found'); END IF;
  IF v_s.status <> 'PENDING' THEN RETURN jsonb_build_object('success', false, 'message', 'order_' || lower(v_s.status)); END IF;
  v_open := dine_open_check(v_s.table_id, coalesce(v_s.guests, 1), NULL, p_staff_name, p_device_sn);
  IF NOT (v_open->>'success')::BOOLEAN THEN RETURN v_open; END IF;
  v_send := dine_send_items((v_open->>'id')::UUID, v_s.items, coalesce(nullif(trim(p_staff_name), ''), 'QR') || ' (QR)');
  IF NOT (v_send->>'success')::BOOLEAN THEN RETURN v_send; END IF;
  UPDATE dine.scan_orders SET status = 'ACCEPTED', decided_at = now(), decided_by = nullif(trim(p_staff_name), ''),
         check_id = (v_open->>'id')::UUID, round = (v_send->>'round')::INT
   WHERE id = p_id;
  -- The party size the phone was told at the door; an open check only ever grows.
  IF v_s.guests IS NOT NULL THEN
    UPDATE dine.checks SET guests = greatest(guests, v_s.guests) WHERE id = (v_open->>'id')::UUID;
  END IF;
  SELECT * INTO v_check FROM dine.checks WHERE id = (v_open->>'id')::UUID;
  RETURN jsonb_build_object('success', true, 'check_id', v_check.id, 'check_no', v_check.check_no,
    'guests', v_check.guests, 'round', (v_send->>'round')::INT,
    'table_name', (SELECT name FROM dine.tables WHERE id = v_s.table_id),
    'items', v_s.items, 'note', v_s.note);
END $function$;

COMMIT;
