-- 2026-11-08: Ourea Scan, second pass (owner: "借鉴 Chowbus，突出 GoldSky 自己的特色").
-- docs/database_design.md §5, docs/system_architecture.md §17.
--
-- * Call a server from the table QR: dine.service_calls. One open call per
--   table (a second tap answers "already called"), at most 6 per table in 30
--   minutes, the customer can cancel it, staff clear it on the Ourea floor
--   (dine_ack_call). An open call older than 30 minutes no longer counts --
--   it shows neither on the floor nor on the phone.
-- * scan_menu also returns the store's logo (organizations.logo_url) and each
--   dish's photo (products.attributes.image_url, uploaded in Cael) and its
--   translated names (attributes.name_i18n, {"zh": "...", "fr": "..."}).
-- * dine_floor also returns the open calls, so the terminal shows them with
--   the QR orders without another request.

BEGIN;

CREATE TABLE IF NOT EXISTS dine.service_calls (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  table_id    UUID NOT NULL REFERENCES dine.tables(id) ON DELETE CASCADE,
  reason      TEXT NOT NULL CHECK (reason IN ('WATER', 'BILL', 'HELP')),
  status      TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKED', 'CANCELLED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at   TIMESTAMPTZ,
  acked_by    TEXT
);
CREATE INDEX IF NOT EXISTS service_calls_org_open_idx ON dine.service_calls (org_id, created_at) WHERE status = 'OPEN';
CREATE INDEX IF NOT EXISTS service_calls_table_idx ON dine.service_calls (table_id, created_at);
COMMENT ON TABLE dine.service_calls IS 'Customers calling a server from the table QR (Ourea Scan): OPEN -> ACKED (staff) / CANCELLED (customer); open > 30 min = stale';
ALTER TABLE dine.service_calls ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON dine.service_calls FROM public, anon, authenticated;

-- ---------------------------------------------------------------- customer (anon)

CREATE OR REPLACE FUNCTION public.scan_menu(p_token TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object(
    'table', jsonb_build_object('name', t.name, 'area', t.area),
    'store', jsonb_build_object('name', o.name, 'logo_url', o.logo_url),
    'products', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'id', p.id, 'name', p.name, 'price_cents', p.price_cents,
               'names', CASE WHEN jsonb_typeof(p.attributes->'name_i18n') = 'object' THEN p.attributes->'name_i18n' END,
               'image_url', p.attributes->>'image_url',
               'category', p.attributes->>'category',
               'description', p.attributes->>'description',
               'ingredients', p.attributes->>'ingredients',
               'allergens', p.attributes->'allergens',
               'dietary', coalesce(p.attributes->'dietary', '[]'::jsonb),
               'spice_level', (p.attributes->>'spice_level')::INT,
               'guide', p.attributes->>'guide',
               'tax_rate_bps', coalesce((p.attributes->>'tax_rate_bps')::INT, 1300),
               'modifier_groups', coalesce(p.attributes->'modifier_groups', '[]'::jsonb),
               'sold_out', coalesce((p.attributes->>'stock_qty')::INT, 1) <= 0)
             ORDER BY p.attributes->>'category' NULLS LAST, p.name)
        FROM products p
       WHERE p.org_id = t.org_id AND p.is_active AND p.vertical_type = 'RETAIL'), '[]'::jsonb))
    FROM dine.tables t JOIN organizations o ON o.id = t.org_id
   WHERE t.qr_token = p_token AND t.is_active
$$;

-- The table's latest call of the last 30 minutes (null when none).
CREATE OR REPLACE FUNCTION public.scan_call_state(p_token TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object('id', c.id, 'reason', c.reason, 'status', c.status,
                            'created_at', c.created_at, 'closed_at', c.closed_at)
    FROM dine.service_calls c JOIN dine.tables t ON t.id = c.table_id
   WHERE t.qr_token = p_token AND t.is_active AND c.created_at > now() - interval '30 minutes'
   ORDER BY c.created_at DESC LIMIT 1
$$;

CREATE OR REPLACE FUNCTION public.scan_call_waiter(p_token TEXT, p_reason TEXT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_table dine.tables%ROWTYPE; v_open UUID; v_id UUID;
BEGIN
  SELECT * INTO v_table FROM dine.tables WHERE qr_token = p_token AND is_active FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'unknown_table'); END IF;
  IF p_reason IS NULL OR p_reason NOT IN ('WATER', 'BILL', 'HELP') THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_reason');
  END IF;
  SELECT id INTO v_open FROM dine.service_calls
   WHERE table_id = v_table.id AND status = 'OPEN' AND created_at > now() - interval '30 minutes';
  IF FOUND THEN RETURN jsonb_build_object('success', true, 'id', v_open, 'already', true); END IF;
  IF (SELECT count(*) FROM dine.service_calls WHERE table_id = v_table.id AND created_at > now() - interval '30 minutes') >= 6 THEN
    RETURN jsonb_build_object('success', false, 'message', 'too_many_calls');
  END IF;
  INSERT INTO dine.service_calls (org_id, table_id, reason) VALUES (v_table.org_id, v_table.id, p_reason) RETURNING id INTO v_id;
  RETURN jsonb_build_object('success', true, 'id', v_id);
END $$;

CREATE OR REPLACE FUNCTION public.scan_cancel_call(p_token TEXT) RETURNS JSONB
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
  WITH done AS (
    UPDATE dine.service_calls c SET status = 'CANCELLED', closed_at = now()
      FROM dine.tables t
     WHERE t.id = c.table_id AND t.qr_token = p_token AND c.status = 'OPEN'
    RETURNING c.id)
  SELECT jsonb_build_object('success', true, 'cancelled', (SELECT count(*) FROM done))
$$;

-- ---------------------------------------------------------------- staff (devices)

-- Open calls of this store, oldest first.
CREATE OR REPLACE FUNCTION public.dine_service_calls() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object('id', c.id, 'table_id', c.table_id, 'table_name', t.name,
                                               'reason', c.reason, 'created_at', c.created_at) ORDER BY c.created_at), '[]'::jsonb)
    FROM dine.service_calls c JOIN dine.tables t ON t.id = c.table_id
   WHERE c.org_id = dine_device_org() AND c.status = 'OPEN' AND c.created_at > now() - interval '30 minutes'
$$;

CREATE OR REPLACE FUNCTION public.dine_ack_call(p_id UUID, p_staff_name TEXT) RETURNS JSONB
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
DECLARE v_org UUID := dine_device_org(); v_c dine.service_calls%ROWTYPE;
BEGIN
  SELECT * INTO v_c FROM dine.service_calls WHERE id = p_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_c.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'call_not_found'); END IF;
  -- Another terminal got there first, or the customer cancelled: nothing left to do.
  IF v_c.status <> 'OPEN' THEN RETURN jsonb_build_object('success', true, 'already', lower(v_c.status)); END IF;
  UPDATE dine.service_calls SET status = 'ACKED', closed_at = now(), acked_by = nullif(trim(left(p_staff_name, 60)), '') WHERE id = p_id;
  RETURN jsonb_build_object('success', true);
END $$;

CREATE OR REPLACE FUNCTION public.dine_floor()
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$
  SELECT jsonb_build_object(
    'tables', dine_list_tables(),
    'checks', coalesce((
      SELECT jsonb_agg(to_jsonb(c) || jsonb_build_object(
               'total_cents', dine_check_total(c.id),
               'paid_cents', (SELECT coalesce(sum(p.amount_cents), 0) FROM dine.payments p WHERE p.check_id = c.id AND p.refund_status IS NULL)))
        FROM dine.checks c
       WHERE c.org_id = dine_device_org() AND c.status IN ('OPEN', 'BILLED')), '[]'::jsonb),
    'scan_pending', dine_scan_pending(),
    'service_calls', dine_service_calls(),
    'online_paid', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'ecr_ref_num', p.ecr_ref_num,
               'table_name', t.name,
               'check_no', c.check_no,
               'amount_cents', p.amount_cents + p.tip_cents,
               'check_closed', c.status = 'PAID',
               'balance_cents', greatest(dine_check_total(c.id) - (SELECT coalesce(sum(q.amount_cents), 0) FROM dine.payments q WHERE q.check_id = c.id AND q.refund_status IS NULL), 0),
               'created_at', p.created_at) ORDER BY p.created_at DESC)
        FROM dine.payments p JOIN dine.checks c ON c.id = p.check_id JOIN dine.tables t ON t.id = c.table_id
       WHERE c.org_id = dine_device_org() AND p.method = 'ONLINE' AND p.refund_status IS NULL
         AND p.created_at > now() - interval '30 minutes'), '[]'::jsonb))
$function$;

REVOKE ALL ON FUNCTION public.scan_call_state(TEXT) FROM public;
REVOKE ALL ON FUNCTION public.scan_call_waiter(TEXT, TEXT) FROM public;
REVOKE ALL ON FUNCTION public.scan_cancel_call(TEXT) FROM public;
GRANT EXECUTE ON FUNCTION public.scan_call_state(TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_call_waiter(TEXT, TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_cancel_call(TEXT) TO anon, authenticated;

REVOKE ALL ON FUNCTION public.dine_service_calls() FROM public, anon;
REVOKE ALL ON FUNCTION public.dine_ack_call(UUID, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.dine_service_calls() TO authenticated;
GRANT EXECUTE ON FUNCTION public.dine_ack_call(UUID, TEXT) TO authenticated;

COMMIT;
