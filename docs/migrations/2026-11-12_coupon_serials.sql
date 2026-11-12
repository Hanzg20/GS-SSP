-- 2026-11-12 (owner): vouchers get a number, a time and a note, so they can be handed out and followed.
--
-- One code that is also the number: <serial><4 random>, shown as 0123-K7MQ.
--   * serial = the store's running number (every coupon, every kind, every value), at least 4
--     digits, so a code is never 6 characters long (6 = a VIP member code on the terminal);
--     from 10000 on it simply grows to 9.
--   * 4 random characters (coupon_random_code's 31 symbols, crypto random) make it unguessable:
--     ~923k per number, tried only at a terminal, 10 refused codes in 10 minutes and the
--     terminal is refused (coupon_attempts, 2026-11-08).
--   * The QR carries the plain form 0123K7MQ; a typed 0123-K7MQ / 0123 k7mq is read the same.
--     Codes made before keep working as they are (some of the old ones have dashes).
-- No batches (owner): a note given when the coupons are made ("Grand opening flyers") finds
-- them again, and "given to" (one line, who and why) records a coupon handed to someone.
-- Old coupons are numbered in the order they were made.

BEGIN;

ALTER TABLE public.coupons ADD COLUMN IF NOT EXISTS serial INT;
ALTER TABLE public.coupons ADD COLUMN IF NOT EXISTS note TEXT;
ALTER TABLE public.coupons ADD COLUMN IF NOT EXISTS given_to TEXT;
ALTER TABLE public.coupons ADD COLUMN IF NOT EXISTS given_at TIMESTAMPTZ;
ALTER TABLE public.coupons ADD COLUMN IF NOT EXISTS given_by_profile_id UUID REFERENCES public.profiles(id);
COMMENT ON COLUMN public.coupons.serial IS 'The store''s running coupon number (0123 in 0123-K7MQ). 2026-11-12.';
COMMENT ON COLUMN public.coupons.note IS 'Why these coupons were made, given when making them. 2026-11-12.';
COMMENT ON COLUMN public.coupons.given_to IS 'Who the coupon was handed to (one line, no contact details needed); cmp_give_coupons. 2026-11-12.';

WITH n AS (
  SELECT code, row_number() OVER (PARTITION BY org_id ORDER BY created_at, code) AS s
    FROM public.coupons WHERE serial IS NULL
)
UPDATE public.coupons c SET serial = n.s FROM n WHERE c.code = n.code;
ALTER TABLE public.coupons ALTER COLUMN serial SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS coupons_org_serial ON public.coupons(org_id, serial);

-- The next number of a store; the caller holds the store's lock (coupon_lock_serial).
CREATE OR REPLACE FUNCTION public.coupon_lock_serial(p_org_id UUID) RETURNS INT
LANGUAGE plpgsql VOLATILE SET search_path = public AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('coupon_serial:' || p_org_id::text, 0));
  RETURN coalesce((SELECT max(serial) FROM coupons WHERE org_id = p_org_id), 0) + 1;
END $$;
REVOKE ALL ON FUNCTION public.coupon_lock_serial(UUID) FROM PUBLIC, anon, authenticated;

-- A scanned / typed code as stored: exact first (old codes with dashes), else without spaces
-- and dashes, upper case.
CREATE OR REPLACE FUNCTION public.coupon_find_code(p_code TEXT) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = public AS $$
  SELECT code FROM coupons
   WHERE code = p_code OR code = upper(regexp_replace(coalesce(p_code, ''), '[\s-]', '', 'g'))
   ORDER BY (code = p_code) DESC LIMIT 1
$$;
REVOKE ALL ON FUNCTION public.coupon_find_code(TEXT) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.coupon_take(p_code TEXT, p_sn TEXT, p_consume BOOLEAN, p_ref TEXT, p_limit_key TEXT)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_org UUID;
  v_fails INT;
  v_code TEXT;
  c RECORD;
  v_rid UUID;
BEGIN
  SELECT org_id INTO v_org FROM devices WHERE sn = p_sn;
  SELECT count(*) INTO v_fails FROM coupon_attempts
   WHERE limit_key = p_limit_key AND at > now() - interval '10 minutes';
  IF v_org IS NULL OR v_fails >= 10 THEN
    RETURN jsonb_build_object('success', false, 'message', 'unavailable');
  END IF;

  v_code := public.coupon_find_code(p_code);
  IF p_consume THEN
    SELECT * INTO c FROM coupons WHERE code = v_code FOR UPDATE;
  ELSE
    SELECT * INTO c FROM coupons WHERE code = v_code;
  END IF;
  IF NOT FOUND OR NOT coalesce(c.is_active, false)
     OR (c.expires_at IS NOT NULL AND c.expires_at < now())
     OR c.uses_count >= c.max_uses
     OR c.org_id <> v_org THEN
    INSERT INTO coupon_attempts (limit_key) VALUES (p_limit_key);
    DELETE FROM coupon_attempts WHERE limit_key = p_limit_key AND at < now() - interval '1 day';
    RETURN jsonb_build_object('success', false, 'message', 'unavailable');
  END IF;

  IF p_consume THEN
    UPDATE coupons SET uses_count = uses_count + 1 WHERE code = v_code;
    INSERT INTO coupon_redemptions (coupon_code, device_sn, ecr_ref_num)
    VALUES (v_code, p_sn, nullif(p_ref, '')) RETURNING id INTO v_rid;
  END IF;

  RETURN jsonb_build_object(
    'success', true,
    'type', c.type,
    'value', c.value,
    'applicable_product_id', c.applicable_product_id,
    'expires_at', c.expires_at,
    'redemption_id', v_rid
  );
END $$;
REVOKE ALL ON FUNCTION public.coupon_take(TEXT, TEXT, BOOLEAN, TEXT, TEXT) FROM PUBLIC, anon, authenticated;

-- Marketing coupons (Cael's Vouchers): numbered codes, an optional note. Replaces the 8-argument one.
DROP FUNCTION IF EXISTS public.cmp_create_coupons(UUID, TEXT, INT, INT, INT, TEXT, UUID, TIMESTAMPTZ);
CREATE OR REPLACE FUNCTION public.cmp_create_coupons(
  p_org_id UUID,
  p_type TEXT,
  p_value INT,
  p_max_uses INT,
  p_count INT,
  p_prefix TEXT DEFAULT NULL,          -- no longer used (2026-11-12: the number leads the code); kept for old callers
  p_product_id UUID DEFAULT NULL,
  p_expires_at TIMESTAMPTZ DEFAULT NULL,
  p_note TEXT DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_value INT := p_value;
  v_note TEXT := nullif(left(btrim(coalesce(p_note, '')), 200), '');
  v_serial INT;
  v_first INT;
  v_code TEXT;
  v_codes TEXT[] := '{}';
  v_n INT;
BEGIN
  IF auth.uid() IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_authenticated'); END IF;
  IF NOT public.has_permission_for_org('coupons.manage', p_org_id) THEN
    RETURN jsonb_build_object('success', false, 'message', 'not_authorized');
  END IF;
  IF p_type = 'PERCENT_OFF' THEN
    IF p_value IS NULL OR p_value NOT BETWEEN 1 AND 100 THEN RETURN jsonb_build_object('success', false, 'message', 'invalid_percent'); END IF;
  ELSIF p_type = 'FIXED_OFF' THEN
    IF p_value IS NULL OR p_value NOT BETWEEN 1 AND 100000 THEN RETURN jsonb_build_object('success', false, 'message', 'invalid_amount'); END IF;
  ELSIF p_type = 'FREE_WASH' THEN
    v_value := 0;
  ELSE
    RETURN jsonb_build_object('success', false, 'message', 'invalid_type');
  END IF;
  IF p_max_uses IS NULL OR p_max_uses NOT BETWEEN 1 AND 1000 THEN RETURN jsonb_build_object('success', false, 'message', 'invalid_max_uses'); END IF;
  IF p_count IS NULL OR p_count NOT BETWEEN 1 AND 200 THEN RETURN jsonb_build_object('success', false, 'message', 'invalid_count'); END IF;
  IF p_expires_at IS NOT NULL AND p_expires_at <= now() THEN RETURN jsonb_build_object('success', false, 'message', 'invalid_expiry'); END IF;
  IF p_product_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM products WHERE id = p_product_id AND org_id = p_org_id) THEN
    RETURN jsonb_build_object('success', false, 'message', 'invalid_product');
  END IF;

  v_serial := public.coupon_lock_serial(p_org_id);
  v_first := v_serial;
  FOR i IN 1..p_count LOOP
    LOOP
      v_code := lpad(v_serial::text, 4, '0') || public.coupon_random_code(4);
      INSERT INTO coupons (code, org_id, serial, type, value, applicable_product_id, max_uses, expires_at, issued_reason, issued_by_profile_id, note)
      VALUES (v_code, p_org_id, v_serial, p_type, v_value, p_product_id, p_max_uses, p_expires_at, 'MARKETING', auth.uid(), v_note)
      ON CONFLICT (code) DO NOTHING;
      GET DIAGNOSTICS v_n = ROW_COUNT;
      EXIT WHEN v_n = 1;
    END LOOP;
    v_codes := v_codes || v_code;
    v_serial := v_serial + 1;
  END LOOP;

  INSERT INTO audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), p_org_id, 'CREATE_COUPONS', 'coupons', v_codes[1],
          jsonb_build_object('count', p_count, 'type', p_type, 'value', v_value, 'max_uses', p_max_uses,
                             'product_id', p_product_id, 'expires_at', p_expires_at, 'note', v_note,
                             'serials', jsonb_build_array(v_first, v_serial - 1), 'codes', to_jsonb(v_codes)));

  RETURN jsonb_build_object('success', true, 'codes', to_jsonb(v_codes), 'first', v_first, 'last', v_serial - 1);
END $$;
REVOKE ALL ON FUNCTION public.cmp_create_coupons(UUID, TEXT, INT, INT, INT, TEXT, UUID, TIMESTAMPTZ, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cmp_create_coupons(UUID, TEXT, INT, INT, INT, TEXT, UUID, TIMESTAMPTZ, TEXT) TO authenticated;

-- Compensation coupons: same numbered codes (body otherwise as 2026-11-08_coupon_issue_safety.sql).
CREATE OR REPLACE FUNCTION public.issue_compensation_coupon(
  p_org_id UUID,
  p_value_cents INT,
  p_max_uses INT DEFAULT 1,
  p_related_transaction_id UUID DEFAULT NULL
) RETURNS JSON
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_code TEXT;
  v_serial INT;
  v_n INT;
BEGIN
  IF auth.uid() IS NULL THEN RETURN json_build_object('success', false, 'message', 'not_authenticated'); END IF;
  IF p_value_cents IS NULL OR p_value_cents NOT BETWEEN 1 AND 100000 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;
  IF p_max_uses IS NULL OR p_max_uses NOT BETWEEN 1 AND 10 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_max_uses');
  END IF;
  IF NOT public.has_permission_for_org('vip.compensate', p_org_id) THEN
    RETURN json_build_object('success', false, 'message', 'not_authorized');
  END IF;

  IF p_related_transaction_id IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1 FROM transactions t JOIN devices d ON d.sn = t.device_sn
       WHERE t.id = p_related_transaction_id AND d.org_id = p_org_id
    ) THEN
      RETURN json_build_object('success', false, 'message', 'invalid_transaction');
    END IF;
    -- Asked twice for the same transaction: the unused coupon already issued, not a new one.
    SELECT code INTO v_code FROM coupons
     WHERE related_transaction_id = p_related_transaction_id AND issued_reason = 'COMPENSATION'
       AND is_active AND uses_count < max_uses
     ORDER BY created_at DESC LIMIT 1;
    IF v_code IS NOT NULL THEN
      RETURN json_build_object('success', true, 'code', v_code, 'existing', true);
    END IF;
  END IF;

  v_serial := public.coupon_lock_serial(p_org_id);
  LOOP
    v_code := lpad(v_serial::text, 4, '0') || public.coupon_random_code(4);
    INSERT INTO coupons (code, org_id, serial, type, value, max_uses, issued_reason, issued_by_profile_id, related_transaction_id)
    VALUES (v_code, p_org_id, v_serial, 'FIXED_OFF', p_value_cents, p_max_uses, 'COMPENSATION', auth.uid(), p_related_transaction_id)
    ON CONFLICT (code) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    EXIT WHEN v_n = 1;
  END LOOP;

  INSERT INTO audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), p_org_id, 'ISSUE_COMPENSATION_COUPON', 'coupons', v_code,
          json_build_object('value_cents', p_value_cents, 'max_uses', p_max_uses, 'related_transaction_id', p_related_transaction_id, 'serial', v_serial));

  RETURN json_build_object('success', true, 'code', v_code);
END $$;
REVOKE ALL ON FUNCTION public.issue_compensation_coupon(UUID, INT, INT, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.issue_compensation_coupon(UUID, INT, INT, UUID) TO authenticated;

-- "Given to": who a coupon was handed to; empty text clears it. Same right as making coupons.
CREATE OR REPLACE FUNCTION public.cmp_give_coupons(p_codes TEXT[], p_given_to TEXT) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_to TEXT := nullif(left(btrim(coalesce(p_given_to, '')), 100), '');
  v_org UUID;
  v_n INT;
BEGIN
  IF auth.uid() IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_authenticated'); END IF;
  IF p_codes IS NULL OR coalesce(array_length(p_codes, 1), 0) NOT BETWEEN 1 AND 200 THEN
    RETURN jsonb_build_object('success', false, 'message', 'invalid_count');
  END IF;
  FOR v_org IN SELECT DISTINCT org_id FROM coupons WHERE code = ANY(p_codes) LOOP
    IF NOT public.has_permission_for_org('coupons.manage', v_org) THEN
      RETURN jsonb_build_object('success', false, 'message', 'not_authorized');
    END IF;
  END LOOP;
  UPDATE coupons
     SET given_to = v_to,
         given_at = CASE WHEN v_to IS NULL THEN NULL ELSE now() END,
         given_by_profile_id = CASE WHEN v_to IS NULL THEN NULL ELSE auth.uid() END
   WHERE code = ANY(p_codes);
  GET DIAGNOSTICS v_n = ROW_COUNT;
  INSERT INTO audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  SELECT auth.uid(), org_id, 'GIVE_COUPONS', 'coupons', min(code), jsonb_build_object('given_to', v_to, 'codes', to_jsonb(array_agg(code)))
    FROM coupons WHERE code = ANY(p_codes) GROUP BY org_id;
  RETURN jsonb_build_object('success', true, 'count', v_n);
END $$;
REVOKE ALL ON FUNCTION public.cmp_give_coupons(TEXT[], TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cmp_give_coupons(TEXT[], TEXT) TO authenticated;

-- Each use of a store's coupons (coupon_redemptions has no portal access): when, which machine,
-- and whether the terminal gave it back (card declined / wash did not start).
CREATE OR REPLACE FUNCTION public.cmp_coupon_uses(p_org_id UUID)
RETURNS TABLE (coupon_code TEXT, redeemed_at TIMESTAMPTZ, device_sn TEXT, device_name TEXT, released_at TIMESTAMPTZ)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF auth.uid() IS NULL OR NOT (public.is_sys_admin() OR p_org_id IN (SELECT public.member_org_ids())) THEN
    RETURN;
  END IF;
  RETURN QUERY
    SELECT r.coupon_code, r.redeemed_at, r.device_sn, nullif(d.alias, ''), r.released_at
      FROM coupon_redemptions r
      JOIN coupons c ON c.code = r.coupon_code
      LEFT JOIN devices d ON d.sn = r.device_sn
     WHERE c.org_id = p_org_id
     ORDER BY r.redeemed_at DESC
     LIMIT 5000;
END $$;
REVOKE ALL ON FUNCTION public.cmp_coupon_uses(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cmp_coupon_uses(UUID) TO authenticated;

COMMIT;
