-- 2026-11-02: 小海狸 2.0 P3 (docs/apex_agent_design.md): things worth a look, found by
-- fixed rules (no model): the terminal polls apex_alerts() and 小海狸 holds up a sign
-- with how many; tapping one asks 小海狸 about it (details + cards to act).
--
--   low_stock        tracked products at / below their low level, or under 3 days left at the last 7 days' pace
--   long_shift       someone on the clock for more than 12 hours (a forgotten clock-out)
--   delivery_out     an order out with a driver for more than 2 hours, not collected
--   offline_card     card sales approved offline, still not sent after 30 minutes
--   checklist_late   a checklist past its due time today with steps not done
--   todo_overdue     open to-dos past due
--   loss_check       the loss-prevention rules flagged something (apex_loss_signals)
-- Each: {kind, count, names[] (up to 5)}. Only kinds with something to say are returned.

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

  RETURN out;
END;
$$;

-- For a terminal: its own store's alerts.
CREATE OR REPLACE FUNCTION public.apex_alerts(p_tz TEXT DEFAULT 'America/Toronto') RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT CASE WHEN office_device_org() IS NULL THEN '[]'::jsonb ELSE apex_alerts_for(office_device_org(), p_tz) END
$$;

REVOKE ALL ON FUNCTION public.apex_alerts_for(UUID, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apex_alerts_for(UUID, TEXT) TO service_role;
REVOKE ALL ON FUNCTION public.apex_alerts(TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.apex_alerts(TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
