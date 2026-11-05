-- 2026-11-05: a held (PREAUTH) FLEX_DISPENSE answered DISPENSED must say how much to charge.
-- Without it the terminal settles the whole hold (the API's default for a real controller) --
-- a Cael page opened before the charge box existed answered without one, and a test meant to be
-- $0.10 settled $8.00 (FLX-1793915920480). The page always sends it now; this refuses one that doesn't.

CREATE OR REPLACE FUNCTION public.testsim_answer(p_id UUID, p_answer JSONB) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, testsim AS $$
DECLARE v_n INT; r testsim.requests;
BEGIN
  IF NOT is_sys_admin() THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  IF p_answer ->> 'result' NOT IN ('DISPENSED', 'FAILED') THEN
    RETURN jsonb_build_object('success', false, 'message', 'invalid_answer');
  END IF;
  SELECT * INTO r FROM testsim.requests WHERE id = p_id;
  IF p_answer ->> 'result' = 'DISPENSED' AND r.payload ->> 'payment_flow' = 'PREAUTH' AND (
       jsonb_typeof(p_answer -> 'amount_cents') IS DISTINCT FROM 'number'
       OR (p_answer ->> 'amount_cents')::numeric < 0
       OR (p_answer ->> 'amount_cents')::numeric > coalesce((r.payload ->> 'amount_cents')::numeric, 0)) THEN
    RETURN jsonb_build_object('success', false, 'message', 'amount_required');
  END IF;
  UPDATE testsim.requests SET answer = p_answer, answered_at = now(), answered_by = auth.uid()
   WHERE id = p_id AND answer IS NULL AND deadline >= now();
  GET DIAGNOSTICS v_n = ROW_COUNT;
  RETURN jsonb_build_object('success', v_n = 1, 'message', CASE WHEN v_n = 1 THEN NULL ELSE 'already_answered_or_expired' END);
END $$;

REVOKE ALL ON FUNCTION public.testsim_answer(UUID, JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.testsim_answer(UUID, JSONB) TO authenticated;
