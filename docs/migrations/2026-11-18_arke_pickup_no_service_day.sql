-- Arke: pickup numbers per service day (2026-11-18).
-- Found in the booked-order end-to-end test: an order booked today for tomorrow got today's next number (A1),
-- and tomorrow's daily counter would start at A1 again -- two A1s at the counter. Now each order is numbered
-- within the day it is collected (its booked day, else today). Only online_pay_settle numbers orders.

CREATE INDEX IF NOT EXISTS online_orders_pickup_day_idx ON online.orders (org_id, pickup_day) WHERE pickup_no IS NOT NULL;

CREATE OR REPLACE FUNCTION public.online_pay_settle(p_id uuid, p_transaction_id text, p_paid_cents integer, p_auth_code text, p_card text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'online'
AS $function$
DECLARE v_p online.payments%ROWTYPE; v_o online.orders%ROWTYPE; v_s online.stores%ROWTYPE; v_day DATE; v_no INT; v_code TEXT;
BEGIN
  SELECT * INTO v_p FROM online.payments WHERE id = p_id FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'payment_not_found'); END IF;
  IF v_p.status IN ('PAID', 'REFUNDING', 'VOIDED', 'REFUNDED') THEN RETURN jsonb_build_object('success', true, 'duplicate', true); END IF;
  IF coalesce(trim(p_transaction_id), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_transaction'); END IF;
  IF p_paid_cents IS DISTINCT FROM v_p.amount_cents + v_p.tip_cents THEN
    UPDATE online.payments SET reason = 'amount_mismatch: charged ' || coalesce(p_paid_cents::TEXT, '?') || ' tx ' || trim(p_transaction_id)
     WHERE id = p_id;
    RETURN jsonb_build_object('success', false, 'message', 'amount_mismatch');
  END IF;
  SELECT * INTO v_o FROM online.orders WHERE id = v_p.order_id FOR UPDATE;
  IF v_o.status NOT IN ('AWAITING_PAYMENT', 'EXPIRED') THEN
    -- The order was already paid by another attempt: this money is extra -- a person refunds it.
    UPDATE online.payments SET status = 'PAID', paid_at = now(), transaction_id = trim(p_transaction_id),
           auth_code = nullif(trim(p_auth_code), ''), card = nullif(trim(p_card), ''), reason = 'second_payment'
     WHERE id = p_id;
    RETURN jsonb_build_object('success', false, 'message', 'second_payment');
  END IF;

  UPDATE online.payments SET status = 'PAID', paid_at = now(), transaction_id = trim(p_transaction_id),
         auth_code = nullif(trim(p_auth_code), ''), card = nullif(trim(p_card), ''), reason = NULL
   WHERE id = p_id;

  SELECT * INTO v_s FROM online.stores WHERE org_id = v_o.org_id FOR UPDATE;
  -- Numbered within the day the order is collected: a booked order takes its booked day's next number, so
  -- tomorrow's first walk-up order is A2, not a second A1. The store row lock above keeps two payments apart.
  v_day := (coalesce(v_o.scheduled_for, now()) AT TIME ZONE v_s.timezone)::DATE;
  SELECT coalesce(max(pickup_no), 0) + 1 INTO v_no FROM online.orders
   WHERE org_id = v_o.org_id AND pickup_day = v_day AND pickup_no IS NOT NULL;
  IF v_day = (now() AT TIME ZONE v_s.timezone)::DATE THEN
    UPDATE online.stores SET last_pickup_day = v_day, last_pickup_no = v_no WHERE org_id = v_s.org_id;
  END IF;
  UPDATE online.orders SET status = 'NEW', paid_at = now(), tip_cents = v_p.tip_cents, pickup_no = v_no, pickup_day = v_day
   WHERE id = v_o.id;
  v_code := CASE WHEN v_o.fulfilment = 'PICKUP' THEN 'A' ELSE 'D' END || v_no;
  RETURN jsonb_build_object('success', true, 'pickup_code', v_code,
    'mail', jsonb_build_object('email', v_p.email, 'lang', v_p.lang, 'token', v_o.public_token, 'code', v_code,
      'fulfilment', v_o.fulfilment, 'store', (SELECT coalesce(nullif(trim(brand_name), ''), name) FROM organizations WHERE id = v_o.org_id),
      'total_cents', v_p.amount_cents + v_p.tip_cents));
END $function$;
