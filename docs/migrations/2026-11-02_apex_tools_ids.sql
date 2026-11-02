-- 2026-11-02: 小海狸 2.0 P2 (docs/apex_agent_design.md): the journal and stock tools also
-- return ids, so 小海狸 can propose "mark this to-do done", "tick this step", "receive
-- 12 of this product" -- and apex_tool_refs checks such ids belong to the store before
-- a card is shown. Nothing here writes: the terminal saves a card after the manager
-- confirms it, through its own repositories (as when done by hand).

CREATE OR REPLACE FUNCTION public.apex_tool_journal(p_org UUID, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, office AS $$
  WITH today AS (SELECT (now() AT TIME ZONE apex_tz(p_tz))::date AS d)
  SELECT jsonb_build_object(
    'todos_open', (SELECT coalesce(jsonb_agg(jsonb_build_object('id', id, 'text', text, 'for', coalesce(assignee_name, 'everyone'), 'set_by', staff_name,
        'due', to_char(due_at AT TIME ZONE apex_tz(p_tz), 'MM-DD HH24:MI'), 'overdue', due_at < now()) ORDER BY due_at), '[]')
        FROM (SELECT * FROM reminders WHERE org_id = p_org AND done_at IS NULL ORDER BY due_at LIMIT 20) r),
    'checklists_today', (SELECT coalesce(jsonb_agg(jsonb_build_object('name', c.name, 'due', to_char(c.due_time, 'HH24:MI'),
        'items', (SELECT count(*) FROM checklist_items i WHERE i.checklist_id = c.id AND i.is_active),
        'done', (SELECT count(DISTINCT k.item_id) FROM checklist_ticks k, today WHERE k.checklist_id = c.id AND k.business_day = today.d),
        'steps', (SELECT coalesce(jsonb_agg(jsonb_build_object('id', i.id, 'text', i.text,
                    'done', EXISTS (SELECT 1 FROM checklist_ticks k, today WHERE k.item_id = i.id AND k.business_day = today.d)) ORDER BY i.sort), '[]')
                    FROM checklist_items i WHERE i.checklist_id = c.id AND i.is_active))
        ORDER BY c.sort), '[]') FROM checklists c WHERE c.org_id = p_org AND c.is_active),
    'latest_notes', (SELECT coalesce(jsonb_agg(jsonb_build_object('title', title, 'text', left(content, 200), 'by', staff_name,
        'on', to_char(created_at AT TIME ZONE apex_tz(p_tz), 'MM-DD')) ORDER BY pinned DESC, created_at DESC), '[]')
        FROM (SELECT * FROM notes WHERE org_id = p_org ORDER BY pinned DESC, created_at DESC LIMIT 5) n))
$$;

CREATE OR REPLACE FUNCTION public.apex_tool_stock(p_org UUID, p_query TEXT DEFAULT NULL, p_low_only BOOLEAN DEFAULT false)
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT jsonb_build_object(
    'tracked', (SELECT count(*) FROM jsonb_array_elements(retail_stock_list_for(p_org)) e WHERE e->'stock_qty' <> 'null'::jsonb),
    'items', (SELECT coalesce(jsonb_agg(jsonb_build_object('product_id', e->>'id', 'name', e->>'name', 'category', e->>'category',
                'stock', (e->>'stock_qty')::int, 'low_at', (e->>'low_stock_qty')::int, 'sold_last_7_days', (e->>'sold_7d')::int,
                'days_left_at_this_pace', CASE WHEN (e->>'stock_qty')::int > 0 AND (e->>'sold_7d')::int > 0
                                               THEN round((e->>'stock_qty')::numeric / ((e->>'sold_7d')::numeric / 7), 1) END)), '[]')
                FROM (SELECT e FROM jsonb_array_elements(retail_stock_list_for(p_org)) e
                       WHERE (nullif(trim(p_query), '') IS NOT NULL OR e->'stock_qty' <> 'null'::jsonb)   -- a named product even if not tracked yet
                         AND (nullif(trim(p_query), '') IS NULL OR e->>'name' ILIKE '%' || trim(p_query) || '%' OR e->>'barcode' = trim(p_query))
                         AND (NOT coalesce(p_low_only, false) OR (e->>'stock_qty')::int <= coalesce((e->>'low_stock_qty')::int, 0))
                       ORDER BY coalesce((e->>'stock_qty')::int, 1000000) - coalesce((e->>'low_stock_qty')::int, 0), e->>'name'
                       LIMIT 30) x))
$$;

-- Ids 小海狸 put on a card, checked against the store: to-dos (open), checklist steps
-- (active), products (active). Returns the ones that are the store's, with their text.
CREATE OR REPLACE FUNCTION public.apex_tool_refs(p_org UUID, p_kind TEXT, p_ids UUID[])
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, office AS $$
  SELECT coalesce(jsonb_agg(x), '[]') FROM (
    SELECT jsonb_build_object('id', r.id, 'text', r.text) AS x FROM reminders r
     WHERE p_kind = 'todo' AND r.org_id = p_org AND r.done_at IS NULL AND r.id = ANY(p_ids)
    UNION ALL
    SELECT jsonb_build_object('id', i.id, 'text', i.text, 'checklist', c.name) FROM checklist_items i JOIN checklists c ON c.id = i.checklist_id
     WHERE p_kind = 'step' AND i.org_id = p_org AND i.is_active AND i.id = ANY(p_ids)
    UNION ALL
    SELECT jsonb_build_object('id', p.id, 'text', p.name, 'stock', p.stock_qty) FROM products p
     WHERE p_kind = 'product' AND p.org_id = p_org AND p.is_active AND p.id = ANY(p_ids)) y
$$;

REVOKE ALL ON FUNCTION public.apex_tool_refs(UUID, TEXT, UUID[]) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apex_tool_refs(UUID, TEXT, UUID[]) TO service_role;

NOTIFY pgrst, 'reload schema';
