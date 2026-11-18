-- Customer pages show the merchant's brand name (organizations.brand_name, what Cael's branding sets and
-- terminals print), not its internal Cael name; empty brand_name falls back to name. 2026-11-18: renaming test
-- merchants to "测试 · ..." showed the internal name on the Arke / table-ordering pages.
-- Same bodies as live, only the store-name expression changes.

CREATE OR REPLACE FUNCTION public.online_menu(p_slug text)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
  SELECT jsonb_build_object(
    'store', jsonb_build_object('name', coalesce(nullif(trim(o.brand_name), ''), o.name), 'logo_url', o.logo_url, 'slug', s.slug,
      'open_now', online.open_now(s), 'is_open', s.is_open, 'hours', s.hours, 'timezone', s.timezone,
      'pickup_minutes', s.pickup_minutes, 'address', s.address, 'phone', coalesce(s.phone, o.support_phone),
      'notice', s.notice,
      'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a
                             WHERE a.org_id = s.org_id AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE'),
      -- Delivery: on only with at least one zone; the cheapest fee / lowest minimum for "from $x".
      'delivery', CASE WHEN s.delivery_enabled AND EXISTS (SELECT 1 FROM online.delivery_zones z WHERE z.org_id = s.org_id)
        THEN jsonb_build_object('minutes', s.delivery_minutes,
          'fee_from_cents', (SELECT min(fee_cents) FROM online.delivery_zones z WHERE z.org_id = s.org_id),
          'min_order_from_cents', (SELECT min(min_order_cents) FROM online.delivery_zones z WHERE z.org_id = s.org_id),
          'tax_bps', s.delivery_tax_bps) END),
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
       WHERE p.org_id = s.org_id AND p.is_active AND p.vertical_type = 'RETAIL'
         AND coalesce((p.attributes->>'online_hidden')::BOOLEAN, false) = false), '[]'::jsonb))
    FROM online.stores s JOIN organizations o ON o.id = s.org_id
   WHERE s.slug = lower(trim(p_slug))
$function$
;

CREATE OR REPLACE FUNCTION public.online_order_status(p_token text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$

DECLARE v_o online.orders%ROWTYPE; v_s online.stores%ROWTYPE;

BEGIN

  UPDATE online.orders SET status = 'EXPIRED'

   WHERE public_token = p_token AND status = 'AWAITING_PAYMENT' AND created_at < now() - interval '30 minutes'

     AND NOT EXISTS (SELECT 1 FROM online.payments p WHERE p.order_id = orders.id AND p.status = 'OPEN' AND p.created_at > now() - interval '15 minutes');

  SELECT * INTO v_o FROM online.orders WHERE public_token = p_token AND channel = 'ARKE';

  IF NOT FOUND THEN RETURN NULL; END IF;

  SELECT * INTO v_s FROM online.stores WHERE org_id = v_o.org_id;

  RETURN online.order_json(v_o, false) || jsonb_build_object(

    'store', jsonb_build_object('name', (SELECT coalesce(nullif(trim(brand_name), ''), name) FROM organizations WHERE id = v_o.org_id),

                                'logo_url', (SELECT logo_url FROM organizations WHERE id = v_o.org_id),

                                'slug', v_s.slug, 'address', v_s.address,

                                'phone', coalesce(v_s.phone, (SELECT support_phone FROM organizations WHERE id = v_o.org_id)),

                                'pickup_minutes', v_s.pickup_minutes, 'delivery_minutes', v_s.delivery_minutes, 'timezone', v_s.timezone),

    'refund', (SELECT p.status FROM online.payments p WHERE p.order_id = v_o.id AND p.status IN ('REFUNDING', 'VOIDED', 'REFUNDED')

                ORDER BY p.created_at DESC LIMIT 1));

END $function$
;

CREATE OR REPLACE FUNCTION public.online_pay_settle(p_id uuid, p_transaction_id text, p_paid_cents integer, p_auth_code text, p_card text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_p online.payments%ROWTYPE; v_o online.orders%ROWTYPE; v_s online.stores%ROWTYPE; v_day DATE; v_no INT; v_code TEXT;
BEGIN
  SELECT * INTO v_p FROM online.payments WHERE id = p_id FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'payment_not_found'); END IF;
  IF v_p.status IN ('PAID', 'REFUNDING', 'VOIDED', 'REFUNDED') THEN RETURN jsonb_build_object('success', true, 'duplicate', true); END IF;
  IF coalesce(trim(p_transaction_id), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_transaction'); END IF;
  IF p_paid_cents IS DISTINCT FROM v_p.amount_cents + v_p.tip_cents THEN
    UPDATE online.payments SET reason = 'amount_mismatch: charged ' || coalesce(p_paid_cents::TEXT, '?') || ' tx ' || trim(p_transaction_id)
     WHERE id = p_id;
    RETURN jsonb_build_object('success', false, 'message', 'amount_mismatch');
  END IF;
  SELECT * INTO v_o FROM online.orders WHERE id = v_p.order_id FOR UPDATE;
  IF v_o.status NOT IN ('AWAITING_PAYMENT', 'EXPIRED') THEN
    -- The order was already paid by another attempt: this money is extra -- a person refunds it.
    UPDATE online.payments SET status = 'PAID', paid_at = now(), transaction_id = trim(p_transaction_id),
           auth_code = nullif(trim(p_auth_code), ''), card = nullif(trim(p_card), ''), reason = 'second_payment'
     WHERE id = p_id;
    RETURN jsonb_build_object('success', false, 'message', 'second_payment');
  END IF;

  UPDATE online.payments SET status = 'PAID', paid_at = now(), transaction_id = trim(p_transaction_id),
         auth_code = nullif(trim(p_auth_code), ''), card = nullif(trim(p_card), ''), reason = NULL
   WHERE id = p_id;

  SELECT * INTO v_s FROM online.stores WHERE org_id = v_o.org_id FOR UPDATE;
  v_day := (now() AT TIME ZONE v_s.timezone)::DATE;
  v_no := CASE WHEN v_s.last_pickup_day = v_day THEN v_s.last_pickup_no + 1 ELSE 1 END;
  UPDATE online.stores SET last_pickup_day = v_day, last_pickup_no = v_no WHERE org_id = v_s.org_id;
  UPDATE online.orders SET status = 'NEW', paid_at = now(), tip_cents = v_p.tip_cents, pickup_no = v_no, pickup_day = v_day
   WHERE id = v_o.id;
  v_code := CASE WHEN v_o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END || v_no;
  RETURN jsonb_build_object('success', true, 'pickup_code', v_code,
    'mail', jsonb_build_object('email', v_p.email, 'lang', v_p.lang, 'token', v_o.public_token, 'code', v_code,
      'fulfilment', v_o.fulfilment, 'store', (SELECT coalesce(nullif(trim(brand_name), ''), name) FROM organizations WHERE id = v_o.org_id),
      'total_cents', v_p.amount_cents + v_p.tip_cents));
END $function$
;

CREATE OR REPLACE FUNCTION public.scan_menu(p_token text)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$
  SELECT jsonb_build_object(
    'table', jsonb_build_object('name', t.name, 'area', t.area),
    'store', jsonb_build_object('name', coalesce(nullif(trim(o.brand_name), ''), o.name), 'logo_url', o.logo_url),
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
$function$
;

NOTIFY pgrst, 'reload schema';
