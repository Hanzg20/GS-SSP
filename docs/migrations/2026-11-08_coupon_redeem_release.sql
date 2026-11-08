-- 2026-11-08 (owner, coupon fixes 1 and 3):
--
-- 1. A coupon is consumed BEFORE the card is asked for (coupon_redeem) and given back if the
--    card is declined / cancelled or the wash does not start (coupon_release). The old order
--    (card first, coupon after, failure only logged) let a coupon be used again whenever the
--    network or the app dropped after the card. Fail-closed: a release that never arrives
--    leaves the coupon used (at worst the customer asks staff for a new one), never free.
-- 3. The new coupon_check / coupon_redeem / coupon_release only answer a terminal session
--    linked to its SN (device_auth_map) -- the SN is taken from the session, not from the
--    caller. Every refusal is the same word, 'unavailable' (no telling not-found from used /
--    expired / another store's), and a terminal with 10 refused codes in 10 minutes gets
--    'unavailable' for everything until the window passes.
--    The old peek_coupon / redeem_coupon stay for the terminals not yet updated (Wash 1.1.12,
--    Timer 1.2.10), now through the same checks: one word for every refusal and the same
--    limit, counted apart ('legacy:<sn>') so a stranger can't lock the new apps out.
--    Revoke them from anon once every terminal runs the new apps.

BEGIN;

ALTER TABLE public.coupon_redemptions ADD COLUMN IF NOT EXISTS ecr_ref_num TEXT;
ALTER TABLE public.coupon_redemptions ADD COLUMN IF NOT EXISTS released_at TIMESTAMPTZ;
ALTER TABLE public.coupon_redemptions ADD COLUMN IF NOT EXISTS release_reason TEXT;
COMMENT ON COLUMN public.coupon_redemptions.ecr_ref_num IS 'The sale the coupon went to (transactions.ecr_ref_num). 2026-11-08.';
COMMENT ON COLUMN public.coupon_redemptions.released_at IS 'Given back by the terminal (card declined, wash did not start): uses_count was taken back. 2026-11-08.';

CREATE TABLE IF NOT EXISTS public.coupon_attempts (
  id        BIGSERIAL PRIMARY KEY,
  limit_key TEXT NOT NULL,      -- device SN, or 'legacy:<sn>' for the old functions
  at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_coupon_attempts_key_at ON public.coupon_attempts(limit_key, at DESC);
COMMENT ON TABLE public.coupon_attempts IS 'Refused coupon codes per terminal, for the 10-in-10-minutes limit. 2026-11-08.';
ALTER TABLE public.coupon_attempts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.coupon_attempts FROM anon, authenticated;

-- The one place a code is checked (and, with p_consume, used).
CREATE OR REPLACE FUNCTION public.coupon_take(p_code TEXT, p_sn TEXT, p_consume BOOLEAN, p_ref TEXT, p_limit_key TEXT)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_org UUID;
  v_fails INT;
  c RECORD;
  v_rid UUID;
BEGIN
  SELECT org_id INTO v_org FROM devices WHERE sn = p_sn;
  SELECT count(*) INTO v_fails FROM coupon_attempts
   WHERE limit_key = p_limit_key AND at > now() - interval '10 minutes';
  IF v_org IS NULL OR v_fails >= 10 THEN
    RETURN jsonb_build_object('success', false, 'message', 'unavailable');
  END IF;

  IF p_consume THEN
    SELECT * INTO c FROM coupons WHERE code = p_code FOR UPDATE;
  ELSE
    SELECT * INTO c FROM coupons WHERE code = p_code;
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
    UPDATE coupons SET uses_count = uses_count + 1 WHERE code = p_code;
    INSERT INTO coupon_redemptions (coupon_code, device_sn, ecr_ref_num)
    VALUES (p_code, p_sn, nullif(p_ref, '')) RETURNING id INTO v_rid;
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

-- The terminal's SN from its own session; NULL = not linked.
CREATE OR REPLACE FUNCTION public.coupon_session_sn() RETURNS TEXT
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT device_sn FROM device_auth_map WHERE auth_user_id = auth.uid() LIMIT 1
$$;
REVOKE ALL ON FUNCTION public.coupon_session_sn() FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.coupon_check(p_code TEXT) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_sn TEXT := public.coupon_session_sn();
BEGIN
  IF v_sn IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_linked'); END IF;
  RETURN public.coupon_take(p_code, v_sn, false, NULL, v_sn);
END $$;

CREATE OR REPLACE FUNCTION public.coupon_redeem(p_code TEXT, p_ref TEXT DEFAULT NULL) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_sn TEXT := public.coupon_session_sn();
BEGIN
  IF v_sn IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_linked'); END IF;
  RETURN public.coupon_take(p_code, v_sn, true, p_ref, v_sn);
END $$;

-- Gives a coupon back: same terminal, once, within 2 hours of using it.
CREATE OR REPLACE FUNCTION public.coupon_release(p_redemption_id UUID, p_reason TEXT DEFAULT NULL) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_sn TEXT := public.coupon_session_sn();
  r RECORD;
BEGIN
  IF v_sn IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_linked'); END IF;
  SELECT * INTO r FROM coupon_redemptions WHERE id = p_redemption_id FOR UPDATE;
  IF NOT FOUND OR r.device_sn IS DISTINCT FROM v_sn OR r.released_at IS NOT NULL
     OR r.redeemed_at < now() - interval '2 hours' THEN
    RETURN jsonb_build_object('success', false, 'message', 'unavailable');
  END IF;
  UPDATE coupons SET uses_count = greatest(uses_count - 1, 0) WHERE code = r.coupon_code;
  UPDATE coupon_redemptions SET released_at = now(), release_reason = left(p_reason, 200) WHERE id = p_redemption_id;
  RETURN jsonb_build_object('success', true);
END $$;

REVOKE ALL ON FUNCTION public.coupon_check(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.coupon_redeem(TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.coupon_release(UUID, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.coupon_check(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.coupon_redeem(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.coupon_release(UUID, TEXT) TO authenticated;

-- Old entry points (terminals not yet updated): same checks, one word for every refusal.
CREATE OR REPLACE FUNCTION public.peek_coupon(p_code TEXT, p_device_sn TEXT) RETURNS JSON
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  RETURN public.coupon_take(p_code, p_device_sn, false, NULL, 'legacy:' || coalesce(p_device_sn, ''))::json;
END $$;

CREATE OR REPLACE FUNCTION public.redeem_coupon(p_code TEXT, p_device_sn TEXT) RETURNS JSON
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  RETURN public.coupon_take(p_code, p_device_sn, true, NULL, 'legacy:' || coalesce(p_device_sn, ''))::json;
END $$;

COMMIT;
