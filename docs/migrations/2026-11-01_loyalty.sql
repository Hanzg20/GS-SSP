-- 2026-11-01: retail loyalty points (Iris / Ourea, Cael).
--
-- A member is a phone number in a store (member.members, unique per store); the
-- cashier types it at checkout. Points are earned on what the customer paid
-- before tax and tips (transactions.subtotal_cents, already after discounts):
-- points_per_dollar per whole dollar. A reward = reward_points points for
-- reward_cents off, taken as an order discount (before tax, like any store
-- discount). Nothing happens until the store turns loyalty on (Cael).
--
-- Points move only through member_record_sale (after the sale is PAID; the
-- server reads the amount from the sale itself, so a terminal can't award what
-- wasn't paid), Cael adjustments, and a trigger that undoes a sale's points
-- when it is voided / refunded whole (earned taken back, redeemed given back).
-- Every move is a row in member.points_ledger, keyed by the sale so a replayed
-- call never counts twice. Partial refunds keep the points (known gap).
--
-- Separate from vip_cards (stored value, wash / laundry).

CREATE SCHEMA IF NOT EXISTS member;
COMMENT ON SCHEMA member IS 'Store members: retail loyalty points; reached only through public member_* / cmp_member_* RPCs';
REVOKE ALL ON SCHEMA member FROM public, anon, authenticated;

CREATE TABLE IF NOT EXISTS member.settings (
  org_id            UUID PRIMARY KEY REFERENCES public.organizations(id) ON DELETE CASCADE,
  enabled           BOOLEAN NOT NULL DEFAULT false,
  points_per_dollar INTEGER NOT NULL DEFAULT 1 CHECK (points_per_dollar BETWEEN 1 AND 100),
  reward_points     INTEGER NOT NULL DEFAULT 100 CHECK (reward_points BETWEEN 1 AND 1000000),
  reward_cents      INTEGER NOT NULL DEFAULT 500 CHECK (reward_cents BETWEEN 1 AND 100000),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by        TEXT
);
COMMENT ON TABLE member.settings IS 'A store''s loyalty rules (earn rate, reward); written by Cael';

CREATE TABLE IF NOT EXISTS member.members (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id          UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  phone           TEXT NOT NULL CHECK (phone ~ '^\d{7,15}$'),
  name            TEXT,
  points          INTEGER NOT NULL DEFAULT 0,
  lifetime_points INTEGER NOT NULL DEFAULT 0,
  visits          INTEGER NOT NULL DEFAULT 0,
  last_visit_at   TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT members_org_phone_key UNIQUE (org_id, phone)
);
COMMENT ON TABLE member.members IS 'Loyalty members of a store, by phone number; written by Iris / Ourea (join, sales) and Cael';

CREATE TABLE IF NOT EXISTS member.points_ledger (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  member_id   UUID NOT NULL REFERENCES member.members(id) ON DELETE CASCADE,
  reason      TEXT NOT NULL CHECK (reason IN ('EARN', 'REDEEM', 'REVERSE', 'RESTORE', 'ADJUST')),
  delta       INTEGER NOT NULL,
  points_after INTEGER NOT NULL,
  ecr_ref_num TEXT,
  note        TEXT,
  staff_name  TEXT,
  device_sn   TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS points_ledger_ref_key ON member.points_ledger (member_id, reason, ecr_ref_num) WHERE ecr_ref_num IS NOT NULL;
CREATE INDEX IF NOT EXISTS points_ledger_member_idx ON member.points_ledger (member_id, created_at);
COMMENT ON TABLE member.points_ledger IS 'Every points change of a member and why; written by member_* / cmp_member_* RPCs and the refund trigger';

REVOKE ALL ON ALL TABLES IN SCHEMA member FROM public, anon, authenticated;

ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS member_id UUID REFERENCES member.members(id) ON DELETE SET NULL;

-- ---------------------------------------------------------------- core (not callable from the API)

-- Digits only; a North American number with its leading 1 is the same member as without.
CREATE OR REPLACE FUNCTION public.member_phone(p TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN length(d) = 11 AND left(d, 1) = '1' THEN substr(d, 2) ELSE d END
    FROM (SELECT regexp_replace(coalesce(p, ''), '\D', '', 'g') AS d) x
$$;

CREATE OR REPLACE FUNCTION public.member_settings_for(p_org UUID) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, member AS $$
  SELECT coalesce(
    (SELECT jsonb_build_object('enabled', enabled, 'points_per_dollar', points_per_dollar,
                               'reward_points', reward_points, 'reward_cents', reward_cents)
       FROM member.settings WHERE org_id = p_org),
    jsonb_build_object('enabled', false, 'points_per_dollar', 1, 'reward_points', 100, 'reward_cents', 500))
$$;

CREATE OR REPLACE FUNCTION public.member_json(m member.members) RETURNS JSONB
LANGUAGE sql STABLE AS $$
  SELECT jsonb_build_object('id', m.id, 'phone', m.phone, 'name', m.name, 'points', m.points,
                            'lifetime_points', m.lifetime_points, 'visits', m.visits, 'last_visit_at', m.last_visit_at)
$$;

-- Moves a member's points; NULL when [p_ref] was already applied for this reason.
CREATE OR REPLACE FUNCTION public.member_points_apply(
  p_member UUID, p_reason TEXT, p_delta INT, p_ref TEXT, p_note TEXT, p_staff TEXT, p_device TEXT
) RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, member AS $$
DECLARE
  v_m member.members%ROWTYPE;
  v_id UUID;
BEGIN
  SELECT * INTO v_m FROM member.members WHERE id = p_member FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;
  INSERT INTO member.points_ledger (org_id, member_id, reason, delta, points_after, ecr_ref_num, note, staff_name, device_sn)
  VALUES (v_m.org_id, p_member, p_reason, p_delta, v_m.points + p_delta, p_ref, nullif(trim(p_note), ''), nullif(trim(p_staff), ''), nullif(trim(p_device), ''))
  ON CONFLICT (member_id, reason, ecr_ref_num) WHERE ecr_ref_num IS NOT NULL DO NOTHING
  RETURNING id INTO v_id;
  IF v_id IS NULL THEN RETURN NULL; END IF;
  UPDATE member.members
     SET points = points + p_delta,
         lifetime_points = lifetime_points + CASE WHEN p_reason = 'EARN' THEN p_delta WHEN p_reason = 'REVERSE' THEN p_delta ELSE 0 END
   WHERE id = p_member;
  RETURN v_m.points + p_delta;
END;
$$;

-- A whole void / refund of a member's sale: its earned points back off, its redeemed points back on.
CREATE OR REPLACE FUNCTION public.transactions_member_points() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, member AS $$
DECLARE l member.points_ledger%ROWTYPE;
BEGIN
  BEGIN
    IF NEW.member_id IS NOT NULL AND OLD.payment_status = 'PAID' AND NEW.payment_status IN ('VOIDED', 'REFUNDED') THEN
      FOR l IN SELECT * FROM member.points_ledger WHERE member_id = NEW.member_id AND ecr_ref_num = NEW.ecr_ref_num AND reason IN ('EARN', 'REDEEM') LOOP
        PERFORM member_points_apply(NEW.member_id, CASE l.reason WHEN 'EARN' THEN 'REVERSE' ELSE 'RESTORE' END,
                                    -l.delta, NEW.ecr_ref_num, 'sale ' || lower(NEW.payment_status), NULL, NEW.device_sn);
      END LOOP;
    END IF;
  EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'member points reversal failed for %: %', NEW.ecr_ref_num, SQLERRM;
  END;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS transactions_member_points ON public.transactions;
CREATE TRIGGER transactions_member_points AFTER UPDATE OF payment_status ON public.transactions
  FOR EACH ROW EXECUTE FUNCTION public.transactions_member_points();

-- ---------------------------------------------------------------- terminals

CREATE OR REPLACE FUNCTION public.member_settings() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT member_settings_for(office_device_org())
$$;

-- {found, member} by phone.
CREATE OR REPLACE FUNCTION public.member_lookup(p_phone TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, member AS $$
  SELECT coalesce((SELECT jsonb_build_object('found', true, 'member', member_json(m))
                     FROM member.members m WHERE m.org_id = office_device_org() AND m.phone = member_phone(p_phone)),
                  jsonb_build_object('found', false))
$$;

-- A new member (or the existing one with that phone): {success, message, member}.
CREATE OR REPLACE FUNCTION public.member_join(p_phone TEXT, p_name TEXT DEFAULT NULL) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, member AS $$
DECLARE
  v_org UUID := office_device_org();
  v_phone TEXT := member_phone(p_phone);
  v_m member.members%ROWTYPE;
BEGIN
  IF v_org IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_found'); END IF;
  IF NOT (member_settings_for(v_org)->>'enabled')::BOOLEAN THEN RETURN jsonb_build_object('success', false, 'message', 'loyalty_off'); END IF;
  IF v_phone !~ '^\d{7,15}$' THEN RETURN jsonb_build_object('success', false, 'message', 'bad_phone'); END IF;
  INSERT INTO member.members (org_id, phone, name) VALUES (v_org, v_phone, nullif(trim(p_name), ''))
  ON CONFLICT (org_id, phone) DO UPDATE SET name = coalesce(member.members.name, excluded.name)
  RETURNING * INTO v_m;
  RETURN jsonb_build_object('success', true, 'message', 'ok', 'member', member_json(v_m));
END;
$$;

-- After a sale is PAID: earn on it, and take the points used for its reward.
-- 'ok' / 'already' (this sale was recorded) / 'not_paid' (not PAID yet -- retry) /
-- 'not_found' / 'loyalty_off'. A reward bigger than the points left takes what is left.
CREATE OR REPLACE FUNCTION public.member_record_sale(p_member_id UUID, p_ecr_ref_num TEXT, p_redeem_points INT DEFAULT 0, p_staff_name TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, member AS $$
DECLARE
  v_org UUID := office_device_org();
  v_set JSONB;
  v_m member.members%ROWTYPE;
  v_t transactions%ROWTYPE;
  v_earn INT;
  v_redeem INT;
  v_after INT;
BEGIN
  SELECT * INTO v_m FROM member.members WHERE id = p_member_id AND org_id = v_org;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'not_found'); END IF;
  SELECT * INTO v_t FROM transactions
   WHERE ecr_ref_num = p_ecr_ref_num AND device_sn IN (SELECT device_sn FROM device_auth_map WHERE auth_user_id = auth.uid());
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'not_paid'); END IF;
  IF EXISTS (SELECT 1 FROM member.points_ledger WHERE member_id = p_member_id AND ecr_ref_num = p_ecr_ref_num AND reason = 'EARN') THEN
    RETURN jsonb_build_object('success', true, 'message', 'already', 'member', member_json(v_m));
  END IF;
  IF v_t.payment_status <> 'PAID' THEN RETURN jsonb_build_object('success', false, 'message', 'not_paid'); END IF;
  v_set := member_settings_for(v_org);
  IF NOT (v_set->>'enabled')::BOOLEAN THEN RETURN jsonb_build_object('success', false, 'message', 'loyalty_off'); END IF;

  UPDATE transactions SET member_id = p_member_id WHERE ecr_ref_num = p_ecr_ref_num;
  v_redeem := least(greatest(coalesce(p_redeem_points, 0), 0), greatest(v_m.points, 0));
  IF v_redeem > 0 THEN
    PERFORM member_points_apply(p_member_id, 'REDEEM', -v_redeem, p_ecr_ref_num, NULL, p_staff_name, v_t.device_sn);
  END IF;
  v_earn := (greatest(coalesce(v_t.subtotal_cents, 0) - coalesce(v_t.refunded_cents, 0), 0) / 100) * (v_set->>'points_per_dollar')::INT;
  -- Always an EARN row (0 too): it marks the sale as recorded.
  v_after := member_points_apply(p_member_id, 'EARN', v_earn, p_ecr_ref_num, NULL, p_staff_name, v_t.device_sn);
  UPDATE member.members SET visits = visits + 1, last_visit_at = now() WHERE id = p_member_id RETURNING * INTO v_m;
  RETURN jsonb_build_object('success', true, 'message', 'ok', 'earned', v_earn, 'redeemed', v_redeem, 'member', member_json(v_m));
END;
$$;

-- ---------------------------------------------------------------- Cael (vip.manage to write)

CREATE OR REPLACE FUNCTION public.cmp_member_can(p_org UUID, p_write BOOLEAN) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT is_sys_admin() OR (p_org IN (SELECT member_org_ids()) AND (NOT p_write OR has_permission('vip.manage')))
$$;

CREATE OR REPLACE FUNCTION public.cmp_member_settings(p_org UUID) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, member AS $$
BEGIN
  IF NOT cmp_member_can(p_org, false) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN member_settings_for(p_org) || jsonb_build_object(
    'members', (SELECT count(*) FROM member.members WHERE org_id = p_org),
    'points_out', (SELECT coalesce(sum(points), 0) FROM member.members WHERE org_id = p_org));
END;
$$;

CREATE OR REPLACE FUNCTION public.cmp_member_save_settings(p_org UUID, p_enabled BOOLEAN, p_points_per_dollar INT, p_reward_points INT, p_reward_cents INT)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, member AS $$
BEGIN
  IF NOT cmp_member_can(p_org, true) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  INSERT INTO member.settings (org_id, enabled, points_per_dollar, reward_points, reward_cents, updated_by)
  VALUES (p_org, p_enabled, p_points_per_dollar, p_reward_points, p_reward_cents, (SELECT coalesce(full_name, email) FROM profiles WHERE id = auth.uid()))
  ON CONFLICT (org_id) DO UPDATE SET enabled = excluded.enabled, points_per_dollar = excluded.points_per_dollar,
    reward_points = excluded.reward_points, reward_cents = excluded.reward_cents, updated_by = excluded.updated_by, updated_at = now();
  RETURN jsonb_build_object('success', true, 'message', 'ok');
EXCEPTION WHEN check_violation THEN
  RETURN jsonb_build_object('success', false, 'message', 'bad_value');
END;
$$;

CREATE OR REPLACE FUNCTION public.cmp_members(p_org UUID, p_query TEXT DEFAULT NULL, p_limit INT DEFAULT 200) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, member AS $$
BEGIN
  IF NOT cmp_member_can(p_org, false) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN (SELECT coalesce(jsonb_agg(member_json(m) || jsonb_build_object('created_at', m.created_at) ORDER BY m.last_visit_at DESC NULLS LAST, m.created_at DESC), '[]'::jsonb)
            FROM (SELECT * FROM member.members m
                   WHERE m.org_id = p_org
                     AND (coalesce(trim(p_query), '') = ''
                          OR m.phone LIKE '%' || member_phone(p_query) || '%' AND member_phone(p_query) <> ''
                          OR m.name ILIKE '%' || trim(p_query) || '%')
                   ORDER BY m.last_visit_at DESC NULLS LAST, m.created_at DESC
                   LIMIT least(greatest(coalesce(p_limit, 200), 1), 1000)) m);
END;
$$;

CREATE OR REPLACE FUNCTION public.cmp_member_ledger(p_org UUID, p_member_id UUID) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, member AS $$
BEGIN
  IF NOT cmp_member_can(p_org, false) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN (SELECT coalesce(jsonb_agg(jsonb_build_object('id', l.id, 'reason', l.reason, 'delta', l.delta, 'points_after', l.points_after,
            'ecr_ref_num', l.ecr_ref_num, 'note', l.note, 'staff_name', l.staff_name, 'created_at', l.created_at) ORDER BY l.created_at DESC), '[]'::jsonb)
            FROM member.points_ledger l WHERE l.org_id = p_org AND l.member_id = p_member_id);
END;
$$;

-- +/- points with a reason (a goodwill gift, a correction); also rename.
CREATE OR REPLACE FUNCTION public.cmp_member_adjust(p_org UUID, p_member_id UUID, p_delta INT, p_note TEXT, p_name TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, member AS $$
DECLARE v_after INT;
BEGIN
  IF NOT cmp_member_can(p_org, true) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  IF NOT EXISTS (SELECT 1 FROM member.members WHERE id = p_member_id AND org_id = p_org) THEN
    RETURN jsonb_build_object('success', false, 'message', 'not_found');
  END IF;
  IF p_name IS NOT NULL THEN UPDATE member.members SET name = nullif(trim(p_name), '') WHERE id = p_member_id; END IF;
  IF coalesce(p_delta, 0) <> 0 THEN
    IF coalesce(trim(p_note), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'note_required'); END IF;
    v_after := member_points_apply(p_member_id, 'ADJUST', p_delta, NULL, p_note, (SELECT coalesce(full_name, email) FROM profiles WHERE id = auth.uid()), 'Cael');
  END IF;
  RETURN jsonb_build_object('success', true, 'message', 'ok', 'points', (SELECT points FROM member.members WHERE id = p_member_id));
END;
$$;

-- ---------------------------------------------------------------- grants

REVOKE ALL ON FUNCTION public.member_phone(TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.member_phone(TEXT) TO authenticated;
REVOKE ALL ON FUNCTION public.member_settings_for(UUID) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.member_json(member.members) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.member_points_apply(UUID, TEXT, INT, TEXT, TEXT, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.transactions_member_points() FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.cmp_member_can(UUID, BOOLEAN) FROM public, anon, authenticated;

REVOKE ALL ON FUNCTION public.member_settings() FROM public, anon;
REVOKE ALL ON FUNCTION public.member_lookup(TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.member_join(TEXT, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.member_record_sale(UUID, TEXT, INT, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_member_settings(UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_member_save_settings(UUID, BOOLEAN, INT, INT, INT) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_members(UUID, TEXT, INT) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_member_ledger(UUID, UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_member_adjust(UUID, UUID, INT, TEXT, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.member_settings() TO authenticated;
GRANT EXECUTE ON FUNCTION public.member_lookup(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.member_join(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.member_record_sale(UUID, TEXT, INT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_member_settings(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_member_save_settings(UUID, BOOLEAN, INT, INT, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_members(UUID, TEXT, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_member_ledger(UUID, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_member_adjust(UUID, UUID, INT, TEXT, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
