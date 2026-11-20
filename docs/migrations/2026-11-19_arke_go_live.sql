-- Arke go-live (2026-11-19, docs/arke_go_live.md):
--   1. online_menu tells the customer site the store's accept_minutes, so the refund policy page
--      (?s=<slug>&p=refund) states the store's real number instead of a made-up one;
--   2. cmp_online_ready(p_org): the store's "ready to go live?" self-check for Cael's Online Ordering
--      page -- each line ok / warn / todo from what the database actually holds (no guessing).

-- 1 ----------------------------------------------------------------------------------------------
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
      -- A paid order nobody accepts in this many minutes is cancelled and refunded (refund policy page).
      'accept_minutes', s.accept_minutes,
      -- Store rewards (loyalty points by phone number), when the store has them on.
      'points', (SELECT jsonb_build_object('per_dollar', ms.points_per_dollar, 'reward_points', ms.reward_points, 'reward_cents', ms.reward_cents)
                   FROM member.settings ms WHERE ms.org_id = s.org_id AND ms.enabled),
      'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a
                             WHERE a.org_id = s.org_id AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE'),
      -- Ordering ahead: the bookable slots per way of getting the order (empty = none).
      'schedule_days', s.schedule_days,
      'slots', jsonb_build_object('PICKUP', to_jsonb(online.slot_times(s, 'PICKUP')),
                                  'OWN_DELIVERY', to_jsonb(online.slot_times(s, 'OWN_DELIVERY'))),
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
$function$;

-- 2 ----------------------------------------------------------------------------------------------
-- One line per check: {key, level: ok | warn | todo, ...facts for the words}. "todo" = don't go live
-- before fixing it; "warn" = works, but look at it. The words live in Cael (online.ready.*).
CREATE OR REPLACE FUNCTION public.cmp_online_ready(p_org uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE
  v_s online.stores%ROWTYPE;
  v_phone text; v_days int; v_menu int; v_free int; v_env text; v_pay text;
  v_seen timestamptz; v_mail text; v_mail_at timestamptz; v_zones int; v_tests int;
  r jsonb := '[]'::jsonb;
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501'; END IF;
  SELECT * INTO v_s FROM online.stores WHERE org_id = p_org;
  IF NOT FOUND THEN
    RETURN jsonb_build_array(jsonb_build_object('key', 'store', 'level', 'todo'));
  END IF;
  r := r || jsonb_build_object('key', 'store', 'level', 'ok', 'slug', v_s.slug);

  -- The customer's contact for the order, the policy pages and the order emails.
  v_phone := coalesce(nullif(trim(v_s.phone), ''), (SELECT nullif(trim(support_phone), '') FROM organizations WHERE id = p_org));
  r := r || jsonb_build_object('key', 'contact',
    'level', CASE WHEN v_phone IS NOT NULL AND nullif(trim(v_s.address), '') IS NOT NULL THEN 'ok' ELSE 'todo' END,
    'phone', v_phone IS NOT NULL, 'address', nullif(trim(v_s.address), '') IS NOT NULL);

  -- online.open_now: no hours = open around the clock (works, but rarely meant); hours with no day = never open.
  IF v_s.hours IS NULL OR jsonb_typeof(v_s.hours) <> 'object' THEN
    r := r || jsonb_build_object('key', 'hours', 'level', 'warn', 'days', NULL, 'always', true);
  ELSE
    SELECT count(*) INTO v_days FROM jsonb_each(v_s.hours) d
     WHERE jsonb_typeof(d.value) = 'array' AND jsonb_array_length(d.value) > 0;
    r := r || jsonb_build_object('key', 'hours', 'level', CASE WHEN v_days > 0 THEN 'ok' ELSE 'todo' END, 'days', v_days);
  END IF;

  -- The same dishes online_menu shows.
  SELECT count(*), count(*) FILTER (WHERE coalesce(p.price_cents, 0) <= 0) INTO v_menu, v_free
    FROM products p
   WHERE p.org_id = p_org AND p.is_active AND p.vertical_type = 'RETAIL'
     AND coalesce((p.attributes->>'online_hidden')::BOOLEAN, false) = false;
  r := r || jsonb_build_object('key', 'menu',
    'level', CASE WHEN v_menu = 0 THEN 'todo' WHEN v_free > 0 THEN 'warn' ELSE 'ok' END, 'dishes', v_menu, 'no_price', v_free);

  -- Card payments: an ACTIVE Nuvei account, and the live one (env prod), not the sandbox.
  SELECT a.env, a.status INTO v_env, v_pay FROM merchant_acquirer_accounts a
   WHERE a.org_id = p_org AND a.acquirer = 'nuvei' ORDER BY (a.status = 'ACTIVE') DESC, a.created_at DESC LIMIT 1;
  r := r || jsonb_build_object('key', 'payment',
    'level', CASE WHEN v_pay = 'ACTIVE' AND v_env = 'prod' THEN 'ok' ELSE 'todo' END,
    'status', v_pay, 'env', v_env);

  -- Someone to take the orders: an Ourea / Iris of this store seen lately (Cael's order desk leaves no trace).
  SELECT max(d.last_seen) INTO v_seen FROM devices d WHERE d.org_id = p_org AND d.vertical_type = 'RETAIL' AND d.is_active;
  r := r || jsonb_build_object('key', 'device',
    'level', CASE WHEN v_seen > now() - interval '15 minutes' THEN 'ok' ELSE 'warn' END, 'last_seen', v_seen);

  -- Order emails: the last one this store's customers were sent.
  SELECT p.mail_status, p.mail_at INTO v_mail, v_mail_at FROM online.payments p JOIN online.orders o ON o.id = p.order_id
   WHERE o.org_id = p_org AND p.mail_status IS NOT NULL ORDER BY p.mail_at DESC NULLS LAST LIMIT 1;
  r := r || jsonb_build_object('key', 'mail',
    'level', CASE WHEN v_mail = 'SENT' THEN 'ok' ELSE 'warn' END, 'status', left(v_mail, 120), 'at', v_mail_at);

  IF v_s.delivery_enabled THEN
    SELECT count(*) INTO v_zones FROM online.delivery_zones z WHERE z.org_id = p_org;
    r := r || jsonb_build_object('key', 'delivery', 'level', CASE WHEN v_zones > 0 THEN 'ok' ELSE 'warn' END, 'zones', v_zones);
  END IF;

  r := r || jsonb_build_object('key', 'accept', 'level', 'ok', 'minutes', v_s.accept_minutes);

  -- A paid test order handled end to end (picked up / delivered) before real customers.
  SELECT count(*) INTO v_tests FROM online.orders o
   WHERE o.org_id = p_org AND o.channel = 'ARKE' AND o.status IN ('PICKED_UP', 'DELIVERED');
  r := r || jsonb_build_object('key', 'test_order', 'level', CASE WHEN v_tests > 0 THEN 'ok' ELSE 'warn' END, 'done', v_tests);

  r := r || jsonb_build_object('key', 'open', 'level', CASE WHEN v_s.is_open THEN 'ok' ELSE 'warn' END);
  r := r || jsonb_build_object('key', 'review', 'level', CASE WHEN nullif(trim(v_s.review_url), '') IS NOT NULL THEN 'ok' ELSE 'warn' END);
  RETURN r;
END $function$;

REVOKE ALL ON FUNCTION public.cmp_online_ready(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_online_ready(uuid) TO authenticated;
