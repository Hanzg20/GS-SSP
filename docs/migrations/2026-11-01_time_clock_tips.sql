-- 2026-11-01: staff time clock and tip split (Iris / Ourea, Cael).
--
-- office.shifts: one row per shift -- clock in, clock out (NULL = on the clock
-- now). Punches come from the terminals with the time they were made and a
-- terminal-made ref, so a punch queued offline lands once, at the right time.
-- One open shift per person. A manager fixes a forgotten clock-out (or a missed
-- shift) on the terminal; the fix is recorded (who, when, why).
--
-- office.settings.tip_split: how a business day's tips are shared --
--   OWN        each keeps the tips on their own sales (default)
--   POOL_HOURS all tips pooled, shared by minutes worked that day
--   POOL_EQUAL all tips pooled, shared equally among those who worked
-- Tips = tip_cents of the day's PAID transactions on the store's terminals, plus
-- Ourea Scan online tips (no staff: pooled, or "unassigned" under OWN). A
-- VOIDED / REFUNDED sale's tip went back; a partial refund never takes the tip.
-- Shares are a report for payroll -- nothing moves money. Rounding: largest
-- remainder, so the shares add up to the pool to the cent.
--
-- Business day: America/Toronto (as checklists). Terminals: office_* RPCs, store
-- from the calling device. Cael: cmp_office_* RPCs, store member (writes:
-- staff.manage).

CREATE TABLE IF NOT EXISTS office.shifts (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id         UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  staff_id       UUID REFERENCES public.staff(id) ON DELETE SET NULL,
  staff_name     TEXT NOT NULL,
  clock_in_at    TIMESTAMPTZ NOT NULL,
  clock_out_at   TIMESTAMPTZ,
  in_device_sn   TEXT,
  out_device_sn  TEXT,
  in_ref         TEXT UNIQUE,
  out_ref        TEXT UNIQUE,
  fixed_by_name  TEXT,
  fixed_note     TEXT,
  fixed_at       TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT shifts_out_after_in CHECK (clock_out_at IS NULL OR clock_out_at >= clock_in_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS shifts_one_open_per_staff ON office.shifts (staff_id) WHERE clock_out_at IS NULL;
CREATE INDEX IF NOT EXISTS shifts_org_in_idx ON office.shifts (org_id, clock_in_at);
COMMENT ON TABLE office.shifts IS 'Staff shifts (time clock): clock in / out per person, fixes by a manager; written by Iris / Ourea through office_* RPCs';

CREATE TABLE IF NOT EXISTS office.settings (
  org_id     UUID PRIMARY KEY REFERENCES public.organizations(id) ON DELETE CASCADE,
  tip_split  TEXT NOT NULL DEFAULT 'OWN' CHECK (tip_split IN ('OWN', 'POOL_HOURS', 'POOL_EQUAL')),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by TEXT
);
COMMENT ON TABLE office.settings IS 'Store back-office settings (tip split rule); written by Iris / Ourea managers and Cael';

REVOKE ALL ON ALL TABLES IN SCHEMA office FROM public, anon, authenticated;

-- ---------------------------------------------------------------- helpers (not callable from the API)

CREATE OR REPLACE FUNCTION public.office_day_window(p_day DATE, OUT day_start TIMESTAMPTZ, OUT day_end TIMESTAMPTZ)
LANGUAGE sql IMMUTABLE AS $$
  SELECT (p_day::timestamp) AT TIME ZONE 'America/Toronto', ((p_day + 1)::timestamp) AT TIME ZONE 'America/Toronto'
$$;

-- A store's day: rule, tips, minutes per person, shares, and the shifts touching the day.
CREATE OR REPLACE FUNCTION public.office_tips_for(p_org UUID, p_day DATE)
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, office, dine AS $$
  WITH w AS (SELECT * FROM office_day_window(p_day)),
  rule AS (SELECT coalesce((SELECT tip_split FROM office.settings WHERE org_id = p_org), 'OWN') AS r),
  tips AS (
    SELECT t.staff_id, sum(t.tip_cents)::BIGINT AS cents
      FROM transactions t, w
     WHERE t.device_sn IN (SELECT sn FROM devices WHERE org_id = p_org)
       AND t.payment_status = 'PAID' AND coalesce(t.tip_cents, 0) > 0
       AND t.created_at >= w.day_start AND t.created_at < w.day_end
     GROUP BY t.staff_id
    UNION ALL
    SELECT NULL, sum(p.tip_cents)::BIGINT
      FROM dine.payments p JOIN dine.checks c ON c.id = p.check_id, w
     WHERE c.org_id = p_org AND p.method = 'ONLINE' AND p.refund_status IS NULL AND p.tip_cents > 0
       AND p.created_at >= w.day_start AND p.created_at < w.day_end
    HAVING sum(p.tip_cents) > 0),
  shifts AS (
    SELECT s.*, coalesce(st.name, s.staff_name) AS name
      FROM office.shifts s LEFT JOIN staff st ON st.id = s.staff_id, w
     WHERE s.org_id = p_org AND s.clock_in_at < w.day_end AND coalesce(s.clock_out_at, now()) > w.day_start),
  worked AS (
    SELECT s.staff_id, max(s.name) AS name,
           sum(greatest(0, extract(epoch FROM least(coalesce(s.clock_out_at, now()), w.day_end) - greatest(s.clock_in_at, w.day_start)) / 60))::BIGINT AS minutes,
           bool_or(s.clock_out_at IS NULL) AS on_clock
      FROM shifts s, w GROUP BY s.staff_id),
  -- Tips on the sales of someone who didn't clock in still count (their own under OWN).
  people AS (
    SELECT staff_id, name, minutes, on_clock FROM worked
    UNION ALL
    SELECT t.staff_id, coalesce(st.name, '?'), 0, false
      FROM tips t LEFT JOIN staff st ON st.id = t.staff_id
     WHERE t.staff_id IS NOT NULL AND t.staff_id NOT IN (SELECT staff_id FROM worked WHERE staff_id IS NOT NULL)),
  pool AS (SELECT coalesce(sum(cents), 0)::BIGINT AS cents, coalesce(sum(cents) FILTER (WHERE staff_id IS NULL), 0)::BIGINT AS unassigned FROM tips),
  m AS (
    SELECT p.*, coalesce((SELECT sum(cents) FROM tips t WHERE t.staff_id = p.staff_id), 0)::BIGINT AS own,
           CASE (SELECT r FROM rule) WHEN 'POOL_HOURS' THEN p.minutes WHEN 'POOL_EQUAL' THEN (p.minutes > 0)::int ELSE 0 END::BIGINT AS weight
      FROM people p),
  tot AS (SELECT coalesce(sum(weight), 0)::BIGINT AS w FROM m),
  b AS (
    SELECT m.*, CASE WHEN tot.w > 0 THEN (pool.cents * m.weight) / tot.w ELSE 0 END AS base,
                CASE WHEN tot.w > 0 THEN (pool.cents * m.weight) % tot.w ELSE 0 END AS frac
      FROM m, tot, pool),
  -- Largest remainder: the cents left by rounding down go to the biggest fractions.
  r AS (
    SELECT b.*, row_number() OVER (ORDER BY frac DESC, name) AS rn,
           (SELECT cents FROM pool) - (SELECT coalesce(sum(base), 0) FROM b) AS rem
      FROM b),
  shares AS (
    SELECT r.*, CASE WHEN (SELECT r FROM rule) = 'OWN' THEN own
                     WHEN weight > 0 THEN base + CASE WHEN rn <= rem THEN 1 ELSE 0 END
                     ELSE 0 END AS share
      FROM r)
  SELECT jsonb_build_object(
    'day', p_day, 'rule', (SELECT r FROM rule), 'tips_cents', pool.cents,
    -- Under OWN, online tips (no staff) belong to nobody; under a pool rule they're in
    -- the pool -- unless nobody clocked in that day, then the whole pool is unshared.
    'unassigned_cents', CASE WHEN (SELECT r FROM rule) = 'OWN' THEN pool.unassigned
                             WHEN (SELECT w FROM tot) = 0 THEN pool.cents ELSE 0 END,
    'staff', coalesce((SELECT jsonb_agg(jsonb_build_object(
               'staff_id', staff_id, 'name', name, 'minutes', minutes, 'on_clock', on_clock,
               'own_tips_cents', own, 'share_cents', share) ORDER BY name) FROM shares), '[]'::jsonb),
    'shifts', coalesce((SELECT jsonb_agg(jsonb_build_object(
               'id', id, 'staff_id', staff_id, 'name', name,
               'clock_in_at', clock_in_at, 'clock_out_at', clock_out_at,
               'fixed_by_name', fixed_by_name, 'fixed_note', fixed_note) ORDER BY clock_in_at) FROM shifts), '[]'::jsonb))
  FROM pool
$$;

-- A shift's times for a fix: checks shared by the terminal and Cael.
CREATE OR REPLACE FUNCTION public.office_fix_shift_for(
  p_org UUID, p_id UUID, p_staff_id UUID, p_in TIMESTAMPTZ, p_out TIMESTAMPTZ, p_by_name TEXT, p_note TEXT
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, office AS $$
DECLARE
  v_shift office.shifts%ROWTYPE;
  v_name TEXT;
BEGIN
  IF p_in IS NULL OR p_in > now() + interval '5 minutes' OR (p_out IS NOT NULL AND (p_out < p_in OR p_out > now() + interval '5 minutes')) THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_time');
  END IF;
  IF p_out IS NOT NULL AND p_out - p_in > interval '24 hours' THEN
    RETURN jsonb_build_object('success', false, 'message', 'too_long');
  END IF;
  IF coalesce(trim(p_note), '') = '' THEN
    RETURN jsonb_build_object('success', false, 'message', 'note_required');
  END IF;

  IF p_id IS NULL THEN
    -- A shift nobody punched (forgot to clock in at all): needs both times.
    SELECT name INTO v_name FROM staff WHERE id = p_staff_id AND org_id = p_org;
    IF v_name IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_found'); END IF;
    IF p_out IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'bad_time'); END IF;
    INSERT INTO office.shifts (org_id, staff_id, staff_name, clock_in_at, clock_out_at, fixed_by_name, fixed_note, fixed_at)
    VALUES (p_org, p_staff_id, v_name, p_in, p_out, nullif(trim(p_by_name), ''), trim(p_note), now())
    RETURNING * INTO v_shift;
    RETURN jsonb_build_object('success', true, 'message', 'added', 'id', v_shift.id);
  END IF;

  SELECT * INTO v_shift FROM office.shifts WHERE id = p_id AND org_id = p_org FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'not_found'); END IF;
  UPDATE office.shifts
     SET clock_in_at = p_in, clock_out_at = p_out,
         fixed_by_name = nullif(trim(p_by_name), ''), fixed_note = trim(p_note), fixed_at = now()
   WHERE id = p_id;
  RETURN jsonb_build_object('success', true, 'message', 'fixed', 'id', p_id);
EXCEPTION WHEN unique_violation THEN
  RETURN jsonb_build_object('success', false, 'message', 'already_on_clock');
END;
$$;

-- ---------------------------------------------------------------- terminals

-- Clock in [p_staff_id] at [p_at] (default now). [p_ref] = the terminal's punch id:
-- the same ref again is the same punch ('ok'). 'already_in' (another shift is open),
-- 'not_found' (not this store's active staff), 'bad_time' (future, or over 7 days ago).
CREATE OR REPLACE FUNCTION public.office_clock_in(p_staff_id UUID, p_ref TEXT, p_at TIMESTAMPTZ DEFAULT NULL, p_device_sn TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, office AS $$
DECLARE
  v_org UUID := office_device_org();
  v_at TIMESTAMPTZ := coalesce(p_at, now());
  v_name TEXT;
  v_id UUID;
BEGIN
  SELECT id INTO v_id FROM office.shifts WHERE in_ref = p_ref AND org_id = v_org;
  IF v_id IS NOT NULL THEN RETURN jsonb_build_object('success', true, 'message', 'ok', 'id', v_id); END IF;

  SELECT name INTO v_name FROM staff WHERE id = p_staff_id AND org_id = v_org AND is_active;
  IF v_org IS NULL OR v_name IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_found'); END IF;
  IF v_at > now() + interval '5 minutes' OR v_at < now() - interval '7 days' THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_time');
  END IF;

  SELECT id INTO v_id FROM office.shifts WHERE staff_id = p_staff_id AND clock_out_at IS NULL;
  IF v_id IS NOT NULL THEN RETURN jsonb_build_object('success', false, 'message', 'already_in', 'id', v_id); END IF;

  INSERT INTO office.shifts (org_id, staff_id, staff_name, clock_in_at, in_device_sn, in_ref)
  VALUES (v_org, p_staff_id, v_name, v_at, nullif(trim(p_device_sn), ''), p_ref)
  RETURNING id INTO v_id;
  RETURN jsonb_build_object('success', true, 'message', 'ok', 'id', v_id);
EXCEPTION WHEN unique_violation THEN
  RETURN jsonb_build_object('success', false, 'message', 'already_in');
END;
$$;

-- Clock out. 'ok' (also a replay of the same ref), 'not_in' (no open shift),
-- 'bad_time' (before the clock-in, or in the future).
CREATE OR REPLACE FUNCTION public.office_clock_out(p_staff_id UUID, p_ref TEXT, p_at TIMESTAMPTZ DEFAULT NULL, p_device_sn TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, office AS $$
DECLARE
  v_org UUID := office_device_org();
  v_at TIMESTAMPTZ := coalesce(p_at, now());
  v_shift office.shifts%ROWTYPE;
BEGIN
  SELECT * INTO v_shift FROM office.shifts WHERE out_ref = p_ref AND org_id = v_org;
  IF FOUND THEN RETURN jsonb_build_object('success', true, 'message', 'ok', 'id', v_shift.id); END IF;

  SELECT * INTO v_shift FROM office.shifts
   WHERE staff_id = p_staff_id AND org_id = v_org AND clock_out_at IS NULL FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'not_in'); END IF;
  IF v_at > now() + interval '5 minutes' OR v_at < v_shift.clock_in_at THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_time');
  END IF;

  UPDATE office.shifts SET clock_out_at = v_at, out_device_sn = nullif(trim(p_device_sn), ''), out_ref = p_ref
   WHERE id = v_shift.id;
  RETURN jsonb_build_object('success', true, 'message', 'ok', 'id', v_shift.id);
END;
$$;

-- Who is on the clock now (the store's open shifts).
CREATE OR REPLACE FUNCTION public.office_on_clock()
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, office AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object('id', s.id, 'staff_id', s.staff_id, 'name', s.staff_name, 'clock_in_at', s.clock_in_at)
                            ORDER BY s.clock_in_at), '[]'::jsonb)
    FROM office.shifts s WHERE s.org_id = office_device_org() AND s.clock_out_at IS NULL
$$;

-- The day's hours and tip shares (the Day report).
CREATE OR REPLACE FUNCTION public.office_tips(p_day DATE)
RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT CASE WHEN office_device_org() IS NULL THEN NULL ELSE office_tips_for(office_device_org(), p_day) END
$$;

-- A manager's fix (the app asks for the manager's PIN first): an open or wrong shift
-- ([p_id]), or a shift nobody punched ([p_id] NULL, [p_staff_id], both times). Reason required.
CREATE OR REPLACE FUNCTION public.office_fix_shift(
  p_id UUID, p_clock_in_at TIMESTAMPTZ, p_note TEXT,
  p_clock_out_at TIMESTAMPTZ DEFAULT NULL, p_staff_id UUID DEFAULT NULL, p_by_name TEXT DEFAULT NULL
) RETURNS JSONB
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  SELECT CASE WHEN office_device_org() IS NULL THEN jsonb_build_object('success', false, 'message', 'not_found')
              ELSE office_fix_shift_for(office_device_org(), p_id, p_staff_id, p_clock_in_at, p_clock_out_at, p_by_name, p_note) END
$$;

CREATE OR REPLACE FUNCTION public.office_set_tip_split(p_rule TEXT, p_by_name TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, office AS $$
DECLARE v_org UUID := office_device_org();
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_found'); END IF;
  IF p_rule NOT IN ('OWN', 'POOL_HOURS', 'POOL_EQUAL') THEN RETURN jsonb_build_object('success', false, 'message', 'bad_rule'); END IF;
  INSERT INTO office.settings (org_id, tip_split, updated_by) VALUES (v_org, p_rule, nullif(trim(p_by_name), ''))
  ON CONFLICT (org_id) DO UPDATE SET tip_split = excluded.tip_split, updated_by = excluded.updated_by, updated_at = now();
  RETURN jsonb_build_object('success', true, 'message', 'ok');
END;
$$;

-- ---------------------------------------------------------------- Cael

CREATE OR REPLACE FUNCTION public.cmp_office_can(p_org UUID, p_write BOOLEAN) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT is_sys_admin() OR (p_org IN (SELECT member_org_ids()) AND (NOT p_write OR has_permission('staff.manage')))
$$;

-- Days [p_from, p_to] (at most 31): each day's hours and shares, for the timesheet.
CREATE OR REPLACE FUNCTION public.cmp_office_timesheet(p_org UUID, p_from DATE, p_to DATE)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NOT cmp_office_can(p_org, false) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  IF p_to < p_from OR p_to - p_from > 31 THEN RAISE EXCEPTION 'bad range' USING ERRCODE = '22023'; END IF;
  RETURN (SELECT jsonb_agg(office_tips_for(p_org, d::date) ORDER BY d) FROM generate_series(p_from, p_to, interval '1 day') d);
END;
$$;

CREATE OR REPLACE FUNCTION public.cmp_office_fix_shift(
  p_org UUID, p_id UUID, p_clock_in_at TIMESTAMPTZ, p_note TEXT,
  p_clock_out_at TIMESTAMPTZ DEFAULT NULL, p_staff_id UUID DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NOT cmp_office_can(p_org, true) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN office_fix_shift_for(p_org, p_id, p_staff_id, p_clock_in_at, p_clock_out_at,
                              (SELECT coalesce(full_name, email) FROM profiles WHERE id = auth.uid()), p_note);
END;
$$;

CREATE OR REPLACE FUNCTION public.cmp_office_set_tip_split(p_org UUID, p_rule TEXT)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, office AS $$
BEGIN
  IF NOT cmp_office_can(p_org, true) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  IF p_rule NOT IN ('OWN', 'POOL_HOURS', 'POOL_EQUAL') THEN RETURN jsonb_build_object('success', false, 'message', 'bad_rule'); END IF;
  INSERT INTO office.settings (org_id, tip_split, updated_by)
  VALUES (p_org, p_rule, (SELECT coalesce(full_name, email) FROM profiles WHERE id = auth.uid()))
  ON CONFLICT (org_id) DO UPDATE SET tip_split = excluded.tip_split, updated_by = excluded.updated_by, updated_at = now();
  RETURN jsonb_build_object('success', true, 'message', 'ok');
END;
$$;

-- ---------------------------------------------------------------- grants

REVOKE ALL ON FUNCTION public.office_day_window(DATE) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.office_tips_for(UUID, DATE) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.office_fix_shift_for(UUID, UUID, UUID, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.cmp_office_can(UUID, BOOLEAN) FROM public, anon, authenticated;

REVOKE ALL ON FUNCTION public.office_clock_in(UUID, TEXT, TIMESTAMPTZ, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.office_clock_out(UUID, TEXT, TIMESTAMPTZ, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.office_on_clock() FROM public, anon;
REVOKE ALL ON FUNCTION public.office_tips(DATE) FROM public, anon;
REVOKE ALL ON FUNCTION public.office_fix_shift(UUID, TIMESTAMPTZ, TEXT, TIMESTAMPTZ, UUID, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.office_set_tip_split(TEXT, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_office_timesheet(UUID, DATE, DATE) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_office_fix_shift(UUID, UUID, TIMESTAMPTZ, TEXT, TIMESTAMPTZ, UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_office_set_tip_split(UUID, TEXT) FROM public, anon;

GRANT EXECUTE ON FUNCTION public.office_clock_in(UUID, TEXT, TIMESTAMPTZ, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_clock_out(UUID, TEXT, TIMESTAMPTZ, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_on_clock() TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_tips(DATE) TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_fix_shift(UUID, TIMESTAMPTZ, TEXT, TIMESTAMPTZ, UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.office_set_tip_split(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_office_timesheet(UUID, DATE, DATE) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_office_fix_shift(UUID, UUID, TIMESTAMPTZ, TEXT, TIMESTAMPTZ, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_office_set_tip_split(UUID, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
