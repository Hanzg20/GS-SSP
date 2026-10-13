-- 2026-10-13: the customer AI guide on Ourea Scan (supabase/functions/scan-assistant).
--
-- Dish facts the guide may use, in products.attributes (all optional):
--   description   TEXT   what the dish is, in the store's words
--   ingredients   TEXT   main ingredients
--   allergens     TEXT[] Canada's priority allergens it CONTAINS:
--                        peanut, tree_nut, milk, egg, wheat, soy, fish, crustacean,
--                        mollusc, sesame, mustard, sulphite
--                        ([] = none of them; key absent = NOT RECORDED)
--   dietary       TEXT[] vegetarian, vegan, halal, gluten_free, no_pork, no_beef ...
--   spice_level   INT    0 (none) .. 5
--   guide         TEXT   how to eat it / what it pairs with
-- The guide only repeats these; a dish without `allergens` is "not recorded",
-- and allergy answers always send the customer to staff to confirm.
--
-- dine.scan_chats: every question and answer (rate limits, and the owner can
-- see what customers ask). Written only by the edge function (service role).

BEGIN;

CREATE TABLE IF NOT EXISTS dine.scan_chats (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  table_id    UUID NOT NULL REFERENCES dine.tables(id) ON DELETE CASCADE,
  question    TEXT NOT NULL,
  reply       TEXT,
  language    TEXT,           -- the customer's language as the model saw it (BCP 47)
  model       TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS scan_chats_table_time_idx ON dine.scan_chats (table_id, created_at);
CREATE INDEX IF NOT EXISTS scan_chats_org_time_idx ON dine.scan_chats (org_id, created_at);
COMMENT ON TABLE dine.scan_chats IS 'Customer questions to the Ourea Scan AI guide and its replies (edge function scan-assistant)';
ALTER TABLE dine.scan_chats ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON dine.scan_chats FROM public, anon, authenticated;

-- The menu now carries the dish facts.
CREATE OR REPLACE FUNCTION public.scan_menu(p_token TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object(
    'table', jsonb_build_object('name', t.name, 'area', t.area),
    'store', jsonb_build_object('name', o.name),
    'products', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'id', p.id, 'name', p.name, 'price_cents', p.price_cents,
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

-- Edge function only: may this table ask now? (per table and per store limits)
CREATE OR REPLACE FUNCTION public.scan_chat_allowed(p_token TEXT, p_per_10min INT, p_store_per_day INT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT CASE WHEN t.id IS NULL THEN jsonb_build_object('allowed', false, 'reason', 'unknown_table')
    WHEN (SELECT count(*) FROM dine.scan_chats c WHERE c.table_id = t.id AND c.created_at > now() - interval '10 minutes') >= p_per_10min
      THEN jsonb_build_object('allowed', false, 'reason', 'table_limit')
    WHEN (SELECT count(*) FROM dine.scan_chats c WHERE c.org_id = t.org_id AND c.created_at > now() - interval '1 day') >= p_store_per_day
      THEN jsonb_build_object('allowed', false, 'reason', 'store_limit')
    ELSE jsonb_build_object('allowed', true) END
    FROM (SELECT NULL) x LEFT JOIN dine.tables t ON t.qr_token = p_token AND t.is_active
$$;

CREATE OR REPLACE FUNCTION public.scan_chat_record(p_token TEXT, p_question TEXT, p_reply TEXT, p_language TEXT, p_model TEXT) RETURNS VOID
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
  INSERT INTO dine.scan_chats (org_id, table_id, question, reply, language, model)
  SELECT t.org_id, t.id, left(p_question, 1000), left(p_reply, 4000), left(p_language, 20), p_model
    FROM dine.tables t WHERE t.qr_token = p_token
$$;

REVOKE ALL ON FUNCTION public.scan_chat_allowed(TEXT, INT, INT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.scan_chat_record(TEXT, TEXT, TEXT, TEXT, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_chat_allowed(TEXT, INT, INT) TO service_role;
GRANT EXECUTE ON FUNCTION public.scan_chat_record(TEXT, TEXT, TEXT, TEXT, TEXT) TO service_role;

COMMIT;
