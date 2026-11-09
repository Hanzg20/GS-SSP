-- 2026-11-08: what customers ask Beaver, for the owner (Ourea Scan AI plan step 3).
-- Owner: "顾客问小海狸的问题本身就是独有的数据" -- other systems only know what sold; we also know
-- what people wanted.
--
-- * dine.scan_chats gains kind (ask / order / plan), product_ids (dishes Beaver suggested or
--   heard) and missing (things asked for that aren't on the menu). scan_chat_record takes them
--   (defaults keep the old 5-argument call working).
-- * apex_tool_guest_asks(org, from, to): the summary 小海狸 (Apex) reads for "what are customers
--   asking?": counts, languages, topics (allergy, vegetarian, halal, gluten, spicy, kids) against
--   how many dishes are labelled for them, dishes talked about vs sold, things not on the menu,
--   dishes talked about with no photo, and the latest questions themselves.
-- * apex_alerts_for gains two fixed-rule alerts (last 7 days, no model):
--     guest_missing  something not on the menu asked for 2+ times        names = what they said
--     guest_diet     a diet asked about 3+ times, no dish labelled for it  names = vegetarian / halal / gluten_free
--   Same function as 2026-11-02_apex_alerts.sql otherwise (checked identical to prod before replacing).

BEGIN;

ALTER TABLE dine.scan_chats ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'ask';
ALTER TABLE dine.scan_chats ADD COLUMN IF NOT EXISTS product_ids UUID[] NOT NULL DEFAULT '{}';
ALTER TABLE dine.scan_chats ADD COLUMN IF NOT EXISTS missing TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX IF NOT EXISTS scan_chats_org_time ON dine.scan_chats (org_id, created_at DESC);
COMMENT ON COLUMN dine.scan_chats.kind IS 'ask = menu question, order = one-sentence order, plan = table picks / is it enough';
COMMENT ON COLUMN dine.scan_chats.product_ids IS 'dishes Beaver suggested or heard (only real menu ids)';
COMMENT ON COLUMN dine.scan_chats.missing IS 'what the customer asked for that is not on the menu (or sold out), in their words';

DROP FUNCTION IF EXISTS public.scan_chat_record(TEXT, TEXT, TEXT, TEXT, TEXT);
CREATE OR REPLACE FUNCTION public.scan_chat_record(p_token TEXT, p_question TEXT, p_reply TEXT, p_language TEXT, p_model TEXT,
  p_kind TEXT DEFAULT 'ask', p_product_ids UUID[] DEFAULT '{}', p_missing TEXT[] DEFAULT '{}') RETURNS VOID
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
  INSERT INTO dine.scan_chats (org_id, table_id, question, reply, language, model, kind, product_ids, missing)
  SELECT t.org_id, t.id, left(p_question, 1000), left(p_reply, 4000), left(p_language, 20), p_model,
         CASE WHEN p_kind IN ('ask', 'order', 'plan') THEN p_kind ELSE 'ask' END,
         coalesce(p_product_ids[1:12], '{}'),
         coalesce((SELECT array_agg(left(trim(m), 60)) FROM unnest(p_missing[1:5]) m WHERE trim(m) <> ''), '{}')
    FROM dine.tables t WHERE t.qr_token = p_token
$$;
REVOKE ALL ON FUNCTION public.scan_chat_record(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, UUID[], TEXT[]) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_chat_record(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, UUID[], TEXT[]) TO service_role;

-- Which topics a question is about (zh / en / fr / ar words; a question can hit several).
CREATE OR REPLACE FUNCTION dine.guest_topics(q TEXT) RETURNS TEXT[]
LANGUAGE sql IMMUTABLE AS $$
  SELECT array_remove(ARRAY[
    CASE WHEN q ~* '过敏|allerg|حساسية' THEN 'allergy' END,
    CASE WHEN q ~* '素食|吃素|素菜|纯素|vegetarian|vegan|végé|نباتي' THEN 'vegetarian' END,
    CASE WHEN q ~* '清真|halal|حلال' THEN 'halal' END,
    CASE WHEN q ~* '麸质|gluten|غلوتين' THEN 'gluten_free' END,
    CASE WHEN q ~* '辣|spicy|épic|piquant|حار' THEN 'spicy' END,
    CASE WHEN q ~* '小孩|儿童|孩子|kid|child|enfant|طفل|أطفال' THEN 'kids' END
  ], NULL)
$$;

CREATE OR REPLACE FUNCTION public.apex_tool_guest_asks(p_org UUID, p_from DATE, p_to DATE, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  WITH c AS (
    SELECT * FROM dine.scan_chats
     WHERE org_id = p_org
       AND (created_at AT TIME ZONE apex_tz(p_tz))::date BETWEEN p_from AND p_to
  ),
  menu AS (SELECT p.id, p.name, p.attributes FROM products p WHERE p.org_id = p_org AND p.is_active AND p.vertical_type = 'RETAIL'),
  topics AS (
    SELECT t AS topic, count(*) AS n FROM c, unnest(dine.guest_topics(c.question)) t WHERE c.kind <> 'plan' GROUP BY t
  ),
  talked AS (SELECT pid, count(*) AS n FROM c, unnest(c.product_ids) pid GROUP BY pid),
  sold AS (
    SELECT i.product_id AS pid, sum(i.quantity) AS n FROM dine.check_items i JOIN dine.checks k ON k.id = i.check_id
     WHERE k.org_id = p_org AND i.status <> 'VOID' AND (i.sent_at AT TIME ZONE apex_tz(p_tz))::date BETWEEN p_from AND p_to
     GROUP BY i.product_id
  ),
  miss AS (SELECT lower(m) AS what, count(*) AS n FROM c, unnest(c.missing) m GROUP BY lower(m))
  SELECT jsonb_build_object(
    'from', p_from, 'to', p_to,
    'questions', (SELECT count(*) FROM c WHERE kind = 'ask'),
    'voice_orders', (SELECT count(*) FROM c WHERE kind = 'order'),
    'table_plans', (SELECT count(*) FROM c WHERE kind = 'plan'),
    'tables', (SELECT count(DISTINCT table_id) FROM c),
    'languages', coalesce((SELECT jsonb_object_agg(l, n) FROM (SELECT coalesce(nullif(split_part(language, '-', 1), ''), '?') l, count(*) n FROM c GROUP BY 1) x), '{}'),
    'topics', coalesce((SELECT jsonb_agg(jsonb_build_object('topic', topic, 'asked', n,
        'dishes_labelled', CASE WHEN topic IN ('vegetarian', 'halal', 'gluten_free')
          THEN (SELECT count(*) FROM menu WHERE menu.attributes->'dietary' ? topic OR (topic = 'vegetarian' AND menu.attributes->'dietary' ? 'vegan')) END)
        ORDER BY n DESC) FROM topics), '[]'),
    'dishes', coalesce((SELECT jsonb_agg(jsonb_build_object('name', m.name, 'talked_about', t.n, 'sold', coalesce(s.n, 0),
        'has_photo', coalesce(m.attributes->>'image_url', '') <> '') ORDER BY t.n DESC)
      FROM talked t JOIN menu m ON m.id = t.pid LEFT JOIN sold s ON s.pid = t.pid), '[]'),
    'not_on_menu', coalesce((SELECT jsonb_agg(jsonb_build_object('what', what, 'times', n) ORDER BY n DESC) FROM (SELECT * FROM miss ORDER BY n DESC LIMIT 15) x), '[]'),
    'latest_questions', coalesce((SELECT jsonb_agg(q ORDER BY at DESC) FROM (
        SELECT left(question, 200) q, created_at at FROM c WHERE kind IN ('ask', 'order') ORDER BY created_at DESC LIMIT 40) x), '[]')
  )
$$;
REVOKE ALL ON FUNCTION public.apex_tool_guest_asks(UUID, DATE, DATE, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apex_tool_guest_asks(UUID, DATE, DATE, TEXT) TO service_role;

CREATE OR REPLACE FUNCTION public.apex_alerts_for(p_org UUID, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, office AS $$
DECLARE
  tz TEXT := apex_tz(p_tz);
  now_local TIMESTAMP := now() AT TIME ZONE tz;
  out JSONB := '[]';
  r RECORD;
BEGIN
  SELECT count(*) AS n, coalesce(jsonb_agg(name) FILTER (WHERE rn <= 5), '[]') AS names INTO r FROM (
    SELECT e->>'name' AS name, row_number() OVER (ORDER BY (e->>'stock_qty')::int) AS rn
      FROM jsonb_array_elements(retail_stock_list_for(p_org)) e
     WHERE e->'stock_qty' <> 'null'::jsonb
       AND ((e->>'stock_qty')::int <= coalesce((e->>'low_stock_qty')::int, 0)
            OR ((e->>'sold_7d')::int > 0 AND (e->>'stock_qty')::numeric / ((e->>'sold_7d')::numeric / 7) < 3))) x;
  IF r.n > 0 THEN out := out || jsonb_build_object('kind', 'low_stock', 'count', r.n, 'names', r.names); END IF;

  SELECT count(*) AS n, coalesce(jsonb_agg(s.staff_name), '[]') AS names INTO r FROM office.shifts s
   WHERE s.org_id = p_org AND s.clock_out_at IS NULL AND s.clock_in_at < now() - INTERVAL '12 hours';
  IF r.n > 0 THEN out := out || jsonb_build_object('kind', 'long_shift', 'count', r.n, 'names', r.names); END IF;

  SELECT count(*) AS n, coalesce(jsonb_agg('D' || order_no), '[]') AS names INTO r FROM delivery_orders
   WHERE org_id = p_org AND status = 'OPEN' AND delivery_stage = 'OUT' AND out_at < now() - INTERVAL '2 hours';
  IF r.n > 0 THEN out := out || jsonb_build_object('kind', 'delivery_out', 'count', r.n, 'names', r.names); END IF;

  SELECT count(*) AS n INTO r FROM transactions
   WHERE device_sn IN (SELECT sn FROM devices WHERE org_id = p_org) AND offline_auth AND offline_status = 'PENDING_UPLOAD'
     AND created_at < now() - INTERVAL '30 minutes';
  IF r.n > 0 THEN out := out || jsonb_build_object('kind', 'offline_card', 'count', r.n, 'names', '[]'::jsonb); END IF;

  SELECT count(*) AS n, coalesce(jsonb_agg(c.name), '[]') AS names INTO r FROM checklists c
   WHERE c.org_id = p_org AND c.is_active AND c.due_time IS NOT NULL AND c.due_time < now_local::time
     AND EXISTS (SELECT 1 FROM checklist_items i WHERE i.checklist_id = c.id AND i.is_active
                   AND NOT EXISTS (SELECT 1 FROM checklist_ticks k WHERE k.item_id = i.id AND k.business_day = now_local::date));
  IF r.n > 0 THEN out := out || jsonb_build_object('kind', 'checklist_late', 'count', r.n, 'names', r.names); END IF;

  SELECT count(*) AS n, coalesce(jsonb_agg(text) FILTER (WHERE rn <= 5), '[]') AS names INTO r FROM (
    SELECT text, row_number() OVER (ORDER BY due_at) AS rn FROM reminders WHERE org_id = p_org AND done_at IS NULL AND due_at < now()) x;
  IF r.n > 0 THEN out := out || jsonb_build_object('kind', 'todo_overdue', 'count', r.n, 'names', r.names); END IF;

  SELECT jsonb_array_length(coalesce(apex_loss_signals(p_org, tz)->'flags', '[]')) AS n INTO r;
  IF r.n > 0 THEN out := out || jsonb_build_object('kind', 'loss_check', 'count', r.n, 'names', '[]'::jsonb); END IF;

  -- Customers asked Beaver for something the menu doesn't have, 2+ times this week.
  SELECT count(*) AS n, coalesce(jsonb_agg(what ORDER BY times DESC) FILTER (WHERE rn <= 5), '[]') AS names INTO r FROM (
    SELECT lower(m) AS what, count(*) AS times, row_number() OVER (ORDER BY count(*) DESC) AS rn
      FROM dine.scan_chats c, unnest(c.missing) m
     WHERE c.org_id = p_org AND c.created_at > now() - INTERVAL '7 days'
     GROUP BY lower(m) HAVING count(*) >= 2) x;
  IF r.n > 0 THEN out := out || jsonb_build_object('kind', 'guest_missing', 'count', r.n, 'names', r.names); END IF;

  -- A diet asked about 3+ times this week and not one dish labelled for it.
  SELECT count(*) AS n, coalesce(jsonb_agg(topic), '[]') AS names INTO r FROM (
    SELECT t AS topic FROM dine.scan_chats c, unnest(dine.guest_topics(c.question)) t
     WHERE c.org_id = p_org AND c.kind <> 'plan' AND c.created_at > now() - INTERVAL '7 days'
       AND t IN ('vegetarian', 'halal', 'gluten_free')
     GROUP BY t HAVING count(*) >= 3
        AND NOT EXISTS (SELECT 1 FROM products p WHERE p.org_id = p_org AND p.is_active AND p.vertical_type = 'RETAIL'
                          AND (p.attributes->'dietary' ? t OR (t = 'vegetarian' AND p.attributes->'dietary' ? 'vegan')))) x;
  IF r.n > 0 THEN out := out || jsonb_build_object('kind', 'guest_diet', 'count', r.n, 'names', r.names); END IF;

  RETURN out;
END;
$$;

COMMIT;

-- Backfill (run once, 2026-11-08): rows written before the kind column carried it as a prefix.
-- UPDATE dine.scan_chats SET kind = 'order', question = substr(question, 9) WHERE question LIKE '[order] %';
-- UPDATE dine.scan_chats SET kind = 'plan' WHERE question LIKE '[plan:%';
