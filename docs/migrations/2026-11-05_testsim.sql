-- 2026-11-05: the shared test simulator (owner: "a public test page in Cael; any test can be
-- driven from it from now on"). A terminal set to "Cael test simulator" sends what it would send
-- to a real machine here, and a sys admin answers it on Cael's Test simulator page.
-- First kind: FLEX_DISPENSE -- Aegis Flex's partner controller (docs/flex_controller_api.md):
-- READY / NOT READY per terminal, and each dispense answered DISPENSED / FAILED.
-- Schema testsim is not exposed through the API (docs/database_design.md §3.2): terminals and
-- Cael only go through the public.testsim_* functions below. Real payments still happen on the
-- terminal; only the machine is simulated. New kinds: extend the CHECK and the Cael page.

BEGIN;

CREATE SCHEMA IF NOT EXISTS testsim;
REVOKE ALL ON SCHEMA testsim FROM PUBLIC, anon, authenticated;

-- The simulated machine behind each terminal using it.
CREATE TABLE IF NOT EXISTS testsim.controllers (
  device_sn  TEXT PRIMARY KEY REFERENCES public.devices(sn) ON DELETE CASCADE,
  ready      BOOLEAN NOT NULL DEFAULT true,
  message    TEXT,
  last_seen  TIMESTAMPTZ,                 -- the terminal's last status check
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One request from a terminal, waiting for a person's answer.
CREATE TABLE IF NOT EXISTS testsim.requests (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_sn   TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE CASCADE,
  kind        TEXT NOT NULL CHECK (kind IN ('FLEX_DISPENSE')),
  ref         TEXT NOT NULL,              -- the terminal's own id, e.g. the order_id
  payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
  deadline    TIMESTAMPTZ NOT NULL,       -- unanswered by then = FAILED
  answer      JSONB,
  answered_at TIMESTAMPTZ,
  answered_by UUID,                       -- auth user; null = the deadline
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (device_sn, kind, ref)
);
CREATE INDEX IF NOT EXISTS testsim_requests_created_idx ON testsim.requests (created_at DESC);

-- ---- terminal side (the device's anonymous session, linked in device_auth_map) ----

CREATE OR REPLACE FUNCTION public.testsim_device_sn() RETURNS TEXT
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT device_sn FROM device_auth_map WHERE auth_user_id = auth.uid()
$$;

-- Is this terminal's simulated machine ready? Also marks the terminal as using the simulator.
CREATE OR REPLACE FUNCTION public.testsim_status() RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, testsim AS $$
DECLARE v_sn TEXT := testsim_device_sn(); c testsim.controllers;
BEGIN
  IF v_sn IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_linked'); END IF;
  INSERT INTO testsim.controllers (device_sn, last_seen) VALUES (v_sn, now())
  ON CONFLICT (device_sn) DO UPDATE SET last_seen = now()
  RETURNING * INTO c;
  RETURN jsonb_build_object('success', true, 'ready', c.ready, 'message', c.message);
END $$;

-- A request from this terminal (again with the same ref = the same request).
CREATE OR REPLACE FUNCTION public.testsim_submit(p_kind TEXT, p_ref TEXT, p_payload JSONB, p_deadline_ms BIGINT) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, testsim AS $$
DECLARE v_sn TEXT := testsim_device_sn();
BEGIN
  IF v_sn IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_linked'); END IF;
  IF p_kind IS DISTINCT FROM 'FLEX_DISPENSE' OR coalesce(p_ref, '') = '' THEN
    RETURN jsonb_build_object('success', false, 'message', 'invalid_request');
  END IF;
  INSERT INTO testsim.requests (device_sn, kind, ref, payload, deadline)
  VALUES (v_sn, p_kind, p_ref, coalesce(p_payload, '{}'::jsonb),
          least(to_timestamp(p_deadline_ms / 1000.0), now() + interval '10 minutes'))
  ON CONFLICT (device_sn, kind, ref) DO NOTHING;
  RETURN jsonb_build_object('success', true);
END $$;

-- The answer to this terminal's request, once there is one; past the deadline it's FAILED.
CREATE OR REPLACE FUNCTION public.testsim_answer_of(p_kind TEXT, p_ref TEXT) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, testsim AS $$
DECLARE v_sn TEXT := testsim_device_sn(); r testsim.requests;
BEGIN
  IF v_sn IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_linked'); END IF;
  UPDATE testsim.requests SET answer = jsonb_build_object('result', 'FAILED', 'reason', 'not answered by the deadline'),
         answered_at = now()
   WHERE device_sn = v_sn AND kind = p_kind AND ref = p_ref AND answer IS NULL AND deadline < now();
  SELECT * INTO r FROM testsim.requests WHERE device_sn = v_sn AND kind = p_kind AND ref = p_ref;
  IF r.id IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_found'); END IF;
  RETURN jsonb_build_object('success', true, 'answered', r.answer IS NOT NULL, 'answer', r.answer);
END $$;

-- ---- Cael side (sys admins) ----

-- Terminals using the simulator and the recent requests ([p_hours] back), newest first.
CREATE OR REPLACE FUNCTION public.testsim_console(p_hours INT DEFAULT 12) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, testsim AS $$
BEGIN
  IF NOT is_sys_admin() THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN jsonb_build_object(
    'now', now(),
    'controllers', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'device_sn', c.device_sn, 'alias', d.alias, 'org_name', o.name,
               'ready', c.ready, 'message', c.message, 'last_seen', c.last_seen)
             ORDER BY c.last_seen DESC NULLS LAST)
      FROM testsim.controllers c JOIN devices d ON d.sn = c.device_sn LEFT JOIN organizations o ON o.id = d.org_id
    ), '[]'::jsonb),
    'requests', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'id', r.id, 'device_sn', r.device_sn, 'alias', d.alias, 'kind', r.kind, 'ref', r.ref,
               'payload', r.payload, 'deadline', r.deadline, 'answer', r.answer,
               'answered_at', r.answered_at, 'created_at', r.created_at)
             ORDER BY r.created_at DESC)
      FROM (SELECT * FROM testsim.requests WHERE created_at > now() - make_interval(hours => p_hours)
            ORDER BY created_at DESC LIMIT 100) r
      JOIN devices d ON d.sn = r.device_sn
    ), '[]'::jsonb));
END $$;

-- Answer a waiting request: FLEX_DISPENSE takes {"result": "DISPENSED" | "FAILED", "reason"?}.
CREATE OR REPLACE FUNCTION public.testsim_answer(p_id UUID, p_answer JSONB) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, testsim AS $$
DECLARE v_n INT;
BEGIN
  IF NOT is_sys_admin() THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  IF p_answer ->> 'result' NOT IN ('DISPENSED', 'FAILED') THEN
    RETURN jsonb_build_object('success', false, 'message', 'invalid_answer');
  END IF;
  UPDATE testsim.requests SET answer = p_answer, answered_at = now(), answered_by = auth.uid()
   WHERE id = p_id AND answer IS NULL AND deadline >= now();
  GET DIAGNOSTICS v_n = ROW_COUNT;
  RETURN jsonb_build_object('success', v_n = 1, 'message', CASE WHEN v_n = 1 THEN NULL ELSE 'already_answered_or_expired' END);
END $$;

-- READY / NOT READY for a terminal's simulated machine.
CREATE OR REPLACE FUNCTION public.testsim_set_ready(p_sn TEXT, p_ready BOOLEAN, p_message TEXT DEFAULT NULL) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, testsim AS $$
BEGIN
  IF NOT is_sys_admin() THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  INSERT INTO testsim.controllers (device_sn, ready, message, updated_at) VALUES (p_sn, p_ready, p_message, now())
  ON CONFLICT (device_sn) DO UPDATE SET ready = p_ready, message = p_message, updated_at = now();
  RETURN jsonb_build_object('success', true);
END $$;

REVOKE ALL ON FUNCTION public.testsim_device_sn(), public.testsim_status(),
  public.testsim_submit(TEXT, TEXT, JSONB, BIGINT), public.testsim_answer_of(TEXT, TEXT),
  public.testsim_console(INT), public.testsim_answer(UUID, JSONB), public.testsim_set_ready(TEXT, BOOLEAN, TEXT)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.testsim_status(),
  public.testsim_submit(TEXT, TEXT, JSONB, BIGINT), public.testsim_answer_of(TEXT, TEXT),
  public.testsim_console(INT), public.testsim_answer(UUID, JSONB), public.testsim_set_ready(TEXT, BOOLEAN, TEXT)
  TO authenticated;

COMMIT;
