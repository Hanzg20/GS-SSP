-- 2026-10-16: to-dos for one person, and shift handover notes -- the Journal (手账).
--
-- A to-do (public.reminders) can now be for one staff member (assignee_*) or, as
-- before, for the whole store (assignee_id NULL). A handover note is a to-do for the
-- next person, due now: when they sign in on Iris, their open to-dos due by the end
-- of the day come up first, to tick or acknowledge. done_by_name: who finished it.
--
-- reminders stays in `public` for now (read directly by the apps under RLS; it moves
-- to `office` later -- docs/database_design.md §2 / §5b). Idempotent; one transaction.

BEGIN;

ALTER TABLE public.reminders ADD COLUMN IF NOT EXISTS assignee_id   UUID REFERENCES staff(id) ON DELETE SET NULL;
ALTER TABLE public.reminders ADD COLUMN IF NOT EXISTS assignee_name TEXT;
ALTER TABLE public.reminders ADD COLUMN IF NOT EXISTS done_by_name  TEXT;
CREATE INDEX IF NOT EXISTS reminders_assignee_open_idx ON public.reminders (assignee_id, due_at) WHERE done_at IS NULL;

COMMENT ON TABLE public.reminders IS 'Store to-dos and handover notes (Journal on Iris / Ourea, 小海狸): for one staff member (assignee_id) or the whole store (NULL); staff_* = who set it, done_by_name = who finished it';
COMMENT ON COLUMN public.reminders.assignee_id IS 'Who it is for; NULL = the whole store. Shown to that person when they sign in on Iris';

-- 小海狸's store data: each due to-do says who it is for ("everyone" when the whole
-- store) and who set it. The rest is unchanged from the live version.
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
        SELECT to_char(refunded_at AT TIME ZONE tz, 'MM-DD HH24:MI') AS at, payment_status AS kind,
               amount AS amount_cents, payment_method AS method, staff_name AS sold_by,
               refund_approved_by_name AS approved_by
        FROM tx WHERE payment_status IN ('REFUNDED', 'VOIDED') AND refunded_at >= now() - INTERVAL '7 days'
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
    'sales_this_month_cents', (SELECT coalesce(sum(amount), 0) FROM transactions t
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

COMMIT;
