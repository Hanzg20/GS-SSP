-- 2026-10-13: the Cael (CMP) dine-in console: the store's floor (tables, open
-- checks, customer QR orders waiting) for a manager in a browser.
--
-- A browser can't print in the kitchen, so it doesn't turn a QR order into a
-- round itself: "approve" marks the order, and the next Ourea / Iris showing
-- the floor accepts it through dine_accept_scan (one terminal wins, the order
-- is locked FOR UPDATE) and prints the kitchen ticket. Until a terminal does,
-- the customer still sees "waiting for staff" -- nothing is promised that the
-- kitchen hasn't received. Reject is final and needs no terminal.

BEGIN;

ALTER TABLE dine.scan_orders ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ;
ALTER TABLE dine.scan_orders ADD COLUMN IF NOT EXISTS approved_by TEXT;
COMMENT ON COLUMN dine.scan_orders.approved_at IS 'Approved in Cael: a terminal on the floor accepts it (round + kitchen ticket) on its next refresh';

-- Who did it, for the audit columns: "<name or email> (Cael)".
CREATE OR REPLACE FUNCTION public.cmp_dine_user() RETURNS TEXT
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT left(coalesce(nullif(trim(p.full_name), ''), u.email, 'Cael user'), 60) || ' (Cael)'
    FROM auth.users u LEFT JOIN profiles p ON p.id = u.id WHERE u.id = auth.uid()
$$;

-- The floor of a store: active tables, live checks with totals, pending QR orders.
CREATE OR REPLACE FUNCTION public.cmp_dine_floor(p_org UUID) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
BEGIN
  IF NOT cmp_dine_can(p_org, false) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN jsonb_build_object(
    'tables', coalesce((SELECT jsonb_agg(jsonb_build_object('id', t.id, 'name', t.name, 'area', t.area, 'seats', t.seats)
                                ORDER BY t.area NULLS FIRST, t.sort, t.name)
                          FROM dine.tables t WHERE t.org_id = p_org AND t.is_active), '[]'::jsonb),
    'checks', coalesce((SELECT jsonb_agg(jsonb_build_object(
                                'id', c.id, 'table_id', c.table_id, 'check_no', c.check_no, 'status', c.status,
                                'guests', c.guests, 'server_name', c.server_name, 'opened_at', c.opened_at,
                                'total_cents', dine_check_total(c.id),
                                'paid_cents', (SELECT coalesce(sum(amount_cents), 0) FROM dine.payments p WHERE p.check_id = c.id)))
                          FROM dine.checks c WHERE c.org_id = p_org AND c.status IN ('OPEN', 'BILLED')), '[]'::jsonb),
    'scan_pending', coalesce((SELECT jsonb_agg(jsonb_build_object(
                                'id', s.id, 'table_id', s.table_id, 'table_name', t.name, 'items', s.items, 'note', s.note,
                                'subtotal_cents', s.subtotal_cents, 'tax_cents', s.tax_cents, 'created_at', s.created_at,
                                'approved_at', s.approved_at, 'approved_by', s.approved_by) ORDER BY s.created_at)
                          FROM dine.scan_orders s JOIN dine.tables t ON t.id = s.table_id
                         WHERE s.org_id = p_org AND s.status = 'PENDING'), '[]'::jsonb));
END $$;

CREATE OR REPLACE FUNCTION public.cmp_dine_approve_scan(p_org UUID, p_id UUID) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_s dine.scan_orders%ROWTYPE;
BEGIN
  IF NOT cmp_dine_can(p_org, true) THEN RETURN jsonb_build_object('success', false, 'message', 'not_allowed'); END IF;
  SELECT * INTO v_s FROM dine.scan_orders WHERE id = p_id AND org_id = p_org FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'order_not_found'); END IF;
  IF v_s.status <> 'PENDING' THEN RETURN jsonb_build_object('success', false, 'message', 'order_' || lower(v_s.status)); END IF;
  UPDATE dine.scan_orders SET approved_at = coalesce(approved_at, now()),
         approved_by = coalesce(approved_by, cmp_dine_user())
   WHERE id = p_id;
  RETURN jsonb_build_object('success', true);
END $$;

CREATE OR REPLACE FUNCTION public.cmp_dine_reject_scan(p_org UUID, p_id UUID, p_reason TEXT) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_s dine.scan_orders%ROWTYPE;
BEGIN
  IF NOT cmp_dine_can(p_org, true) THEN RETURN jsonb_build_object('success', false, 'message', 'not_allowed'); END IF;
  SELECT * INTO v_s FROM dine.scan_orders WHERE id = p_id AND org_id = p_org FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'order_not_found'); END IF;
  IF v_s.status <> 'PENDING' THEN RETURN jsonb_build_object('success', false, 'message', 'order_' || lower(v_s.status)); END IF;
  UPDATE dine.scan_orders SET status = 'REJECTED', decided_at = now(),
         decided_by = cmp_dine_user(),
         reject_reason = nullif(left(trim(coalesce(p_reason, '')), 120), '')
   WHERE id = p_id;
  RETURN jsonb_build_object('success', true);
END $$;

REVOKE ALL ON FUNCTION public.cmp_dine_user() FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_dine_floor(UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_dine_approve_scan(UUID, UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_dine_reject_scan(UUID, UUID, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_dine_floor(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_dine_approve_scan(UUID, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_dine_reject_scan(UUID, UUID, TEXT) TO authenticated;

COMMIT;
