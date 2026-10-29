-- 2026-10-28: Ourea's day close counts Ourea Scan online payments (owner).
-- They live only in dine.payments (method ONLINE, device_sn = the store's
-- Ourea; database_design.md §4), so the day report and the store's day profit
-- add them to what they read from public.transactions. Only ONLINE rows: a card
-- or cash payment taken on a terminal is already in public.transactions.

BEGIN;

-- The calling terminal's online payments made in [p_start, p_end), in the shape
-- of the day report's transaction rows (DayReportRepository.ReportTxn).
CREATE OR REPLACE FUNCTION public.dine_online_sales(p_start TIMESTAMPTZ, p_end TIMESTAMPTZ)
RETURNS JSONB LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
           'ecr_ref_num', p.ecr_ref_num,
           'amount', p.amount_cents + p.tip_cents,
           'subtotal_cents', p.amount_cents - p.tax_cents,
           'tax_cents', p.tax_cents,
           'tip_cents', p.tip_cents,
           'payment_status', 'PAID',
           'payment_method', 'ONLINE',
           'staff_name', 'Ourea Scan',
           'created_at', p.created_at) ORDER BY p.created_at), '[]'::jsonb)
    FROM dine.payments p
   WHERE p.method = 'ONLINE' AND p.created_at >= p_start AND p.created_at < p_end
     AND p.device_sn = (SELECT m.device_sn FROM device_auth_map m
                         WHERE m.auth_user_id = auth.uid() AND m.org_id IS NOT NULL
                         ORDER BY m.created_at DESC LIMIT 1)
$$;
REVOKE ALL ON FUNCTION public.dine_online_sales(TIMESTAMPTZ, TIMESTAMPTZ) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.dine_online_sales(TIMESTAMPTZ, TIMESTAMPTZ) TO authenticated;

-- The store's day profit (Ourea's day report, 小海狸's profit_today) with the
-- day's online payments added.
CREATE OR REPLACE FUNCTION public.office_day_profit_for(p_org UUID, p_day DATE)
RETURNS JSONB LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  WITH sales AS (
    SELECT t.amount AS total, coalesce(t.tax_cents, 0) AS tax, coalesce(t.tip_cents, 0) AS tip
      FROM transactions t
     WHERE t.device_sn IN (SELECT sn FROM devices WHERE org_id = p_org)
       AND t.payment_status = 'PAID'
       AND (t.created_at AT TIME ZONE 'America/Toronto')::date = p_day
    UNION ALL
    SELECT p.amount_cents + p.tip_cents, p.tax_cents, p.tip_cents
      FROM dine.payments p JOIN dine.checks c ON c.id = p.check_id
     WHERE c.org_id = p_org AND p.method = 'ONLINE'
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
$$;

COMMIT;
