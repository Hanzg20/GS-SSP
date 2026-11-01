-- 2026-11-01: partial refunds (2026-11-01_partial_refunds.sql) in the store figures:
-- office_day_profit_for keeps what wasn't given back (refunded_cents / refunded_tax_cents off a PAID sale);
-- apex_store_stats lists partial refunds as kind PARTIAL with the amount back, and month sales net of them.

CREATE OR REPLACE FUNCTION public.office_day_profit_for(p_org uuid, p_day date)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'dine'
AS $function$
  WITH sales AS (
    SELECT t.amount - t.refunded_cents AS total, coalesce(t.tax_cents, 0) - t.refunded_tax_cents AS tax, coalesce(t.tip_cents, 0) AS tip
      FROM transactions t
     WHERE t.device_sn IN (SELECT sn FROM devices WHERE org_id = p_org)
       AND t.payment_status = 'PAID'
       AND (t.created_at AT TIME ZONE 'America/Toronto')::date = p_day
    UNION ALL
    SELECT p.amount_cents + p.tip_cents, p.tax_cents, p.tip_cents
      FROM dine.payments p JOIN dine.checks c ON c.id = p.check_id
     WHERE c.org_id = p_org AND p.method = 'ONLINE' AND p.refund_status IS NULL
       AND (p.created_at AT TIME ZONE 'America/Toronto')::date = p_day),
  s AS (
    SELECT count(*) AS n, coalesce(sum(total), 0) AS total, coalesce(sum(tax), 0) AS tax, coalesce(sum(tip), 0) AS tip FROM sales),
  e AS (
    SELECT category, count(*) AS n, sum(amount_cents) AS c
      FROM expenses WHERE org_id = p_org AND spent_on = p_day GROUP BY category)
  SELECT jsonb_build_object(
    'day', p_day,
    'sales', s.n, 'sales_total_cents', s.total, 'tax_cents', s.tax, 'tip_cents', s.tip,
    'revenue_cents', s.total - s.tax - s.tip,
    'expenses_count', (SELECT coalesce(sum(n), 0) FROM e),
    'expenses_cents', (SELECT coalesce(sum(c), 0) FROM e),
    'expenses_by_category', (SELECT coalesce(jsonb_object_agg(category, c), '{}'::jsonb) FROM e),
    'rough_profit_cents', s.total - s.tax - s.tip - (SELECT coalesce(sum(c), 0) FROM e))
    FROM s
$function$;

CREATE OR REPLACE FUNCTION public.apex_store_stats(p_org uuid, p_tz text DEFAULT 'America/Toronto'::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  tz         TEXT := CASE WHEN p_tz IN (SELECT name FROM pg_timezone_names) THEN p_tz ELSE 'America/Toronto' END;
  now_local  TIMESTAMP := now() AT TIME ZONE tz;
  today      DATE := (now() AT TIME ZONE tz)::date;
  day_start  TIMESTAMPTZ := (today::timestamp) AT TIME ZONE tz;
  result     JSONB;
BEGIN
  WITH sns AS (SELECT sn FROM devices WHERE org_id = p_org),
  tx AS (
    SELECT t.*, (t.created_at AT TIME ZONE tz) AS local_at
    FROM transactions t
    WHERE t.device_sn IN (SELECT sn FROM sns) AND t.created_at >= day_start - INTERVAL '35 days'
  ),
  paid AS (SELECT * FROM tx WHERE payment_status = 'PAID'),
  items AS (
    SELECT i.*, p.local_at
    FROM transaction_items i JOIN paid p ON p.ecr_ref_num = i.ecr_ref_num AND p.device_sn = i.device_sn
  )
  SELECT jsonb_build_object(
    'store', (SELECT name FROM organizations WHERE id = p_org),
    'timezone', tz,
    'now_local', to_char(now_local, 'YYYY-MM-DD HH24:MI (Dy)'),
    'today', (SELECT jsonb_build_object(
        'sales', count(*), 'total_cents', coalesce(sum(amount), 0),
        'tax_cents', coalesce(sum(tax_cents), 0), 'tip_cents', coalesce(sum(tip_cents), 0),
        'cash_cents', coalesce(sum(amount) FILTER (WHERE payment_method = 'CASH'), 0),
        'card_cents', coalesce(sum(amount) FILTER (WHERE payment_method <> 'CASH'), 0),
        'avg_basket_cents', coalesce(round(avg(amount)), 0))
      FROM paid WHERE local_at::date = today),
    -- Same weekday last week, up to the same time of day: a fair "so far" comparison.
    'same_time_last_week', (SELECT jsonb_build_object('sales', count(*), 'total_cents', coalesce(sum(amount), 0))
      FROM paid WHERE local_at::date = today - 7 AND local_at::time <= now_local::time),
    'last_14_days', (SELECT coalesce(jsonb_agg(jsonb_build_object('date', d::date, 'weekday', to_char(d, 'Dy'),
          'sales', (SELECT count(*) FROM paid WHERE local_at::date = d::date),
          'total_cents', (SELECT coalesce(sum(amount), 0) FROM paid WHERE local_at::date = d::date)) ORDER BY d), '[]')
      FROM generate_series(today - 13, today, INTERVAL '1 day') d),
    -- Average sales per hour of day over the last 28 days (peak hours).
    'hourly_last_28_days', (SELECT coalesce(jsonb_agg(jsonb_build_object('hour', h, 'avg_sales', round(n / 28.0, 2),
          'avg_total_cents', round(c / 28.0)) ORDER BY h), '[]')
      FROM (SELECT extract(hour FROM local_at)::int AS h, count(*) AS n, sum(amount) AS c
            FROM paid WHERE local_at::date BETWEEN today - 28 AND today - 1 GROUP BY 1) x),
    'top_items_7_days', (SELECT coalesce(jsonb_agg(x ORDER BY x.qty DESC), '[]') FROM (
        SELECT name, sum(quantity) AS qty, sum(line_total_cents) AS revenue_cents
        FROM items WHERE local_at::date > today - 7 GROUP BY name ORDER BY 2 DESC LIMIT 10) x),
    'top_items_28_days', (SELECT coalesce(jsonb_agg(x ORDER BY x.qty DESC), '[]') FROM (
        SELECT name, sum(quantity) AS qty, sum(line_total_cents) AS revenue_cents
        FROM items WHERE local_at::date > today - 28 GROUP BY name ORDER BY 2 DESC LIMIT 10) x),
    -- Active catalogue items with no sale in 14 days.
    'not_sold_14_days', (SELECT coalesce(jsonb_agg(jsonb_build_object('name', p.name, 'category', p.category) ORDER BY p.name), '[]') FROM (
        SELECT name, attributes->>'category' AS category FROM products pr
        WHERE pr.org_id = p_org AND pr.is_active
          AND NOT EXISTS (SELECT 1 FROM items i WHERE i.local_at::date > today - 14
                          AND (i.product_id = pr.id OR (i.product_id IS NULL AND i.name = pr.name)))
        ORDER BY name LIMIT 20) p),
    -- Fresh produce / dairy / meat / bakery not sold in 3 days: spoilage risk,
    -- worth a markdown (spoilage is a large share of grocery shrink).
    'perishable_not_sold_3_days', (SELECT coalesce(jsonb_agg(p.name ORDER BY p.name), '[]') FROM (
        SELECT name FROM products pr
        WHERE pr.org_id = p_org AND pr.is_active
          AND coalesce(attributes->>'category', '') ~* '(生鲜|fresh|乳|dairy|肉|meat|海鲜|seafood|面包|bakery|豆腐|tofu)'
          AND NOT EXISTS (SELECT 1 FROM items i WHERE i.local_at::date > today - 3
                          AND (i.product_id = pr.id OR (i.product_id IS NULL AND i.name = pr.name)))
        ORDER BY name LIMIT 20) p),
    'catalogue_active_items', (SELECT count(*) FROM products WHERE org_id = p_org AND is_active),
    'staff_today', (SELECT coalesce(jsonb_agg(x), '[]') FROM (
        SELECT coalesce(staff_name, '(no staff)') AS staff, count(*) AS sales, sum(amount) AS total_cents
        FROM paid WHERE local_at::date = today GROUP BY 1 ORDER BY 3 DESC) x),
    'refunds_7_days', (SELECT coalesce(jsonb_agg(x ORDER BY x.at DESC), '[]') FROM (
        SELECT to_char(refunded_at AT TIME ZONE tz, 'MM-DD HH24:MI') AS at,
               CASE WHEN payment_status = 'PAID' THEN 'PARTIAL' ELSE payment_status END AS kind,
               CASE WHEN payment_status = 'PAID' THEN refunded_cents ELSE amount END AS amount_cents, payment_method AS method, staff_name AS sold_by,
               refund_approved_by_name AS approved_by
        FROM tx WHERE (payment_status IN ('REFUNDED', 'VOIDED') OR (payment_status = 'PAID' AND refunded_cents > 0))
          AND refunded_at >= now() - INTERVAL '7 days'
        LIMIT 30) x),
    'declined_today', (SELECT count(*) FROM tx WHERE payment_status = 'DECLINED' AND local_at::date = today),
    'loss_signals', apex_loss_signals(p_org, tz),
    'delivery_open', (SELECT count(*) FROM delivery_orders WHERE org_id = p_org AND status = 'OPEN'),
    -- Open to-dos due by the end of today (overdue included), soonest first.
    'reminders_due', (SELECT coalesce(jsonb_agg(jsonb_build_object('id', id, 'text', text, 'for', coalesce(assignee_name, 'everyone'), 'set_by', staff_name,
          'due', to_char(due_at AT TIME ZONE tz, 'MM-DD HH24:MI'), 'overdue', due_at < now()) ORDER BY due_at), '[]')
      FROM reminders WHERE org_id = p_org AND done_at IS NULL AND (due_at AT TIME ZONE tz)::date <= today),
    -- Today, the whole store: sales before tax and tips minus today's expenses (office_day_profit_for).
    'profit_today', office_day_profit_for(p_org, today),
    -- Spending this calendar month (by category) and all of last month.
    'expenses_this_month', (SELECT jsonb_build_object(
          'total_cents', coalesce(sum(amount_cents), 0),
          'by_category', coalesce((SELECT jsonb_object_agg(category, c) FROM (
              SELECT category, sum(amount_cents) AS c FROM expenses
              WHERE org_id = p_org AND spent_on >= date_trunc('month', today)::date GROUP BY 1) x), '{}'))
      FROM expenses WHERE org_id = p_org AND spent_on >= date_trunc('month', today)::date),
    'expenses_last_month_cents', (SELECT coalesce(sum(amount_cents), 0) FROM expenses WHERE org_id = p_org
      AND spent_on >= (date_trunc('month', today) - INTERVAL '1 month')::date AND spent_on < date_trunc('month', today)::date),
    'sales_this_month_cents', (SELECT coalesce(sum(amount - refunded_cents), 0) FROM transactions t
      WHERE t.device_sn IN (SELECT sn FROM devices WHERE org_id = p_org) AND t.payment_status = 'PAID'
        AND (t.created_at AT TIME ZONE tz)::date >= date_trunc('month', today)::date),
    -- Online = a heartbeat within 35 min (they come every 15). Decided here, not
    -- left to the model: given raw minutes it called an 8-minute-old heartbeat
    -- "idle, auto-locked" (the lock screen is a different thing entirely).
    'terminals', (SELECT coalesce(jsonb_agg(jsonb_build_object('name', coalesce(alias, sn),
          'online', coalesce(last_seen > now() - INTERVAL '35 minutes', false),
          'offline_for_minutes', CASE WHEN last_seen > now() - INTERVAL '35 minutes' THEN NULL
                                      ELSE round(extract(epoch FROM now() - last_seen) / 60) END)), '[]')
      FROM devices WHERE org_id = p_org)
  ) INTO result;
  RETURN result;
END;
$function$;

NOTIFY pgrst, 'reload schema';
