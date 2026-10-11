-- 2026-10-10: Apex (小海狸 / Beaver), the store manager's AI assistant on Iris / Ourea.
-- Same shape as JustWeDo's 小海狸 (ai-support-reply): how to behave is fixed
-- in the apex-assistant Edge Function; what to know is data -- the store's
-- own numbers (apex_store_stats, computed here, never by the model) plus
-- operating facts in apex_knowledge that CMP edits without a deploy.
--
--   apex_store_stats(org, tz)  the numbers the assistant may quote. Called only
--                              by the Edge Function (service role), which
--                              derives the org from the caller's device.
--   apex_knowledge             GoldSky-wide (org_id NULL) or per-store facts.
--   apex_messages              every question and answer (and the cached
--                              daily brief), for CMP review and chat history.

-- ---------------------------------------------------------------------------
-- Store numbers
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION apex_store_stats(p_org UUID, p_tz TEXT DEFAULT 'America/Toronto')
RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public
AS $$
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
    'not_sold_14_days', (SELECT coalesce(jsonb_agg(p.name ORDER BY p.name), '[]') FROM (
        SELECT name FROM products pr
        WHERE pr.org_id = p_org AND pr.is_active
          AND NOT EXISTS (SELECT 1 FROM items i WHERE i.local_at::date > today - 14
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
    'delivery_open', (SELECT count(*) FROM delivery_orders WHERE org_id = p_org AND status = 'OPEN'),
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
$$;
-- The Edge Function (service role) only; a terminal gets these numbers through it.
REVOKE ALL ON FUNCTION apex_store_stats(UUID, TEXT) FROM PUBLIC, anon, authenticated;

-- ---------------------------------------------------------------------------
-- Knowledge: facts Apex may state (operating how-tos, store policies)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS apex_knowledge (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      UUID REFERENCES organizations(id) ON DELETE CASCADE, -- NULL = every store
  content     TEXT NOT NULL CHECK (char_length(trim(content)) BETWEEN 1 AND 1000),
  sort_order  INTEGER NOT NULL DEFAULT 100,
  is_active   BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS apex_knowledge_org_idx ON apex_knowledge (org_id);
ALTER TABLE apex_knowledge ENABLE ROW LEVEL SECURITY;

INSERT INTO permissions (key, description, category) VALUES
  ('apex.manage', 'Edit what the Apex assistant knows about the store and read its conversations', 'team')
ON CONFLICT (key) DO NOTHING;
INSERT INTO capability_permissions (capability, permission) VALUES
  ('admin', 'apex.manage'), ('decision_maker', 'apex.manage')
ON CONFLICT DO NOTHING;

-- Store facts: the org's members manage their own; GoldSky-wide facts (org_id NULL): sys admins.
DROP POLICY IF EXISTS "Members can view apex knowledge" ON apex_knowledge;
CREATE POLICY "Members can view apex knowledge" ON apex_knowledge FOR SELECT TO authenticated
  USING (is_sys_admin() OR org_id IS NULL OR org_id IN (SELECT member_org_ids()));
DROP POLICY IF EXISTS "Managers can insert apex knowledge" ON apex_knowledge;
CREATE POLICY "Managers can insert apex knowledge" ON apex_knowledge FOR INSERT TO authenticated
  WITH CHECK (is_sys_admin() OR (org_id IS NOT NULL AND has_permission('apex.manage') AND org_id IN (SELECT member_org_ids())));
DROP POLICY IF EXISTS "Managers can update apex knowledge" ON apex_knowledge;
CREATE POLICY "Managers can update apex knowledge" ON apex_knowledge FOR UPDATE TO authenticated
  USING (is_sys_admin() OR (org_id IS NOT NULL AND has_permission('apex.manage') AND org_id IN (SELECT member_org_ids())));
DROP POLICY IF EXISTS "Managers can delete apex knowledge" ON apex_knowledge;
CREATE POLICY "Managers can delete apex knowledge" ON apex_knowledge FOR DELETE TO authenticated
  USING (is_sys_admin() OR (org_id IS NOT NULL AND has_permission('apex.manage') AND org_id IN (SELECT member_org_ids())));

-- Seed: how GoldSky's terminals actually work (fact-checked against the code, 2026-10-10).
INSERT INTO apex_knowledge (org_id, content, sort_order)
SELECT NULL, c, o FROM (VALUES
  ('Card batches settle automatically every day at 03:30 store time. Before that a card sale can be reversed with no card; after it, a refund from the terminal needs the customer''s card, or it can be refunded in the WizarPOS merchant portal with no card and then recorded on the terminal as "Already refunded in portal".', 10),
  ('Refunds are whole-sale only. A cashier starts it from Transactions; a manager approves it with their PIN.', 20),
  ('Staff (name, role, 4-digit PIN) are added and reset in CMP under Ops > Staff. Roles: CASHIER, MANAGER (also sees reports, settings, refund approval), DRIVER (delivery collection only).', 30),
  ('Terminals lock after 5 minutes without use; staff sign in again with their PIN.', 40),
  ('Delivery orders are entered in CMP (Payments > Delivery). The driver collects card or cash on the handheld; the order then shows as paid.', 50),
  ('Products, prices, barcodes and the tax rate per product are managed in CMP (IoT > Retail products), including bulk import. Terminals pick up changes on their next sync.', 60),
  ('Ontario HST is 13%. Each product carries its own tax rate; tax-exempt items (most basic groceries) are set to 0%.', 70),
  ('The day report (managers) shows cash vs card, sales by staff, tax and tips, and refunds/voids for one day, and can be printed.', 80)
) v(c, o)
WHERE NOT EXISTS (SELECT 1 FROM apex_knowledge WHERE org_id IS NULL);

-- ---------------------------------------------------------------------------
-- Conversation log (+ cached daily brief)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS apex_messages (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  device_sn   TEXT,
  staff_id    UUID REFERENCES staff(id) ON DELETE SET NULL,
  staff_name  TEXT,
  kind        TEXT NOT NULL DEFAULT 'chat' CHECK (kind IN ('chat', 'brief')),
  role        TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
  language    TEXT NOT NULL DEFAULT 'zh' CHECK (language IN ('zh', 'en')),
  content     TEXT NOT NULL,
  model       TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS apex_messages_org_idx ON apex_messages (org_id, created_at DESC);
ALTER TABLE apex_messages ENABLE ROW LEVEL SECURITY;
-- Written by the Edge Function only (service role); CMP can read its own org's.
DROP POLICY IF EXISTS "Managers can view apex messages" ON apex_messages;
CREATE POLICY "Managers can view apex messages" ON apex_messages FOR SELECT TO authenticated
  USING (is_sys_admin() OR (has_permission('apex.manage') AND org_id IN (SELECT member_org_ids())));

NOTIFY pgrst, 'reload schema';
