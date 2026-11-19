-- 2026-11-19: Arke -- a paid order nobody accepts is cancelled and refunded on its own.
--
-- Before: a paid order waited in NEW for ever if the store's tablet was off or the store closed early -- the customer
-- had paid and nobody knew. Now every paid order has an accept-by time (online.accept_by):
--   * order for now: paid_at + the store's accept_minutes (Cael, default 15);
--   * booked order : when the kitchen has to start (booked time - preparation / delivery minutes), but never sooner
--                    than paid_at + accept_minutes.
-- The order desks (Ourea / Iris, Cael) show the time left and get louder as it runs out.
--
-- Every minute pg_cron runs online.sweep_kick():
--   1. NEW orders past accept-by -> REJECTED (auto_rejected, handled by "Arke"), right here in SQL;
--   2. if any rejected order still holds the customer's money, or a refund hasn't been mailed, it wakes the
--      arke-sweep Edge Function (pg_net), which voids / refunds at Nuvei (online_refund_begin / done / abort, the same
--      steps as the desk's refund) and mails the customer.
-- The sweep also finishes refunds a desk started but never finished (rejected > 2 minutes ago, money still PAID; at
-- most 5 tries), and mails every rejected order's customer once their money is back -- manual rejects included.
--
-- arke-sweep needs no secret: it only acts on what the database says is due, so calling it early does nothing harmful.

CREATE EXTENSION IF NOT EXISTS pg_cron;
CREATE EXTENSION IF NOT EXISTS pg_net WITH SCHEMA extensions;

ALTER TABLE online.stores ADD COLUMN IF NOT EXISTS accept_minutes SMALLINT NOT NULL DEFAULT 15
  CHECK (accept_minutes BETWEEN 5 AND 120);
COMMENT ON COLUMN online.stores.accept_minutes IS 'A paid order not accepted within this many minutes is cancelled and refunded (booked orders: by when the kitchen must start, at least this long after payment)';

ALTER TABLE online.orders ADD COLUMN IF NOT EXISTS auto_rejected BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE online.orders ADD COLUMN IF NOT EXISTS refund_tries SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE online.orders ADD COLUMN IF NOT EXISTS refund_mail_status TEXT;
COMMENT ON COLUMN online.orders.auto_rejected IS 'Rejected by Arke because nobody accepted it in time (online.sweep_kick)';
COMMENT ON COLUMN online.orders.refund_tries IS 'Times the sweep tried to give back a rejected order''s money (stops at 5; then a person, Nuvei Cpanel)';
COMMENT ON COLUMN online.orders.refund_mail_status IS 'The "cancelled, money back" email: SENDING, SENT, FAILED: ..., SKIPPED: ...';

-- When a NEW order must be accepted by; NULL for anything else.
CREATE OR REPLACE FUNCTION online.accept_by(o online.orders) RETURNS TIMESTAMPTZ
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT CASE
    WHEN o.status <> 'NEW' OR o.channel <> 'ARKE' OR o.paid_at IS NULL THEN NULL
    WHEN o.scheduled_for IS NULL THEN o.paid_at + make_interval(mins => s.accept_minutes)
    ELSE greatest(o.paid_at + make_interval(mins => s.accept_minutes),
                  o.scheduled_for - make_interval(mins => CASE WHEN o.fulfilment = 'OWN_DELIVERY' THEN s.delivery_minutes ELSE s.pickup_minutes END))
  END
  FROM online.stores s WHERE s.org_id = o.org_id;
$$;
REVOKE ALL ON FUNCTION online.accept_by(online.orders) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION online.order_json(o online.orders, p_for_store boolean)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
  SELECT jsonb_build_object(
    'id', o.id, 'token', o.public_token, 'status', o.status, 'fulfilment', o.fulfilment, 'channel', o.channel,
    'pickup_code', CASE WHEN o.channel = 'UBER_EATS' THEN o.external_code
                        WHEN o.pickup_no IS NOT NULL THEN (CASE WHEN o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END) || o.pickup_no END,
    'customer_name', o.customer_name, 'note', o.note, 'items', o.items,
    'subtotal_cents', o.subtotal_cents, 'tax_cents', o.tax_cents, 'tip_cents', o.tip_cents,
    'delivery_fee_cents', o.delivery_fee_cents, 'delivery_address', o.delivery_address, 'delivery_zone', o.delivery_zone,
    'total_cents', o.subtotal_cents - o.discount_cents + o.delivery_fee_cents + o.tax_cents + o.tip_cents,
    'discount_cents', o.discount_cents, 'coupon_code', o.coupon_code,
    'points_earned', o.points_earned,
    'points_balance', (SELECT m.points FROM member.members m WHERE m.id = o.member_id),
    'scheduled_for', o.scheduled_for,
    'accept_by', online.accept_by(o), 'auto_rejected', o.auto_rejected,
    'created_at', o.created_at, 'paid_at', o.paid_at, 'accepted_at', o.accepted_at, 'ready_by', o.ready_by,
    'ready_at', o.ready_at, 'picked_up_at', o.picked_up_at, 'out_at', o.out_at, 'delivered_at', o.delivered_at,
    'rejected_at', o.rejected_at, 'reject_reason', o.reject_reason,
    'cancelled_at', o.cancelled_at, 'cancel_reason', o.cancel_reason)
  || CASE WHEN p_for_store THEN jsonb_build_object('customer_phone', o.customer_phone, 'handled_by', o.handled_by,
       'out_by', o.out_by, 'out_by_sn', o.out_by_sn, 'delivery_attempts', o.delivery_attempts, 'last_attempt_note', o.last_attempt_note,
       'external_id', o.external_id, 'external_type', o.external_type)
     ELSE '{}'::jsonb END
$function$;

-- Rejected orders whose money is still with us: auto-rejected at once, a desk's own reject after 2 minutes (its own
-- refund call is normally done by then). Shared by the kick (is there work?) and the claim (take it).
CREATE OR REPLACE FUNCTION online.refund_due() RETURNS SETOF online.orders
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT o.* FROM online.orders o
   WHERE o.status = 'REJECTED' AND o.channel = 'ARKE' AND o.refund_tries < 5
     AND o.rejected_at > now() - interval '3 days'
     AND (o.auto_rejected OR o.rejected_at < now() - interval '2 minutes')
     AND EXISTS (SELECT 1 FROM online.payments p WHERE p.order_id = o.id AND p.status = 'PAID');
$$;

-- Rejected orders whose money went back and whose customer hasn't been told by email yet.
CREATE OR REPLACE FUNCTION online.mail_due() RETURNS SETOF online.orders
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, online AS $$
  SELECT o.* FROM online.orders o
   WHERE o.status = 'REJECTED' AND o.channel = 'ARKE' AND o.refund_mail_status IS NULL
     AND o.rejected_at > now() - interval '3 days'
     AND EXISTS (SELECT 1 FROM online.payments p WHERE p.order_id = o.id AND p.status IN ('VOIDED', 'REFUNDED'))
     AND NOT EXISTS (SELECT 1 FROM online.payments p WHERE p.order_id = o.id AND p.status IN ('PAID', 'REFUNDING'));
$$;
REVOKE ALL ON FUNCTION online.refund_due() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION online.mail_due() FROM PUBLIC, anon, authenticated;

-- pg_cron, every minute.
CREATE OR REPLACE FUNCTION online.sweep_kick() RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, online, extensions AS $$
DECLARE v_n INT;
BEGIN
  WITH due AS (
    SELECT o.id FROM online.orders o
     WHERE o.status = 'NEW' AND o.channel = 'ARKE' AND online.accept_by(o) < now()
     FOR UPDATE SKIP LOCKED)
  UPDATE online.orders o SET status = 'REJECTED', rejected_at = now(), auto_rejected = true,
         reject_reason = 'Not accepted in time', handled_by = 'Arke', handled_by_sn = NULL
    FROM due WHERE o.id = due.id;
  GET DIAGNOSTICS v_n = ROW_COUNT;
  IF EXISTS (SELECT 1 FROM online.refund_due()) OR EXISTS (SELECT 1 FROM online.mail_due()) THEN
    PERFORM net.http_post(url := 'https://ttbtxxpnvkcbyugzdqfw.supabase.co/functions/v1/arke-sweep',
                          headers := '{"Content-Type": "application/json"}'::jsonb, body := '{}'::jsonb,
                          timeout_milliseconds := 55000);
    RETURN jsonb_build_object('rejected', v_n, 'woke', true);
  END IF;
  RETURN jsonb_build_object('rejected', v_n, 'woke', false);
END $$;
REVOKE ALL ON FUNCTION online.sweep_kick() FROM PUBLIC, anon, authenticated;

-- arke-sweep: takes the refunds due (each try counted, so a refund Nuvei keeps declining stops after 5).
CREATE OR REPLACE FUNCTION public.online_sweep_refunds() RETURNS JSONB
LANGUAGE sql SECURITY DEFINER SET search_path = public, online AS $$
  WITH d AS (SELECT id FROM online.refund_due() LIMIT 20),
       u AS (UPDATE online.orders o SET refund_tries = o.refund_tries + 1 FROM d WHERE o.id = d.id RETURNING o.id, o.org_id)
  SELECT coalesce(jsonb_agg(jsonb_build_object('order_id', u.id, 'org_id', u.org_id)), '[]'::jsonb) FROM u;
$$;

-- arke-sweep: takes the "cancelled, money back" emails due (SENDING, so two sweeps never mail twice).
CREATE OR REPLACE FUNCTION public.online_sweep_mails() RETURNS JSONB
LANGUAGE sql SECURITY DEFINER SET search_path = public, online AS $$
  WITH d AS (SELECT id FROM online.mail_due() LIMIT 20),
       u AS (UPDATE online.orders o SET refund_mail_status = 'SENDING' FROM d WHERE o.id = d.id AND o.refund_mail_status IS NULL RETURNING o.*)
  SELECT coalesce(jsonb_agg(jsonb_build_object(
           'order_id', u.id,
           'email', p.email, 'lang', p.lang, 'token', u.public_token,
           'code', (CASE WHEN u.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END) || u.pickup_no,
           'fulfilment', u.fulfilment,
           'store', (SELECT coalesce(nullif(trim(brand_name), ''), name) FROM organizations WHERE id = u.org_id),
           'total_cents', p.amount_cents + p.tip_cents,
           'refund', p.status, 'auto', u.auto_rejected,
           'reason', CASE WHEN u.auto_rejected THEN NULL ELSE u.reject_reason END)), '[]'::jsonb)
    FROM u
    JOIN LATERAL (SELECT * FROM online.payments p WHERE p.order_id = u.id AND p.status IN ('VOIDED', 'REFUNDED')
                   ORDER BY p.refunded_at DESC NULLS LAST LIMIT 1) p ON true;
$$;

CREATE OR REPLACE FUNCTION public.online_sweep_mail_result(p_order UUID, p_status TEXT) RETURNS VOID
LANGUAGE sql SECURITY DEFINER SET search_path = public, online AS $$
  UPDATE online.orders SET refund_mail_status = left(p_status, 300) WHERE id = p_order;
$$;
REVOKE ALL ON FUNCTION public.online_sweep_refunds() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_sweep_mails() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.online_sweep_mail_result(UUID, TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.online_sweep_refunds() TO service_role;
GRANT EXECUTE ON FUNCTION public.online_sweep_mails() TO service_role;
GRANT EXECUTE ON FUNCTION public.online_sweep_mail_result(UUID, TEXT) TO service_role;

-- Old rejected orders are not mailed now (the email is new): mark them so the first sweep doesn't mail last week.
UPDATE online.orders SET refund_mail_status = 'SKIPPED: before 2026-11-19 sweep' WHERE status = 'REJECTED' AND refund_mail_status IS NULL;

-- Cael: the store's accept-within minutes (same permission as the other online settings).
CREATE OR REPLACE FUNCTION public.cmp_online_accept_save(p_org UUID, p_minutes INT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, online AS $$
BEGIN
  IF NOT (is_sys_admin() OR (p_org IN (SELECT member_org_ids()) AND has_permission('catalog.manage'))) THEN
    RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501';
  END IF;
  IF p_minutes IS NULL OR p_minutes NOT BETWEEN 5 AND 120 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_minutes'); END IF;
  UPDATE online.stores SET accept_minutes = p_minutes, updated_at = now() WHERE org_id = p_org;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'no_store'); END IF;
  RETURN jsonb_build_object('success', true);
END $$;
REVOKE ALL ON FUNCTION public.cmp_online_accept_save(UUID, INT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cmp_online_accept_save(UUID, INT) TO authenticated;

CREATE OR REPLACE FUNCTION public.cmp_online_store_get(p_org uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_s online.stores%ROWTYPE;
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'forbidden' USING ERRCODE = '42501'; END IF;
  SELECT * INTO v_s FROM online.stores WHERE org_id = p_org;
  IF NOT FOUND THEN RETURN NULL; END IF;
  RETURN jsonb_build_object('slug', v_s.slug, 'is_open', v_s.is_open, 'open_now', online.open_now(v_s),
    'pickup_minutes', v_s.pickup_minutes, 'hours', v_s.hours, 'timezone', v_s.timezone,
    'address', v_s.address, 'phone', v_s.phone, 'notice', v_s.notice, 'schedule_days', v_s.schedule_days,
    'review_url', v_s.review_url, 'accept_minutes', v_s.accept_minutes,
    'online_pay', EXISTS (SELECT 1 FROM merchant_acquirer_accounts a WHERE a.org_id = p_org AND a.acquirer = 'nuvei' AND a.status = 'ACTIVE'),
    'delivery_enabled', v_s.delivery_enabled, 'delivery_minutes', v_s.delivery_minutes, 'delivery_tax_bps', v_s.delivery_tax_bps,
    'zones', coalesce((SELECT jsonb_agg(jsonb_build_object('id', z.id, 'name', z.name, 'postal_prefixes', to_jsonb(z.postal_prefixes),
                         'fee_cents', z.fee_cents, 'min_order_cents', z.min_order_cents) ORDER BY z.sort, z.created_at)
                         FROM online.delivery_zones z WHERE z.org_id = p_org), '[]'::jsonb));
END $function$;

SELECT cron.unschedule('arke-sweep') WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'arke-sweep');
SELECT cron.schedule('arke-sweep', '* * * * *', 'SELECT online.sweep_kick()');
