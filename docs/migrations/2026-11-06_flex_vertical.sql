-- 2026-11-06: Aegis Flex gets its own vertical, FLEX (owner: "Flex 单独业务类型").
--
-- Until now Flex sold the store's VEND products and left devices.vertical_type alone, so on a
-- terminal shared with Aegis Wash (255) its registerDevice wrote Flex's version into
-- devices.app_version while vertical_type stayed WASH: guard_qr_payment_mode then read the
-- terminal as a Wash older than 1.1.6 and refused publishing "card + scan".
--
--   products / devices vertical_type CHECK   + 'FLEX'
--   device_set_vertical                      accepts 'FLEX' (Aegis Flex reports it at start,
--                                            with its version: the pair always says which app)
--   testsim_console                          a terminal's slots = its store's FLEX products
--   data                                     each store that has a terminal which ran Flex
--                                            (sold through testsim / a controller) gets a FLEX
--                                            copy of its active VEND products with a slot, and
--                                            the copies are added to its latest published
--                                            config -- the VEND rows stay for Aegis Vend.

BEGIN;

ALTER TABLE public.products DROP CONSTRAINT IF EXISTS products_vertical_type_check;
ALTER TABLE public.products ADD CONSTRAINT products_vertical_type_check
  CHECK (vertical_type IN ('WASH', 'LAUNDRY', 'EV', 'VEND', 'RETAIL', 'TIMER', 'FLEX'));

ALTER TABLE public.devices DROP CONSTRAINT IF EXISTS devices_vertical_type_check;
ALTER TABLE public.devices ADD CONSTRAINT devices_vertical_type_check
  CHECK (vertical_type IN ('WASH', 'LAUNDRY', 'EV', 'VEND', 'RETAIL', 'TIMER', 'FLEX'));

CREATE OR REPLACE FUNCTION public.device_set_vertical(p_vertical text)
RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
  v_sn TEXT;
  v_old TEXT;
BEGIN
  IF p_vertical IS NULL OR p_vertical NOT IN ('WASH', 'VEND', 'TIMER', 'EV', 'LAUNDRY', 'FLEX') THEN
    RETURN json_build_object('success', false, 'message', 'invalid_vertical');
  END IF;
  SELECT device_sn INTO v_sn FROM public.device_auth_map WHERE auth_user_id = auth.uid();
  IF v_sn IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'device_not_linked');
  END IF;
  SELECT vertical_type INTO v_old FROM public.devices WHERE sn = v_sn FOR UPDATE;
  IF v_old IS DISTINCT FROM p_vertical THEN
    UPDATE public.devices SET vertical_type = p_vertical WHERE sn = v_sn;
  END IF;
  RETURN json_build_object('success', true, 'sn', v_sn, 'vertical_type', p_vertical, 'changed', v_old IS DISTINCT FROM p_vertical);
END;
$$;

COMMENT ON FUNCTION public.device_set_vertical(text) IS
  'Device: report the Aegis app it runs (devices.vertical_type) on its own row; WASH/VEND/TIMER/EV/LAUNDRY/FLEX. Aegis Vend 2026-11-03, FLEX 2026-11-06.';

CREATE OR REPLACE FUNCTION public.testsim_console(p_hours INT DEFAULT 12) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, testsim AS $$
BEGIN
  IF NOT is_sys_admin() THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN jsonb_build_object(
    'now', now(),
    'controllers', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'device_sn', c.device_sn, 'alias', d.alias, 'org_name', o.name,
               'ready', c.ready, 'message', c.message, 'last_seen', c.last_seen,
               'sold_out', to_jsonb(c.sold_out),
               'slots', coalesce((
                 SELECT jsonb_agg(jsonb_build_object('slot', p.attributes ->> 'slot', 'name', p.name)
                                  ORDER BY length(p.attributes ->> 'slot'), p.attributes ->> 'slot')
                 FROM products p
                 WHERE p.org_id = d.org_id AND p.vertical_type = 'FLEX' AND p.is_active AND p.price_cents > 0
                   AND coalesce(p.attributes ->> 'slot', '') <> ''), '[]'::jsonb))
             ORDER BY c.last_seen DESC NULLS LAST)
      FROM testsim.controllers c JOIN devices d ON d.sn = c.device_sn LEFT JOIN organizations o ON o.id = d.org_id
    ), '[]'::jsonb),
    'requests', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'id', r.id, 'device_sn', r.device_sn, 'alias', d.alias, 'kind', r.kind, 'ref', r.ref,
               'payload', r.payload, 'deadline', r.deadline, 'answer', r.answer,
               'answered_at', r.answered_at, 'created_at', r.created_at)
             ORDER BY r.created_at DESC)
      FROM (SELECT * FROM testsim.requests WHERE created_at > now() - make_interval(hours => p_hours)
            ORDER BY created_at DESC LIMIT 100) r
      JOIN devices d ON d.sn = r.device_sn
    ), '[]'::jsonb));
END $$;

-- Data: the stores whose terminals ran Flex (they talked to the test simulator) and have no FLEX
-- products yet get FLEX copies of their VEND items with a slot.
CREATE TEMP TABLE flex_copies ON COMMIT DROP AS
WITH orgs AS (
  SELECT DISTINCT d.org_id FROM testsim.controllers c JOIN public.devices d ON d.sn = c.device_sn
  WHERE d.org_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM public.products f WHERE f.org_id = d.org_id AND f.vertical_type = 'FLEX')
), ins AS (
  INSERT INTO public.products (org_id, name, price_cents, image_url, vertical_type, attributes, is_active)
  SELECT p.org_id, p.name, p.price_cents, p.image_url, 'FLEX', p.attributes, p.is_active
  FROM public.products p JOIN orgs USING (org_id)
  WHERE p.vertical_type = 'VEND' AND p.is_active AND coalesce(p.attributes ->> 'slot', '') <> ''
  RETURNING *
)
SELECT * FROM ins;

-- ... and onto each store's latest published config, so terminals see them without a republish.
UPDATE public.app_configurations a
SET products = coalesce(a.products, '[]'::jsonb)
             || (SELECT jsonb_agg(to_jsonb(f)) FROM flex_copies f WHERE f.org_id = a.org_id)
WHERE a.id IN (SELECT DISTINCT ON (org_id) id FROM public.app_configurations
               WHERE org_id IN (SELECT org_id FROM flex_copies) ORDER BY org_id, created_at DESC);

SELECT org_id, count(*) AS flex_products FROM flex_copies GROUP BY org_id;

COMMIT;
