-- 2026-11-02: 小海狸 2.0 P4 / P5 (docs/apex_agent_design.md).
--
-- P5 apex_tool_month: one month closed out for the store -- sales (net of refunds) by
-- method and day, tax collected and tips, refunds, expenses by category and rough
-- profit, stock in / out by reason, best sellers, hours and tips per person, loyalty
-- points, delivery orders, offline card sales. All in cents; the assistant writes the
-- report from it (service role, for the caller's store, like the other apex_tool_*).
--
-- P4 cmp_apex_alerts: the store's alerts for Cael (a member of the store or a sys admin).

CREATE OR REPLACE FUNCTION public.apex_tool_month(p_org UUID, p_month DATE, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, retail, member AS $$
DECLARE
  tz TEXT := apex_tz(p_tz);
  d1 DATE := date_trunc('month', p_month)::date;
  d2 DATE := (date_trunc('month', p_month) + INTERVAL '1 month - 1 day')::date;
  t1 TIMESTAMPTZ := d1::timestamp AT TIME ZONE tz;
  t2 TIMESTAMPTZ := (d2 + 1)::timestamp AT TIME ZONE tz;
  money JSONB;
BEGIN
  money := apex_tool_money(p_org, d1, least(d2, (now() AT TIME ZONE tz)::date));
  RETURN jsonb_build_object(
    'month', to_char(d1, 'YYYY-MM'), 'from', d1, 'to', d2,
    'complete', d2 < (now() AT TIME ZONE tz)::date,
    'sales', apex_tool_sales(p_org, d1, d2, 'method', tz),
    'by_day', apex_tool_sales(p_org, d1, d2, 'day', tz)->'groups',
    'expenses_cents', money->'expenses_cents',
    'expenses_by_category', money->'expenses_by_category',
    'largest_expenses', money->'largest_expenses',
    'sales_before_tax_and_tips_cents', money->'sales_before_tax_and_tips_cents',
    'rough_profit_cents', money->'rough_profit_cents',
    'rough_profit_note', money->'rough_profit_note',
    'stock_moves', (SELECT coalesce(jsonb_object_agg(reason, jsonb_build_object('lines', n, 'units', u)), '{}') FROM (
        SELECT m.reason, count(*) AS n, sum(m.delta) AS u FROM retail.stock_moves m JOIN products p ON p.id = m.product_id
         WHERE p.org_id = p_org AND m.created_at >= t1 AND m.created_at < t2 GROUP BY m.reason) x),
    'stock_received', (SELECT coalesce(jsonb_agg(jsonb_build_object('name', name, 'units', u) ORDER BY u DESC), '[]') FROM (
        SELECT p.name, sum(m.delta) AS u FROM retail.stock_moves m JOIN products p ON p.id = m.product_id
         WHERE p.org_id = p_org AND m.reason = 'RECEIVE' AND m.created_at >= t1 AND m.created_at < t2 GROUP BY p.name ORDER BY 2 DESC LIMIT 10) x),
    'best_sellers', apex_tool_products(p_org, d1, d2, 'best', NULL, 10, tz)->'items',
    'by_category', apex_tool_products(p_org, d1, d2, 'category', NULL, 20, tz)->'items',
    'staff', apex_tool_staff(p_org, d1, least(d2, (now() AT TIME ZONE tz)::date))->'staff',
    'loyalty', (SELECT jsonb_build_object(
        'new_members', (SELECT count(*) FROM member.members WHERE org_id = p_org AND created_at >= t1 AND created_at < t2),
        'points_earned', coalesce(sum(delta) FILTER (WHERE reason IN ('EARN', 'REVERSE')), 0),
        'points_used', coalesce(-sum(delta) FILTER (WHERE reason IN ('REDEEM', 'RESTORE')), 0))
        FROM member.points_ledger WHERE org_id = p_org AND created_at >= t1 AND created_at < t2),
    'deliveries', (SELECT jsonb_build_object('created', count(*), 'delivered', count(*) FILTER (WHERE status = 'PAID'),
        'cancelled', count(*) FILTER (WHERE status = 'CANCELLED'), 'brought_back_tries', coalesce(sum(attempts), 0))
        FROM delivery_orders WHERE org_id = p_org AND created_at >= t1 AND created_at < t2),
    'offline_card', (SELECT jsonb_build_object('sales', count(*), 'declined_later', count(*) FILTER (WHERE offline_status = 'DECLINED'),
        'still_waiting', count(*) FILTER (WHERE offline_status = 'PENDING_UPLOAD'))
        FROM transactions WHERE device_sn IN (SELECT sn FROM devices WHERE org_id = p_org) AND offline_auth
          AND created_at >= t1 AND created_at < t2));
END;
$$;

CREATE OR REPLACE FUNCTION public.cmp_apex_alerts(p_org UUID, p_tz TEXT DEFAULT 'America/Toronto') RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN apex_alerts_for(p_org, p_tz);
END;
$$;

REVOKE ALL ON FUNCTION public.apex_tool_month(UUID, DATE, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apex_tool_month(UUID, DATE, TEXT) TO service_role;
REVOKE ALL ON FUNCTION public.cmp_apex_alerts(UUID, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.cmp_apex_alerts(UUID, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
