-- 2026-11-05: sold out per product (owner: "a sold-out 3-bag tile goes grey, 1 and 2 still sell").
-- A real controller says it in GET /flex/v1/status ("sold_out": ["3"], docs/flex_controller_api.md);
-- the Cael test simulator says it here: testsim.controllers.sold_out = the slots that are out,
-- set on the page per terminal. testsim_console also lists each terminal's slots to pick from
-- (its store's active VEND products with a price -- what Aegis Flex sells, attributes.slot).

BEGIN;

ALTER TABLE testsim.controllers ADD COLUMN IF NOT EXISTS sold_out TEXT[] NOT NULL DEFAULT '{}';
COMMENT ON COLUMN testsim.controllers.sold_out IS
  'Slots (products.attributes.slot) the simulated machine has run out of: those tiles go grey on the terminal.';

CREATE OR REPLACE FUNCTION public.testsim_status() RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, testsim AS $$
DECLARE v_sn TEXT := testsim_device_sn(); c testsim.controllers;
BEGIN
  IF v_sn IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_linked'); END IF;
  INSERT INTO testsim.controllers (device_sn, last_seen) VALUES (v_sn, now())
  ON CONFLICT (device_sn) DO UPDATE SET last_seen = now()
  RETURNING * INTO c;
  RETURN jsonb_build_object('success', true, 'ready', c.ready, 'message', c.message, 'sold_out', to_jsonb(c.sold_out));
END $$;

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
                 WHERE p.org_id = d.org_id AND p.vertical_type = 'VEND' AND p.is_active AND p.price_cents > 0
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

-- The slots a terminal's simulated machine has run out of (empty = everything in stock).
CREATE OR REPLACE FUNCTION public.testsim_set_sold_out(p_sn TEXT, p_slots TEXT[]) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, testsim AS $$
BEGIN
  IF NOT is_sys_admin() THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  INSERT INTO testsim.controllers (device_sn, sold_out, updated_at) VALUES (p_sn, coalesce(p_slots, '{}'), now())
  ON CONFLICT (device_sn) DO UPDATE SET sold_out = coalesce(p_slots, '{}'), updated_at = now();
  RETURN jsonb_build_object('success', true);
END $$;

REVOKE ALL ON FUNCTION public.testsim_set_sold_out(TEXT, TEXT[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.testsim_set_sold_out(TEXT, TEXT[]) TO authenticated;

COMMIT;
