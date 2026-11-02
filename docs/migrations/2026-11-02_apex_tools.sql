-- 2026-11-02: 小海狸 2.0 P1 (docs/apex_agent_design.md): the read tools the assistant calls.
--
-- Called only by the apex-assistant Edge Function with the service role, the store
-- (p_org) taken from the caller's session there -- never from the model. Every amount
-- is computed here, in cents; the function turns *_cents into dollars for the model.
-- Sales = PAID transactions of the store's devices, less partial refunds (the same rule
-- as apex_store_stats' month sales and the day report). Dates are the store's local days.
-- Ranges are capped at 400 days, lists at 50 rows.

CREATE OR REPLACE FUNCTION public.apex_tz(p_tz TEXT) RETURNS TEXT
LANGUAGE sql STABLE AS $$
  SELECT CASE WHEN p_tz IN (SELECT name FROM pg_timezone_names) THEN p_tz ELSE 'America/Toronto' END
$$;

-- Sales of [p_from, p_to] (local days, inclusive), optionally grouped:
-- none / day / hour / weekday / staff / method.
CREATE OR REPLACE FUNCTION public.apex_tool_sales(p_org UUID, p_from DATE, p_to DATE, p_group TEXT DEFAULT 'none', p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
DECLARE
  tz TEXT := apex_tz(p_tz);
  d1 DATE := least(p_from, p_to);
  d2 DATE := greatest(p_from, p_to);
  g  TEXT := coalesce(nullif(lower(p_group), ''), 'none');
BEGIN
  IF d2 - d1 > 400 THEN d1 := d2 - 400; END IF;
  RETURN (
    WITH t AS (
      SELECT amount - refunded_cents AS total, coalesce(tax_cents, 0) - refunded_tax_cents AS tax, coalesce(tip_cents, 0) AS tip,
             coalesce(payment_method, 'UNKNOWN') AS method, coalesce(staff_name, '(no staff)') AS staff,
             created_at AT TIME ZONE tz AS local_at
        FROM transactions
       WHERE device_sn IN (SELECT sn FROM devices WHERE org_id = p_org) AND payment_status = 'PAID'
         AND created_at >= (d1::timestamp AT TIME ZONE tz) AND created_at < ((d2 + 1)::timestamp AT TIME ZONE tz)),
    r AS (
      SELECT count(*) AS n, coalesce(sum(CASE WHEN payment_status = 'PAID' THEN refunded_cents ELSE amount END), 0) AS c
        FROM transactions
       WHERE device_sn IN (SELECT sn FROM devices WHERE org_id = p_org)
         AND (payment_status IN ('REFUNDED', 'VOIDED') OR (payment_status = 'PAID' AND refunded_cents > 0))
         AND refunded_at >= (d1::timestamp AT TIME ZONE tz) AND refunded_at < ((d2 + 1)::timestamp AT TIME ZONE tz)),
    k AS (
      SELECT CASE g WHEN 'day' THEN to_char(local_at, 'YYYY-MM-DD Dy')
                    WHEN 'hour' THEN to_char(local_at, 'HH24') || ':00'
                    WHEN 'weekday' THEN to_char(local_at, 'ID Dy')
                    WHEN 'staff' THEN staff
                    WHEN 'method' THEN method END AS key, total
        FROM t WHERE g <> 'none')
    SELECT jsonb_build_object(
      'from', d1, 'to', d2, 'days', d2 - d1 + 1,
      'sales', count(*), 'total_cents', coalesce(sum(total), 0),
      'tax_cents', coalesce(sum(tax), 0), 'tip_cents', coalesce(sum(tip), 0),
      'before_tax_and_tips_cents', coalesce(sum(total - tax - tip), 0),
      'avg_basket_cents', coalesce(round(avg(total)), 0),
      'refunds', (SELECT jsonb_build_object('count', n, 'cents', c) FROM r),
      'groups', CASE WHEN g = 'none' THEN NULL ELSE
        (SELECT coalesce(jsonb_agg(jsonb_build_object('key', key, 'sales', n, 'total_cents', c) ORDER BY key), '[]')
           FROM (SELECT key, count(*) AS n, sum(total) AS c FROM k GROUP BY key) x) END)
      FROM t);
END;
$$;

-- Products sold in [p_from, p_to]: best sellers, slow sellers (active products, fewest
-- sold, unsold first), or by category; p_query narrows by name / barcode.
CREATE OR REPLACE FUNCTION public.apex_tool_products(p_org UUID, p_from DATE, p_to DATE, p_sort TEXT DEFAULT 'best', p_query TEXT DEFAULT NULL,
                                                      p_limit INT DEFAULT 10, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
DECLARE
  tz TEXT := apex_tz(p_tz);
  d1 DATE := least(p_from, p_to);
  d2 DATE := greatest(p_from, p_to);
  lim INT := least(greatest(coalesce(p_limit, 10), 1), 50);
  q TEXT := nullif(trim(p_query), '');
BEGIN
  IF d2 - d1 > 400 THEN d1 := d2 - 400; END IF;
  RETURN (
    WITH sold AS (
      SELECT i.product_id, i.name, i.quantity - i.refunded_qty AS qty,
             CASE WHEN i.quantity > 0 THEN i.line_total_cents * (i.quantity - i.refunded_qty) / i.quantity ELSE 0 END AS revenue
        FROM transaction_items i JOIN transactions t ON t.ecr_ref_num = i.ecr_ref_num
       WHERE t.device_sn IN (SELECT sn FROM devices WHERE org_id = p_org) AND t.payment_status = 'PAID'
         AND t.created_at >= (d1::timestamp AT TIME ZONE tz) AND t.created_at < ((d2 + 1)::timestamp AT TIME ZONE tz)),
    by_item AS (
      SELECT coalesce(p.name, s.name) AS name, p.attributes->>'category' AS category, sum(s.qty) AS qty, sum(s.revenue) AS revenue
        FROM sold s LEFT JOIN products p ON p.id = s.product_id
       WHERE q IS NULL OR coalesce(p.name, s.name) ILIKE '%' || q || '%' OR p.attributes->>'barcode' = q
       GROUP BY 1, 2),
    slow AS (
      SELECT p.name, p.attributes->>'category' AS category, coalesce(sum(s.qty), 0) AS qty, coalesce(sum(s.revenue), 0) AS revenue, p.stock_qty
        FROM products p LEFT JOIN sold s ON s.product_id = p.id
       WHERE p.org_id = p_org AND p.is_active AND (q IS NULL OR p.name ILIKE '%' || q || '%')
       GROUP BY p.id)
    SELECT jsonb_build_object('from', d1, 'to', d2, 'sort', p_sort, 'items',
      CASE lower(coalesce(p_sort, 'best'))
        WHEN 'slow' THEN (SELECT coalesce(jsonb_agg(jsonb_build_object('name', name, 'category', category, 'qty', qty, 'revenue_cents', revenue, 'stock', stock_qty)), '[]')
                            FROM (SELECT * FROM slow ORDER BY qty, name LIMIT lim) x)
        WHEN 'category' THEN (SELECT coalesce(jsonb_agg(jsonb_build_object('category', coalesce(category, '(none)'), 'qty', qty, 'revenue_cents', revenue) ORDER BY revenue DESC), '[]')
                            FROM (SELECT category, sum(qty) AS qty, sum(revenue) AS revenue FROM by_item GROUP BY 1) x)
        ELSE (SELECT coalesce(jsonb_agg(jsonb_build_object('name', name, 'category', category, 'qty', qty, 'revenue_cents', revenue)), '[]')
                FROM (SELECT * FROM by_item ORDER BY qty DESC, revenue DESC LIMIT lim) x) END));
END;
$$;

-- Find sales: a local day, optionally a time window ('HH:MI'), amount, staff, method, status.
CREATE OR REPLACE FUNCTION public.apex_tool_find_transactions(p_org UUID, p_day DATE, p_time_from TEXT DEFAULT NULL, p_time_to TEXT DEFAULT NULL,
    p_amount_cents INT DEFAULT NULL, p_staff TEXT DEFAULT NULL, p_method TEXT DEFAULT NULL, p_status TEXT DEFAULT NULL,
    p_limit INT DEFAULT 20, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, member AS $$
DECLARE
  tz TEXT := apex_tz(p_tz);
  t1 TIME := coalesce(nullif(p_time_from, '')::time, '00:00');
  t2 TIME := coalesce(nullif(p_time_to, '')::time, '23:59:59');
BEGIN
  RETURN (SELECT jsonb_build_object('day', p_day, 'items', coalesce(jsonb_agg(x ORDER BY x->>'time'), '[]')) FROM (
    SELECT jsonb_build_object(
      'ref', t.ecr_ref_num, 'time', to_char(t.created_at AT TIME ZONE tz, 'HH24:MI'),
      'amount_cents', t.amount, 'tip_cents', coalesce(t.tip_cents, 0), 'method', t.payment_method,
      'status', CASE WHEN t.payment_status = 'PAID' AND t.refunded_cents > 0 THEN 'PART_REFUNDED' ELSE t.payment_status END,
      'refunded_cents', CASE WHEN t.payment_status = 'PAID' THEN t.refunded_cents WHEN t.payment_status IN ('REFUNDED', 'VOIDED') THEN t.amount ELSE 0 END,
      'staff', t.staff_name, 'order_number', t.order_number,
      'items', (SELECT count(*) FROM transaction_items i WHERE i.ecr_ref_num = t.ecr_ref_num),
      'member', (SELECT coalesce(m.name, '…' || right(m.phone, 4)) FROM member.members m WHERE m.id = t.member_id)) AS x
      FROM transactions t
     WHERE t.device_sn IN (SELECT sn FROM devices WHERE org_id = p_org)
       AND t.created_at >= (p_day::timestamp AT TIME ZONE tz) AND t.created_at < ((p_day + 1)::timestamp AT TIME ZONE tz)
       AND (t.created_at AT TIME ZONE tz)::time BETWEEN t1 AND t2
       AND (p_amount_cents IS NULL OR abs(t.amount - p_amount_cents) <= 1 OR abs(t.amount - coalesce(t.tip_cents, 0) - p_amount_cents) <= 1)
       AND (nullif(p_staff, '') IS NULL OR t.staff_name ILIKE '%' || p_staff || '%')
       AND (nullif(p_method, '') IS NULL OR t.payment_method ILIKE '%' || p_method || '%')
       AND (nullif(p_status, '') IS NULL OR t.payment_status = upper(p_status)
            OR (upper(p_status) IN ('REFUNDED', 'PART_REFUNDED') AND t.payment_status = 'PAID' AND t.refunded_cents > 0))
     ORDER BY t.created_at
     LIMIT least(greatest(coalesce(p_limit, 20), 1), 50)) y);
END;
$$;

-- One sale in full: lines (with what was given back), refunds, member and points, delivery.
CREATE OR REPLACE FUNCTION public.apex_tool_transaction(p_org UUID, p_ref TEXT, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, member AS $$
  SELECT coalesce((
    SELECT jsonb_build_object(
      'ref', t.ecr_ref_num, 'at', to_char(t.created_at AT TIME ZONE apex_tz(p_tz), 'YYYY-MM-DD HH24:MI'),
      'status', t.payment_status, 'method', t.payment_method, 'staff', t.staff_name,
      'amount_cents', t.amount, 'subtotal_cents', t.subtotal_cents, 'tax_cents', t.tax_cents, 'tip_cents', t.tip_cents,
      'discount_cents', t.discount_cents, 'discounts', t.discounts,
      'refunded_cents', t.refunded_cents, 'order_number', t.order_number, 'order_type', t.order_type, 'note', t.order_note,
      'offline_card', CASE WHEN t.offline_auth THEN t.offline_status END,
      'lines', (SELECT coalesce(jsonb_agg(jsonb_build_object('name', i.name, 'qty', i.quantity, 'returned', i.refunded_qty,
                 'unit_price_cents', i.unit_price_cents, 'line_total_cents', i.line_total_cents) ORDER BY i.line_no), '[]')
                  FROM transaction_items i WHERE i.ecr_ref_num = t.ecr_ref_num),
      'refunds', (SELECT coalesce(jsonb_agg(jsonb_build_object('amount_cents', r.amount_cents, 'how', r.how, 'approved_by', r.approved_by_name,
                   'at', to_char(r.refunded_at AT TIME ZONE apex_tz(p_tz), 'YYYY-MM-DD HH24:MI')) ORDER BY r.refunded_at), '[]')
                    FROM transaction_refunds r WHERE r.ecr_ref_num = t.ecr_ref_num),
      'whole_refund', CASE WHEN t.payment_status IN ('REFUNDED', 'VOIDED') THEN jsonb_build_object(
                        'at', to_char(t.refunded_at AT TIME ZONE apex_tz(p_tz), 'YYYY-MM-DD HH24:MI'), 'approved_by', t.refund_approved_by_name) END,
      'member', (SELECT jsonb_build_object('name', coalesce(m.name, '…' || right(m.phone, 4)),
                  'points_moves', (SELECT coalesce(jsonb_agg(jsonb_build_object('reason', l.reason, 'points', l.delta)), '[]')
                                     FROM member.points_ledger l WHERE l.member_id = m.id AND (l.ecr_ref_num = t.ecr_ref_num OR starts_with(l.ecr_ref_num, t.ecr_ref_num || '#'))))
                   FROM member.members m WHERE m.id = t.member_id),
      'delivery_order', (SELECT 'D' || d.order_no FROM delivery_orders d WHERE d.paid_ecr_ref_num = t.ecr_ref_num))
      FROM transactions t
     WHERE t.ecr_ref_num = p_ref AND t.device_sn IN (SELECT sn FROM devices WHERE org_id = p_org)),
    jsonb_build_object('not_found', p_ref))
$$;

-- Stock: tracked products (low first), by name / barcode, or only the low / out ones.
CREATE OR REPLACE FUNCTION public.apex_tool_stock(p_org UUID, p_query TEXT DEFAULT NULL, p_low_only BOOLEAN DEFAULT false)
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT jsonb_build_object(
    'tracked', (SELECT count(*) FROM jsonb_array_elements(retail_stock_list_for(p_org)) e WHERE e->'stock_qty' <> 'null'::jsonb),
    'items', (SELECT coalesce(jsonb_agg(jsonb_build_object('name', e->>'name', 'category', e->>'category', 'stock', (e->>'stock_qty')::int,
                'low_at', (e->>'low_stock_qty')::int, 'sold_last_7_days', (e->>'sold_7d')::int,
                'days_left_at_this_pace', CASE WHEN (e->>'sold_7d')::int > 0 THEN round((e->>'stock_qty')::numeric / ((e->>'sold_7d')::numeric / 7), 1) END)), '[]')
                FROM (SELECT e FROM jsonb_array_elements(retail_stock_list_for(p_org)) e
                       WHERE e->'stock_qty' <> 'null'::jsonb
                         AND (nullif(trim(p_query), '') IS NULL OR e->>'name' ILIKE '%' || trim(p_query) || '%' OR e->>'barcode' = trim(p_query))
                         AND (NOT coalesce(p_low_only, false) OR (e->>'stock_qty')::int <= coalesce((e->>'low_stock_qty')::int, 0))
                       ORDER BY (e->>'stock_qty')::int - coalesce((e->>'low_stock_qty')::int, 0), e->>'name'
                       LIMIT 30) x))
$$;

-- Loyalty members: one by phone / name (with recent points moves), or the top ones.
CREATE OR REPLACE FUNCTION public.apex_tool_members(p_org UUID, p_query TEXT DEFAULT NULL, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, member AS $$
  SELECT jsonb_build_object(
    'loyalty_on', (member_settings_for(p_org)->>'enabled')::boolean,
    'members_total', (SELECT count(*) FROM member.members WHERE org_id = p_org),
    'points_unused_total', (SELECT coalesce(sum(points), 0) FROM member.members WHERE org_id = p_org),
    'items', (SELECT coalesce(jsonb_agg(jsonb_build_object('name', coalesce(m.name, ''), 'phone_last4', right(m.phone, 4),
                'points', m.points, 'earned_in_all', m.lifetime_points, 'visits', m.visits,
                'last_visit', to_char(m.last_visit_at AT TIME ZONE apex_tz(p_tz), 'YYYY-MM-DD'),
                'recent_points', (SELECT coalesce(jsonb_agg(jsonb_build_object('reason', l.reason, 'points', l.delta,
                     'on', to_char(l.created_at AT TIME ZONE apex_tz(p_tz), 'MM-DD'), 'note', l.note) ORDER BY l.created_at DESC), '[]')
                    FROM (SELECT * FROM member.points_ledger WHERE member_id = m.id ORDER BY created_at DESC LIMIT 5) l))), '[]')
              FROM (SELECT * FROM member.members m
                     WHERE m.org_id = p_org
                       AND (nullif(trim(p_query), '') IS NULL
                            OR (member_phone(p_query) <> '' AND m.phone LIKE '%' || member_phone(p_query) || '%')
                            OR m.name ILIKE '%' || trim(p_query) || '%')
                     ORDER BY m.lifetime_points DESC LIMIT 10) m))
$$;

-- Hours and tips per person over [p_from, p_to] (each day under the store's tip rule).
CREATE OR REPLACE FUNCTION public.apex_tool_staff(p_org UUID, p_from DATE, p_to DATE)
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  WITH days AS (SELECT d::date AS d FROM generate_series(least(p_from, p_to), least(greatest(p_from, p_to), least(p_from, p_to) + 62), INTERVAL '1 day') d),
  per AS (SELECT d.d, s FROM days d, jsonb_array_elements(office_tips_for(p_org, d.d)->'staff') s)
  SELECT jsonb_build_object(
    'from', least(p_from, p_to), 'to', (SELECT max(d) FROM days),
    'tip_rule', office_tips_for(p_org, greatest(p_from, p_to))->>'rule',
    'staff', (SELECT coalesce(jsonb_agg(x ORDER BY x->>'name'), '[]') FROM (
       SELECT jsonb_build_object('name', s->>'name', 'minutes', sum((s->>'minutes')::int),
                                 'tips_share_cents', sum((s->>'share_cents')::int),
                                 'still_on_clock', bool_or((s->>'on_clock')::boolean AND d = (SELECT max(d) FROM days))) AS x
         FROM per GROUP BY s->>'name') y))
$$;

-- Money in and out over [p_from, p_to]: sales before tax and tips, expenses by category
-- (and the largest ones), rough profit. Same rule as office_day_profit_for, summed by day.
CREATE OR REPLACE FUNCTION public.apex_tool_money(p_org UUID, p_from DATE, p_to DATE, p_category TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  WITH r AS (SELECT least(p_from, p_to) AS d1, least(greatest(p_from, p_to), least(p_from, p_to) + 400) AS d2),
  days AS (SELECT office_day_profit_for(p_org, d::date) AS p FROM r, generate_series(r.d1, r.d2, INTERVAL '1 day') d),
  e AS (SELECT x.* FROM expenses x, r WHERE x.org_id = p_org AND x.spent_on BETWEEN r.d1 AND r.d2
          AND (nullif(p_category, '') IS NULL OR x.category = upper(p_category)))
  SELECT jsonb_build_object(
    'from', (SELECT d1 FROM r), 'to', (SELECT d2 FROM r),
    'sales_before_tax_and_tips_cents', (SELECT coalesce(sum((p->>'revenue_cents')::int), 0) FROM days),
    'expenses_cents', (SELECT coalesce(sum(amount_cents), 0) FROM e),
    'expenses_by_category', (SELECT coalesce(jsonb_object_agg(category, c), '{}') FROM (SELECT category, sum(amount_cents) AS c FROM e GROUP BY 1) x),
    'largest_expenses', (SELECT coalesce(jsonb_agg(jsonb_build_object('on', spent_on, 'vendor', vendor, 'category', category,
                           'amount_cents', amount_cents, 'note', note) ORDER BY amount_cents DESC), '[]')
                           FROM (SELECT * FROM e ORDER BY amount_cents DESC LIMIT 10) x),
    'rough_profit_cents', (SELECT coalesce(sum((p->>'revenue_cents')::int), 0) FROM days) - (SELECT coalesce(sum(amount_cents), 0) FROM expenses x, r
                            WHERE x.org_id = p_org AND x.spent_on BETWEEN r.d1 AND r.d2),
    'rough_profit_note', 'stock bought counts as an expense on the day it was paid; only recorded expenses')
$$;

-- Delivery orders: open (waiting / out), or the ones delivered recently.
CREATE OR REPLACE FUNCTION public.apex_tool_deliveries(p_org UUID, p_status TEXT DEFAULT 'open', p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT jsonb_build_object('items', coalesce(jsonb_agg(x), '[]')) FROM (
    SELECT jsonb_build_object('order', 'D' || d.order_no, 'customer', d.customer_name, 'address', d.address,
      'stage', CASE WHEN d.status = 'PAID' THEN 'DELIVERED' WHEN d.status = 'CANCELLED' THEN 'CANCELLED' ELSE d.delivery_stage END,
      'total_cents', d.subtotal_cents + d.tax_cents,
      'out_with', d.out_staff_name, 'out_since', to_char(d.out_at AT TIME ZONE apex_tz(p_tz), 'MM-DD HH24:MI'),
      'tries', d.attempts, 'last_try_note', d.last_attempt_note,
      'paid', CASE WHEN d.status = 'PAID' THEN d.payment_method END,
      'created', to_char(d.created_at AT TIME ZONE apex_tz(p_tz), 'MM-DD HH24:MI')) AS x
      FROM delivery_orders d
     WHERE d.org_id = p_org
       AND CASE lower(coalesce(p_status, 'open'))
             WHEN 'delivered' THEN d.status = 'PAID' AND d.paid_at > now() - INTERVAL '7 days'
             WHEN 'all' THEN d.created_at > now() - INTERVAL '14 days'
             ELSE d.status = 'OPEN' END
     ORDER BY d.created_at DESC LIMIT 30) y
$$;

-- The journal today: open to-dos (overdue / due soon), checklists done so far, latest notes.
CREATE OR REPLACE FUNCTION public.apex_tool_journal(p_org UUID, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, office AS $$
  WITH today AS (SELECT (now() AT TIME ZONE apex_tz(p_tz))::date AS d)
  SELECT jsonb_build_object(
    'todos_open', (SELECT coalesce(jsonb_agg(jsonb_build_object('text', text, 'for', coalesce(assignee_name, 'everyone'), 'set_by', staff_name,
        'due', to_char(due_at AT TIME ZONE apex_tz(p_tz), 'MM-DD HH24:MI'), 'overdue', due_at < now()) ORDER BY due_at), '[]')
        FROM (SELECT * FROM reminders WHERE org_id = p_org AND done_at IS NULL ORDER BY due_at LIMIT 20) r),
    'checklists_today', (SELECT coalesce(jsonb_agg(jsonb_build_object('name', c.name, 'due', to_char(c.due_time, 'HH24:MI'),
        'items', (SELECT count(*) FROM checklist_items i WHERE i.checklist_id = c.id AND i.is_active),
        'done', (SELECT count(DISTINCT k.item_id) FROM checklist_ticks k, today WHERE k.checklist_id = c.id AND k.business_day = today.d),
        'not_done', (SELECT coalesce(jsonb_agg(i.text ORDER BY i.sort), '[]') FROM checklist_items i, today
                      WHERE i.checklist_id = c.id AND i.is_active
                        AND NOT EXISTS (SELECT 1 FROM checklist_ticks k WHERE k.item_id = i.id AND k.business_day = today.d)))
        ORDER BY c.sort), '[]') FROM checklists c WHERE c.org_id = p_org AND c.is_active),
    'latest_notes', (SELECT coalesce(jsonb_agg(jsonb_build_object('title', title, 'text', left(content, 200), 'by', staff_name,
        'on', to_char(created_at AT TIME ZONE apex_tz(p_tz), 'MM-DD')) ORDER BY pinned DESC, created_at DESC), '[]')
        FROM (SELECT * FROM notes WHERE org_id = p_org ORDER BY pinned DESC, created_at DESC LIMIT 5) n))
$$;

REVOKE ALL ON FUNCTION public.apex_tz(TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_sales(UUID, DATE, DATE, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_products(UUID, DATE, DATE, TEXT, TEXT, INT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_find_transactions(UUID, DATE, TEXT, TEXT, INT, TEXT, TEXT, TEXT, INT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_transaction(UUID, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_stock(UUID, TEXT, BOOLEAN) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_members(UUID, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_staff(UUID, DATE, DATE) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_money(UUID, DATE, DATE, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_deliveries(UUID, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.apex_tool_journal(UUID, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apex_tz(TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_sales(UUID, DATE, DATE, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_products(UUID, DATE, DATE, TEXT, TEXT, INT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_find_transactions(UUID, DATE, TEXT, TEXT, INT, TEXT, TEXT, TEXT, INT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_transaction(UUID, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_stock(UUID, TEXT, BOOLEAN) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_members(UUID, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_staff(UUID, DATE, DATE) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_money(UUID, DATE, DATE, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_deliveries(UUID, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.apex_tool_journal(UUID, TEXT) TO service_role;

NOTIFY pgrst, 'reload schema';
