-- QR orders go straight to the kitchen by default (owner 2026-11-08: "应该直接推送厨房就可以了，不用等员工确认").
-- A store with no dine.settings row is now "on"; a store can still turn it off in Ourea Settings > Tables.
-- Existing rows are switched on too (only the Scan E2E store had one).
-- scan_table_orders gains each order's "auto" (approved by QR) so the phone shows Sent -> Kitchen, no "server confirms" step.
-- The kitchen ticket still comes from the first Ourea terminal showing the floor (DineViewModel.acceptApproved).

BEGIN;

ALTER TABLE dine.settings ALTER COLUMN scan_auto_kitchen SET DEFAULT true;
UPDATE dine.settings SET scan_auto_kitchen = true, updated_at = now(), updated_by = 'default on 2026-11-08';

CREATE OR REPLACE FUNCTION public.scan_submit_cart(p_token TEXT, p_rev BIGINT, p_note TEXT, p_client_ref TEXT, p_guest_name TEXT)
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

-- Everything the table has ordered, for every phone at it: the QR orders of the
-- open check (and ones still waiting / refused in the last 2 hours), and the

CREATE OR REPLACE FUNCTION public.scan_table_orders(p_token TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  WITH t AS (SELECT * FROM dine.tables WHERE qr_token = p_token AND is_active),
  c AS (SELECT c.* FROM dine.checks c JOIN t ON c.table_id = t.id WHERE c.status IN ('OPEN', 'BILLED') ORDER BY c.opened_at DESC LIMIT 1)
  SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM t) THEN NULL ELSE jsonb_build_object(
    'orders', coalesce((
      SELECT jsonb_agg(jsonb_build_object('id', s.id, 'status', s.status, 'reject_reason', s.reject_reason, 'items', s.items,
                                          'subtotal_cents', s.subtotal_cents, 'tax_cents', s.tax_cents, 'created_at', s.created_at,
                                          'guest_name', s.guest_name, 'round', s.round,
                                          'auto', s.approved_by IS NOT DISTINCT FROM 'QR') ORDER BY s.created_at DESC)
        FROM dine.scan_orders s JOIN t ON s.table_id = t.id
       WHERE (s.check_id IS NOT NULL AND s.check_id = (SELECT id FROM c))
          OR (s.status <> 'ACCEPTED' AND s.created_at > now() - interval '2 hours')), '[]'::jsonb),
    'check', (SELECT jsonb_build_object('check_no', c.check_no, 'status', c.status,
        'lines', coalesce((SELECT jsonb_agg(jsonb_build_object('product_id', i.product_id, 'name', i.name, 'options', i.options,
                                                               'quantity', i.quantity, 'round', i.round) ORDER BY i.round, i.sent_at)
                             FROM dine.check_items i WHERE i.check_id = c.id AND i.status <> 'VOID'), '[]'::jsonb))
      FROM c)) END
$$;

-- ---------------------------------------------------------------- staff (devices)

CREATE OR REPLACE FUNCTION public.dine_settings() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object('scan_auto_kitchen', coalesce((SELECT s.scan_auto_kitchen FROM dine.settings s WHERE s.org_id = dine_device_org()), true))
$$;

COMMIT;
