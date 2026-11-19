-- 2026-11-19: Arke asks for a Google review after the order is done (design phase 4).
--
-- The store pastes its review link in Cael (Google Business Profile > "Ask for reviews", e.g.
-- https://g.page/r/<id>/review); every order page shows "Leave a Google review" -- whatever the order's state
-- (owner 2026-11-19: as few limits as possible). Every customer sees the same button -- no stars first and no
-- sending only the happy ones to Google (Google's policy forbids that "review gating").

ALTER TABLE online.stores ADD COLUMN IF NOT EXISTS review_url TEXT
  CHECK (review_url IS NULL OR (review_url ~ '^https://[^\s/]+\.[^\s]+$' AND length(review_url) <= 500));
COMMENT ON COLUMN online.stores.review_url IS 'Where the order page asks for a review once the order is done (Google review link); NULL = not asked';

-- Cael: same permission as the other online settings.
CREATE OR REPLACE FUNCTION public.cmp_online_review_save(p_org UUID, p_url TEXT)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, online AS $$
DECLARE v_url TEXT := nullif(trim(coalesce(p_url, '')), '');
BEGIN
  IF NOT (is_sys_admin() OR (p_org IN (SELECT member_org_ids()) AND has_permission('catalog.manage'))) THEN
    RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
  END IF;
  IF v_url IS NOT NULL AND (v_url !~ '^https://[^\s/]+\.[^\s]+$' OR length(v_url) > 500) THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_url');
  END IF;
  UPDATE online.stores SET review_url = v_url, updated_at = now() WHERE org_id = p_org;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'no_store'); END IF;
  RETURN jsonb_build_object('success', true);
END $$;
REVOKE ALL ON FUNCTION public.cmp_online_review_save(UUID, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cmp_online_review_save(UUID, TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION public.cmp_online_store_get(p_org uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_s online.stores%ROWTYPE;
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501'; END IF;
  SELECT * INTO v_s FROM online.stores WHERE org_id = p_org;
  IF NOT FOUND THEN RETURN NULL; END IF;
  RETURN jsonb_build_object('slug', v_s.slug, 'is_open', v_s.is_open, 'open_now', online.open_now(v_s),
    'pickup_minutes', v_s.pickup_minutes, 'hours', v_s.hours, 'timezone', v_s.timezone,
    'address', v_s.address, 'phone', v_s.phone, 'notice', v_s.notice, 'schedule_days', v_s.schedule_days,
    'review_url', v_s.review_url,
    'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a WHERE a.org_id = p_org AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE'),
    'delivery_enabled', v_s.delivery_enabled, 'delivery_minutes', v_s.delivery_minutes, 'delivery_tax_bps', v_s.delivery_tax_bps,
    'zones', coalesce((SELECT jsonb_agg(jsonb_build_object('id', z.id, 'name', z.name, 'postal_prefixes', to_jsonb(z.postal_prefixes),
                         'fee_cents', z.fee_cents, 'min_order_cents', z.min_order_cents) ORDER BY z.sort, z.created_at)
                         FROM online.delivery_zones z WHERE z.org_id = p_org), '[]'::jsonb));
END $function$;

-- The customer's order page: the store's review link, always.
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
                                'pickup_minutes', v_s.pickup_minutes, 'delivery_minutes', v_s.delivery_minutes, 'timezone', v_s.timezone,
                                'review_url', v_s.review_url),
    'refund', (SELECT p.status FROM online.payments p WHERE p.order_id = v_o.id AND p.status IN ('REFUNDING', 'VOIDED', 'REFUNDED')
                ORDER BY p.created_at DESC LIMIT 1));
END $function$;
