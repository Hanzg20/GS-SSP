-- VIP cards sold and topped up at the terminal (2026-10-06).
--
-- A customer picks a load plan on the terminal's VIP page, pays by card
-- (the normal card-sale flow), and the terminal then calls device_vip_load()
-- with that sale's ecr_ref_num. The server -- never the terminal -- checks
-- the sale and credits the card:
--   * the sale exists, belongs to the calling terminal, is PAID and is a
--     VIP_LOAD sale;
--   * the plan belongs to the terminal's merchant and is active;
--   * the paid amount matches the plan (a technician TEST_ sale may charge
--     less: it credits only what was paid, no bonus);
--   * each sale loads a card at most once (ledger source_ref is unique).
-- New cards get a generated card_uid and 6-character member code; top-ups
-- go to an existing card of the same merchant. Balances never expire.
--
-- Business rules agreed 2026-10-06: plans configured per merchant in CMP;
-- phone number optional; no expiry; test loads credit only the amount paid.

-- 1) Load plans per merchant ------------------------------------------------
CREATE TABLE IF NOT EXISTS public.vip_load_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
    amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
    bonus_cents INTEGER NOT NULL DEFAULT 0 CHECK (bonus_cents >= 0),
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_vip_load_plans_org ON public.vip_load_plans(org_id, sort_order);

ALTER TABLE public.vip_load_plans ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Org members can view vip load plans" ON public.vip_load_plans;
CREATE POLICY "Org members can view vip load plans" ON public.vip_load_plans
FOR SELECT TO authenticated
USING (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids()));
DROP POLICY IF EXISTS "VIP managers can manage vip load plans" ON public.vip_load_plans;
CREATE POLICY "VIP managers can manage vip load plans" ON public.vip_load_plans
FOR ALL TO authenticated
USING (public.has_permission_for_org('vip.manage', org_id))
WITH CHECK (public.has_permission_for_org('vip.manage', org_id));
REVOKE ALL ON public.vip_load_plans FROM anon;

-- 2) Which kind of sale a transaction is ------------------------------------
ALTER TABLE public.transactions
    ADD COLUMN IF NOT EXISTS txn_kind TEXT NOT NULL DEFAULT 'SERVICE';
ALTER TABLE public.transactions DROP CONSTRAINT IF EXISTS transactions_txn_kind_check;
ALTER TABLE public.transactions ADD CONSTRAINT transactions_txn_kind_check
    CHECK (txn_kind IN ('SERVICE', 'VIP_LOAD'));
COMMENT ON COLUMN public.transactions.txn_kind IS
    'SERVICE = a wash/vacuum/... sale; VIP_LOAD = money loaded onto a VIP card (stored value, not service revenue).';

-- 3) Ledger: loads and bonuses, idempotent per sale -------------------------
ALTER TABLE public.vip_card_ledger ADD COLUMN IF NOT EXISTS source_ref TEXT;
ALTER TABLE public.vip_card_ledger DROP CONSTRAINT IF EXISTS vip_card_ledger_kind_check;
ALTER TABLE public.vip_card_ledger ADD CONSTRAINT vip_card_ledger_kind_check
    CHECK (kind IN ('DEDUCT', 'LOAD', 'BONUS'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_vip_card_ledger_source
    ON public.vip_card_ledger(source_ref, kind) WHERE source_ref IS NOT NULL;

-- 4) Terminal: the merchant's active plans ----------------------------------
CREATE OR REPLACE FUNCTION public.get_vip_load_plans()
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_org_id UUID;
BEGIN
  SELECT org_id INTO v_org_id FROM public.device_auth_map WHERE auth_user_id = auth.uid();
  IF v_org_id IS NULL THEN
    RETURN '[]'::json;
  END IF;
  RETURN COALESCE((
    SELECT json_agg(json_build_object('id', id, 'amount_cents', amount_cents, 'bonus_cents', bonus_cents)
                    ORDER BY sort_order, amount_cents)
    FROM public.vip_load_plans
    WHERE org_id = v_org_id AND is_active
  ), '[]'::json);
END;
$$;
REVOKE ALL ON FUNCTION public.get_vip_load_plans() FROM public;
GRANT EXECUTE ON FUNCTION public.get_vip_load_plans() TO authenticated;

-- 5) Terminal: credit a paid VIP_LOAD sale onto a new or existing card ------
CREATE OR REPLACE FUNCTION public.device_vip_load(
  p_ecr_ref_num TEXT,
  p_plan_id UUID,
  p_card_uid TEXT DEFAULT NULL,
  p_mobile_phone TEXT DEFAULT NULL
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_sn TEXT;
  v_org_id UUID;
  v_tx RECORD;
  v_plan RECORD;
  v_card RECORD;
  v_load INT;
  v_bonus INT;
  v_uid TEXT;
  v_qr TEXT;
  v_phone TEXT;
  v_created BOOLEAN := false;
  v_done RECORD;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;
  SELECT device_sn, org_id INTO v_sn, v_org_id FROM public.device_auth_map WHERE auth_user_id = auth.uid();
  IF v_sn IS NULL OR v_org_id IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'device_not_bound');
  END IF;

  SELECT id, amount, payment_status, txn_kind INTO v_tx
  FROM public.transactions
  WHERE ecr_ref_num = p_ecr_ref_num AND device_sn = v_sn
  FOR UPDATE;
  IF v_tx.id IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'sale_not_found');
  END IF;

  -- Already loaded (a retry after a lost response): answer with that result.
  SELECT l.card_uid, c.qr_code, c.balance_cents INTO v_done
  FROM public.vip_card_ledger l JOIN public.vip_cards c ON c.card_uid = l.card_uid
  WHERE l.source_ref = p_ecr_ref_num AND l.kind = 'LOAD';
  IF v_done.card_uid IS NOT NULL THEN
    RETURN json_build_object('success', true, 'already_loaded', true, 'card_uid', v_done.card_uid,
                             'qr_code', v_done.qr_code, 'balance_cents', v_done.balance_cents);
  END IF;

  IF v_tx.payment_status <> 'PAID' OR v_tx.txn_kind <> 'VIP_LOAD' THEN
    RETURN json_build_object('success', false, 'message', 'sale_not_loadable');
  END IF;

  SELECT id, amount_cents, bonus_cents INTO v_plan
  FROM public.vip_load_plans
  WHERE id = p_plan_id AND org_id = v_org_id AND is_active;
  IF v_plan.id IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'plan_not_found');
  END IF;

  IF p_ecr_ref_num LIKE 'TEST\_%' THEN
    -- Technician small real test: credit only what was actually paid.
    IF v_tx.amount <= 0 OR v_tx.amount > v_plan.amount_cents THEN
      RETURN json_build_object('success', false, 'message', 'amount_mismatch');
    END IF;
    v_load := v_tx.amount;
    v_bonus := 0;
  ELSE
    IF v_tx.amount <> v_plan.amount_cents THEN
      RETURN json_build_object('success', false, 'message', 'amount_mismatch');
    END IF;
    v_load := v_plan.amount_cents;
    v_bonus := v_plan.bonus_cents;
  END IF;

  IF p_card_uid IS NULL THEN
    v_phone := NULLIF(regexp_replace(COALESCE(p_mobile_phone, ''), '[^0-9+]', '', 'g'), '');
    LOOP
      v_uid := 'VC' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 14));
      EXIT WHEN NOT EXISTS (SELECT 1 FROM public.vip_cards WHERE card_uid = v_uid);
    END LOOP;
    LOOP
      SELECT string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', floor(random() * 32)::int + 1, 1), '')
      INTO v_qr FROM generate_series(1, 6);
      EXIT WHEN NOT EXISTS (SELECT 1 FROM public.vip_cards WHERE qr_code = v_qr);
    END LOOP;
    INSERT INTO public.vip_cards (card_uid, org_id, balance_cents, is_active, qr_code, mobile_phone)
    VALUES (v_uid, v_org_id, 0, true, v_qr, v_phone);
    v_created := true;
  ELSE
    v_uid := p_card_uid;
  END IF;

  SELECT card_uid, org_id, balance_cents, is_active, qr_code INTO v_card
  FROM public.vip_cards WHERE card_uid = v_uid FOR UPDATE;
  -- Same answer for "no such card" and "another merchant's card" (no enumeration).
  IF v_card.card_uid IS NULL OR v_card.org_id IS DISTINCT FROM v_org_id THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;
  IF NOT v_card.is_active THEN
    RETURN json_build_object('success', false, 'message', 'card_inactive');
  END IF;

  UPDATE public.vip_cards SET balance_cents = balance_cents + v_load + v_bonus WHERE card_uid = v_uid;
  INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, source_ref)
  VALUES (v_uid, v_org_id, 'LOAD', v_load, v_card.balance_cents, v_card.balance_cents + v_load, p_ecr_ref_num);
  IF v_bonus > 0 THEN
    INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents, source_ref)
    VALUES (v_uid, v_org_id, 'BONUS', v_bonus, v_card.balance_cents + v_load, v_card.balance_cents + v_load + v_bonus, p_ecr_ref_num);
  END IF;
  UPDATE public.transactions SET vip_card_uid = v_uid WHERE id = v_tx.id;

  RETURN json_build_object(
    'success', true, 'created', v_created, 'card_uid', v_uid, 'qr_code', v_card.qr_code,
    'loaded_cents', v_load, 'bonus_cents', v_bonus, 'balance_cents', v_card.balance_cents + v_load + v_bonus
  );
END;
$$;
REVOKE ALL ON FUNCTION public.device_vip_load(TEXT, UUID, TEXT, TEXT) FROM public;
GRANT EXECUTE ON FUNCTION public.device_vip_load(TEXT, UUID, TEXT, TEXT) TO authenticated;

-- 6) Starting plans for Eagleson: what the VIP page has advertised so far.
INSERT INTO public.vip_load_plans (org_id, amount_cents, bonus_cents, sort_order)
SELECT '3c8e86c7-4c6d-4d1a-aa15-90aeed7aae76', v.amount, v.bonus, v.ord
FROM (VALUES (5000, 1000, 1), (10000, 2500, 2), (20000, 4000, 3)) AS v(amount, bonus, ord)
WHERE NOT EXISTS (SELECT 1 FROM public.vip_load_plans WHERE org_id = '3c8e86c7-4c6d-4d1a-aa15-90aeed7aae76');
