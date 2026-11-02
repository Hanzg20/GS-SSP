-- 2026-11-02: cash drawer and till shifts (Ourea; the drawer hangs off a network receipt printer).
--
-- retail.drawer_sessions: one per terminal from "open the drawer" (the float counted in)
-- to "close" (the cash counted out). One open session per terminal.
-- retail.drawer_moves: cash put in (PAY_IN: change from the bank...) or taken out
-- (PAY_OUT: supplies, cash tips paid out, a bank drop...) during a session, and NO_SALE
-- (the drawer opened without a sale; 0 cents, just logged).
--
-- Expected cash in the drawer = float
--   + cash sales on this terminal since it opened (amount, tax and tip included -- every
--     CASH sale was paid in full, later refunds are taken off below)
--   - cash refunds on this terminal since it opened (a whole cash sale refunded / voided:
--     its amount at refunded_at; a partial refund paid in cash: transaction_refunds.how = 'CASH')
--   + pay-ins - pay-outs.
-- Counted - expected = over (+) / short (-), kept on the session when it closes.
--
-- Terminals only (retail_drawer_* RPCs, terminal from the calling device); Cael reads
-- closed sessions through cmp_retail_drawer_sessions (store member).

CREATE SCHEMA IF NOT EXISTS retail;

CREATE TABLE IF NOT EXISTS retail.drawer_sessions (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id               UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  device_sn            TEXT NOT NULL,
  opened_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  opened_by_staff_id   UUID REFERENCES public.staff(id) ON DELETE SET NULL,
  opened_by_name       TEXT NOT NULL,
  float_cents          INTEGER NOT NULL CHECK (float_cents >= 0),
  closed_at            TIMESTAMPTZ,
  closed_by_staff_id   UUID REFERENCES public.staff(id) ON DELETE SET NULL,
  closed_by_name       TEXT,
  expected_cents       INTEGER,
  counted_cents        INTEGER CHECK (counted_cents IS NULL OR counted_cents >= 0),
  close_note           TEXT,
  CONSTRAINT drawer_closed_whole CHECK ((closed_at IS NULL) = (counted_cents IS NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS drawer_sessions_one_open ON retail.drawer_sessions (org_id, device_sn) WHERE closed_at IS NULL;
CREATE INDEX IF NOT EXISTS drawer_sessions_org_opened_idx ON retail.drawer_sessions (org_id, opened_at);
COMMENT ON TABLE retail.drawer_sessions IS 'Cash drawer sessions per terminal: float in, cash counted out, over/short; written by Ourea through retail_drawer_* RPCs';

CREATE TABLE IF NOT EXISTS retail.drawer_moves (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id  UUID NOT NULL REFERENCES retail.drawer_sessions(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL CHECK (kind IN ('PAY_IN', 'PAY_OUT', 'NO_SALE')),
  cents       INTEGER NOT NULL CHECK (cents >= 0),
  note        TEXT,
  staff_id    UUID REFERENCES public.staff(id) ON DELETE SET NULL,
  staff_name  TEXT NOT NULL,
  approved_by_name TEXT,
  ref         TEXT UNIQUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT drawer_move_cents CHECK ((kind = 'NO_SALE') = (cents = 0))
);
CREATE INDEX IF NOT EXISTS drawer_moves_session_idx ON retail.drawer_moves (session_id);
COMMENT ON TABLE retail.drawer_moves IS 'Cash put in / taken out of a drawer during a session, and drawer opens without a sale; written by Ourea';

REVOKE ALL ON retail.drawer_sessions, retail.drawer_moves FROM public, anon, authenticated;

-- ---------------------------------------------------------------- helpers (not callable from the API)

-- The calling terminal: its store and serial number.
CREATE OR REPLACE FUNCTION public.retail_drawer_device(OUT org_id UUID, OUT device_sn TEXT)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT m.org_id, m.device_sn FROM device_auth_map m
   WHERE m.auth_user_id = auth.uid() AND m.org_id IS NOT NULL AND m.device_sn IS NOT NULL
   ORDER BY m.created_at DESC LIMIT 1
$$;

-- A session's money: float, cash sales, cash refunds, pay-ins / -outs, expected (up to now, or to its close).
CREATE OR REPLACE FUNCTION public.retail_drawer_summary(p_session UUID)
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, retail AS $$
  WITH s AS (SELECT * FROM retail.drawer_sessions WHERE id = p_session),
  win AS (SELECT s.device_sn, s.opened_at AS t1, coalesce(s.closed_at, 'infinity'::timestamptz) AS t2 FROM s),
  sales AS (
    SELECT count(*) AS n, coalesce(sum(t.amount), 0)::BIGINT AS cents
      FROM transactions t, win
     WHERE t.device_sn = win.device_sn AND t.payment_method = 'CASH'
       AND t.payment_status IN ('PAID', 'REFUNDED', 'VOIDED')
       AND t.created_at >= win.t1 AND t.created_at < win.t2),
  refunds AS (
    SELECT coalesce(sum(x.cents), 0)::BIGINT AS cents, count(*) AS n FROM (
      SELECT t.amount - coalesce(t.refunded_cents, 0) AS cents
        FROM transactions t, win
       WHERE t.device_sn = win.device_sn AND t.payment_method = 'CASH'
         AND t.payment_status IN ('REFUNDED', 'VOIDED')
         AND t.refunded_at >= win.t1 AND t.refunded_at < win.t2
      UNION ALL
      SELECT r.amount_cents
        FROM transaction_refunds r JOIN transactions t ON t.ecr_ref_num = r.ecr_ref_num, win
       WHERE r.device_sn = win.device_sn AND r.how = 'CASH'
         AND r.refunded_at >= win.t1 AND r.refunded_at < win.t2) x),
  moves AS (
    SELECT coalesce(sum(cents) FILTER (WHERE kind = 'PAY_IN'), 0)::BIGINT AS pay_in,
           coalesce(sum(cents) FILTER (WHERE kind = 'PAY_OUT'), 0)::BIGINT AS pay_out,
           count(*) FILTER (WHERE kind = 'NO_SALE') AS no_sale,
           coalesce(jsonb_agg(jsonb_build_object('kind', kind, 'cents', cents, 'note', note, 'by', staff_name,
                    'approved_by', approved_by_name, 'at', created_at) ORDER BY created_at), '[]') AS list
      FROM retail.drawer_moves WHERE session_id = p_session)
  SELECT jsonb_build_object(
    'id', s.id, 'device_sn', s.device_sn, 'open', s.closed_at IS NULL,
    'opened_at', s.opened_at, 'opened_by', s.opened_by_name, 'float_cents', s.float_cents,
    'cash_sales', sales.n, 'cash_sales_cents', sales.cents,
    'cash_refunds', refunds.n, 'cash_refunds_cents', refunds.cents,
    'pay_in_cents', moves.pay_in, 'pay_out_cents', moves.pay_out, 'no_sale_opens', moves.no_sale,
    'moves', moves.list,
    -- Fixed when it closed; while open, up to now.
    'expected_cents', coalesce(s.expected_cents, s.float_cents + sales.cents - refunds.cents + moves.pay_in - moves.pay_out),
    'closed_at', s.closed_at, 'closed_by', s.closed_by_name,
    'counted_cents', s.counted_cents,
    'over_short_cents', CASE WHEN s.counted_cents IS NULL THEN NULL ELSE s.counted_cents - s.expected_cents END,
    'close_note', s.close_note)
  FROM s, sales, refunds, moves
$$;

-- ---------------------------------------------------------------- terminal RPCs

-- This terminal's drawer: the open session with its money so far, or {open: false}.
CREATE OR REPLACE FUNCTION public.retail_drawer_status()
RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, retail AS $$
DECLARE d RECORD; sid UUID;
BEGIN
  SELECT * INTO d FROM retail_drawer_device();
  IF d.org_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_terminal'); END IF;
  SELECT id INTO sid FROM retail.drawer_sessions WHERE org_id = d.org_id AND device_sn = d.device_sn AND closed_at IS NULL;
  IF sid IS NULL THEN RETURN jsonb_build_object('success', true, 'open', false); END IF;
  RETURN jsonb_build_object('success', true) || retail_drawer_summary(sid);
END;
$$;

CREATE OR REPLACE FUNCTION public.retail_drawer_open(p_float_cents INTEGER, p_staff_name TEXT, p_staff_id UUID DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, retail AS $$
DECLARE d RECORD; sid UUID;
BEGIN
  SELECT * INTO d FROM retail_drawer_device();
  IF d.org_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_terminal'); END IF;
  IF p_float_cents IS NULL OR p_float_cents < 0 OR p_float_cents > 10000000 THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_amount');
  END IF;
  IF EXISTS (SELECT 1 FROM retail.drawer_sessions WHERE org_id = d.org_id AND device_sn = d.device_sn AND closed_at IS NULL) THEN
    RETURN jsonb_build_object('success', false, 'message', 'already_open');
  END IF;
  INSERT INTO retail.drawer_sessions (org_id, device_sn, opened_by_staff_id, opened_by_name, float_cents)
  VALUES (d.org_id, d.device_sn,
          (SELECT id FROM staff WHERE id = p_staff_id AND org_id = d.org_id),
          left(coalesce(nullif(trim(p_staff_name), ''), '?'), 80), p_float_cents)
  RETURNING id INTO sid;
  RETURN jsonb_build_object('success', true) || retail_drawer_summary(sid);
END;
$$;

-- Cash in / out (a reason required for both), or a drawer open without a sale.
CREATE OR REPLACE FUNCTION public.retail_drawer_move(p_kind TEXT, p_cents INTEGER, p_note TEXT, p_staff_name TEXT,
                                                     p_ref TEXT, p_staff_id UUID DEFAULT NULL, p_approved_by TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, retail AS $$
DECLARE d RECORD; sid UUID;
BEGIN
  SELECT * INTO d FROM retail_drawer_device();
  IF d.org_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_terminal'); END IF;
  SELECT id INTO sid FROM retail.drawer_sessions WHERE org_id = d.org_id AND device_sn = d.device_sn AND closed_at IS NULL;
  IF sid IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_open'); END IF;
  IF p_kind NOT IN ('PAY_IN', 'PAY_OUT', 'NO_SALE') THEN RETURN jsonb_build_object('success', false, 'message', 'bad_kind'); END IF;
  IF p_kind = 'NO_SALE' THEN p_cents := 0;
  ELSIF p_cents IS NULL OR p_cents <= 0 OR p_cents > 10000000 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_amount');
  ELSIF nullif(trim(p_note), '') IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'reason_required');
  END IF;
  -- A retry from the terminal (same ref) is the same move.
  IF p_ref IS NOT NULL AND EXISTS (SELECT 1 FROM retail.drawer_moves WHERE ref = p_ref) THEN
    RETURN jsonb_build_object('success', true) || retail_drawer_summary(sid);
  END IF;
  INSERT INTO retail.drawer_moves (session_id, kind, cents, note, staff_id, staff_name, approved_by_name, ref)
  VALUES (sid, p_kind, p_cents, left(nullif(trim(p_note), ''), 200),
          (SELECT id FROM staff WHERE id = p_staff_id AND org_id = d.org_id),
          left(coalesce(nullif(trim(p_staff_name), ''), '?'), 80), left(nullif(trim(p_approved_by), ''), 80), p_ref);
  RETURN jsonb_build_object('success', true) || retail_drawer_summary(sid);
END;
$$;

-- Close with the cash counted: expected is fixed at this moment, over / short comes back.
CREATE OR REPLACE FUNCTION public.retail_drawer_close(p_counted_cents INTEGER, p_staff_name TEXT, p_note TEXT DEFAULT NULL, p_staff_id UUID DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, retail AS $$
DECLARE d RECORD; sid UUID; expected INTEGER;
BEGIN
  SELECT * INTO d FROM retail_drawer_device();
  IF d.org_id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_a_terminal'); END IF;
  IF p_counted_cents IS NULL OR p_counted_cents < 0 OR p_counted_cents > 10000000 THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_amount');
  END IF;
  SELECT id INTO sid FROM retail.drawer_sessions WHERE org_id = d.org_id AND device_sn = d.device_sn AND closed_at IS NULL FOR UPDATE;
  IF sid IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_open'); END IF;
  expected := (retail_drawer_summary(sid)->>'expected_cents')::INTEGER;
  UPDATE retail.drawer_sessions
     SET closed_at = now(), counted_cents = p_counted_cents, expected_cents = expected,
         closed_by_staff_id = (SELECT id FROM staff WHERE id = p_staff_id AND org_id = d.org_id),
         closed_by_name = left(coalesce(nullif(trim(p_staff_name), ''), '?'), 80),
         close_note = left(nullif(trim(p_note), ''), 300)
   WHERE id = sid;
  RETURN jsonb_build_object('success', true) || retail_drawer_summary(sid);
END;
$$;

-- This terminal's last closed sessions (for the drawer screen).
CREATE OR REPLACE FUNCTION public.retail_drawer_history(p_limit INTEGER DEFAULT 10)
RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, retail AS $$
DECLARE d RECORD;
BEGIN
  SELECT * INTO d FROM retail_drawer_device();
  IF d.org_id IS NULL THEN RETURN '[]'::jsonb; END IF;
  RETURN coalesce((SELECT jsonb_agg(retail_drawer_summary(id) ORDER BY closed_at DESC) FROM (
    SELECT id, closed_at FROM retail.drawer_sessions
     WHERE org_id = d.org_id AND device_sn = d.device_sn AND closed_at IS NOT NULL
     ORDER BY closed_at DESC LIMIT least(greatest(coalesce(p_limit, 10), 1), 50)) x), '[]'::jsonb);
END;
$$;

-- ---------------------------------------------------------------- Cael

-- A store's drawer sessions opened in a range of days (open ones included).
CREATE OR REPLACE FUNCTION public.cmp_retail_drawer_sessions(p_org UUID, p_from DATE, p_to DATE)
RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, retail AS $$
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN coalesce((SELECT jsonb_agg(retail_drawer_summary(id) ORDER BY opened_at DESC) FROM retail.drawer_sessions
     WHERE org_id = p_org
       AND opened_at >= (p_from::timestamp AT TIME ZONE 'America/Toronto')
       AND opened_at < ((p_to + 1)::timestamp AT TIME ZONE 'America/Toronto')), '[]'::jsonb);
END;
$$;

REVOKE ALL ON FUNCTION public.retail_drawer_device() FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.retail_drawer_summary(UUID) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.retail_drawer_status() FROM public, anon;
REVOKE ALL ON FUNCTION public.retail_drawer_open(INTEGER, TEXT, UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.retail_drawer_move(TEXT, INTEGER, TEXT, TEXT, TEXT, UUID, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.retail_drawer_close(INTEGER, TEXT, TEXT, UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.retail_drawer_history(INTEGER) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_retail_drawer_sessions(UUID, DATE, DATE) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.retail_drawer_status() TO authenticated;
GRANT EXECUTE ON FUNCTION public.retail_drawer_open(INTEGER, TEXT, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.retail_drawer_move(TEXT, INTEGER, TEXT, TEXT, TEXT, UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.retail_drawer_close(INTEGER, TEXT, TEXT, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.retail_drawer_history(INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_retail_drawer_sessions(UUID, DATE, DATE) TO authenticated;

NOTIFY pgrst, 'reload schema';
