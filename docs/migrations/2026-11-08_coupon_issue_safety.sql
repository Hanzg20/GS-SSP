-- 2026-11-08 (owner, coupon fixes 2 and 4):
--
-- 2. Issuing a coupon can no longer go wrong by a slip in Cael: coupons are created only
--    through cmp_create_coupons (checked values, codes made here), never by a direct INSERT;
--    the table refuses impossible values (more than 100% off, a negative amount, a code used
--    more than 1000 times). Cael may still switch a coupon on / off (is_active only).
-- 4. issue_compensation_coupon: the old code picker could step past the alphabet and give
--    7- or 6-character codes (a 6-character one is read as a VIP member code by the terminal
--    and can never be used). Codes are now 10 characters from a crypto random source, the
--    amount and uses are capped, the transaction must belong to the store, and a second
--    request for the same transaction gives back the unused coupon instead of a new one.
--
-- Constraints are added NOT VALID (old rows untouched); validated separately once the
-- existing rows are checked.

BEGIN;

-- 10-character code (31 symbols, no 0/O/1/I/L), crypto random, no modulo bias.
CREATE OR REPLACE FUNCTION public.coupon_random_code(p_len INT) RETURNS TEXT
LANGUAGE plpgsql VOLATILE SET search_path = public AS $$
DECLARE
  a CONSTANT TEXT := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  b BYTEA;
  v INT;
  v_out TEXT := '';
BEGIN
  WHILE length(v_out) < p_len LOOP
    b := extensions.gen_random_bytes(16);
    FOR i IN 0..15 LOOP
      v := get_byte(b, i);
      IF v < 248 AND length(v_out) < p_len THEN -- 248 = 8 x 31
        v_out := v_out || substr(a, 1 + v % 31, 1);
      END IF;
    END LOOP;
  END LOOP;
  RETURN v_out;
END $$;
REVOKE ALL ON FUNCTION public.coupon_random_code(INT) FROM PUBLIC, anon, authenticated;

ALTER TABLE public.coupons DROP CONSTRAINT IF EXISTS coupons_value_check;
ALTER TABLE public.coupons ADD CONSTRAINT coupons_value_check CHECK (
  (type = 'PERCENT_OFF' AND value BETWEEN 1 AND 100) OR
  (type = 'FIXED_OFF' AND value BETWEEN 1 AND 100000) OR
  (type = 'FREE_WASH')
) NOT VALID;
ALTER TABLE public.coupons DROP CONSTRAINT IF EXISTS coupons_uses_check;
ALTER TABLE public.coupons ADD CONSTRAINT coupons_uses_check CHECK (
  max_uses BETWEEN 1 AND 1000 AND uses_count >= 0
) NOT VALID;

-- Marketing coupons: Cael's Voucher Hub.
CREATE OR REPLACE FUNCTION public.cmp_create_coupons(
  p_org_id UUID,
  p_type TEXT,
  p_value INT,
  p_max_uses INT,
  p_count INT,
  p_prefix TEXT DEFAULT NULL,
  p_product_id UUID DEFAULT NULL,
  p_expires_at TIMESTAMPTZ DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_prefix TEXT := left(regexp_replace(upper(coalesce(p_prefix, '')), '[^ABCDEFGHJKMNPQRSTUVWXYZ23456789]', '', 'g'), 3);
  v_value INT := p_value;
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

  WHILE coalesce(array_length(v_codes, 1), 0) < p_count LOOP
    v_code := v_prefix || public.coupon_random_code(10 - length(v_prefix));
    INSERT INTO coupons (code, org_id, type, value, applicable_product_id, max_uses, expires_at, issued_reason, issued_by_profile_id)
    VALUES (v_code, p_org_id, p_type, v_value, p_product_id, p_max_uses, p_expires_at, 'MARKETING', auth.uid())
    ON CONFLICT (code) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n = 1 THEN v_codes := v_codes || v_code; END IF;
  END LOOP;

  INSERT INTO audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), p_org_id, 'CREATE_COUPONS', 'coupons', v_codes[1],
          jsonb_build_object('count', p_count, 'type', p_type, 'value', v_value, 'max_uses', p_max_uses,
                             'product_id', p_product_id, 'expires_at', p_expires_at, 'codes', to_jsonb(v_codes)));

  RETURN jsonb_build_object('success', true, 'codes', to_jsonb(v_codes));
END $$;
REVOKE ALL ON FUNCTION public.cmp_create_coupons(UUID, TEXT, INT, INT, INT, TEXT, UUID, TIMESTAMPTZ) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cmp_create_coupons(UUID, TEXT, INT, INT, INT, TEXT, UUID, TIMESTAMPTZ) TO authenticated;

-- No more direct writes from the portal: create through the RPC, change only is_active.
REVOKE INSERT, UPDATE, DELETE ON public.coupons FROM anon, authenticated;
GRANT UPDATE (is_active) ON public.coupons TO authenticated;

CREATE OR REPLACE FUNCTION public.issue_compensation_coupon(
  p_org_id UUID,
  p_value_cents INT,
  p_max_uses INT DEFAULT 1,
  p_related_transaction_id UUID DEFAULT NULL
) RETURNS JSON
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_code TEXT;
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

  LOOP
    v_code := public.coupon_random_code(10);
    INSERT INTO coupons (code, org_id, type, value, max_uses, issued_reason, issued_by_profile_id, related_transaction_id)
    VALUES (v_code, p_org_id, 'FIXED_OFF', p_value_cents, p_max_uses, 'COMPENSATION', auth.uid(), p_related_transaction_id)
    ON CONFLICT (code) DO NOTHING;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    EXIT WHEN v_n = 1;
  END LOOP;

  INSERT INTO audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), p_org_id, 'ISSUE_COMPENSATION_COUPON', 'coupons', v_code,
          json_build_object('value_cents', p_value_cents, 'max_uses', p_max_uses, 'related_transaction_id', p_related_transaction_id));

  RETURN json_build_object('success', true, 'code', v_code);
END $$;
REVOKE ALL ON FUNCTION public.issue_compensation_coupon(UUID, INT, INT, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.issue_compensation_coupon(UUID, INT, INT, UUID) TO authenticated;

COMMIT;
