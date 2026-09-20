-- 2026-09-20: per-card VIP ledger + card link on transactions.
-- Run once against the live database (idempotent).

-- 0) Live `transactions` was missing entry_mode (documented in
--    supabase_full_schema.sql but never applied). The wash app sends
--    entry_mode on every VIP insert, so PostgREST rejected the row and it
--    sat in the offline queue.
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS entry_mode TEXT;

-- 1) Ledger: every successful VIP deduction, written in the same database
--    transaction as the balance change so the two can't diverge.
CREATE TABLE IF NOT EXISTS public.vip_card_ledger (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_uid TEXT NOT NULL REFERENCES public.vip_cards(card_uid) ON DELETE CASCADE,
    org_id UUID,
    kind TEXT NOT NULL DEFAULT 'DEDUCT' CHECK (kind IN ('DEDUCT')),
    amount_cents INTEGER NOT NULL,
    balance_before_cents INTEGER NOT NULL,
    balance_after_cents INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_vip_card_ledger_card_created ON public.vip_card_ledger(card_uid, created_at DESC);
ALTER TABLE public.vip_card_ledger ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Org members can view org vip ledger" ON public.vip_card_ledger;
CREATE POLICY "Org members can view org vip ledger" ON public.vip_card_ledger
    FOR SELECT TO authenticated
    USING (is_sys_admin() OR (org_id IN (SELECT member_org_ids())));
REVOKE ALL ON public.vip_card_ledger FROM anon;

-- 2) Which card paid a VIP transaction (ecr_ref_num is an opaque hash).
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS vip_card_uid TEXT;
CREATE INDEX IF NOT EXISTS idx_transactions_vip_card ON public.transactions(vip_card_uid) WHERE vip_card_uid IS NOT NULL;

-- 3) deduct_vip_balance: same behavior as before, plus the ledger insert.
CREATE OR REPLACE FUNCTION public.deduct_vip_balance(p_card_uid text, p_amount_cents integer)
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_balance_cents INT;
  v_active BOOLEAN;
  v_expiration DATE;
  v_max_daily_cents INT;
  v_daily_spent_cents INT;
  v_daily_spent_date DATE;
  v_org_id UUID;
BEGIN
  IF p_amount_cents <= 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;

  SELECT balance_cents, is_active, card_expiration_date, max_daily_cents, daily_spent_cents, daily_spent_date, org_id
  INTO v_balance_cents, v_active, v_expiration, v_max_daily_cents, v_daily_spent_cents, v_daily_spent_date, v_org_id
  FROM public.vip_cards
  WHERE card_uid = p_card_uid
  FOR UPDATE;

  IF v_balance_cents IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;

  IF NOT v_active THEN
    RETURN json_build_object('success', false, 'message', 'card_inactive');
  END IF;

  IF v_expiration IS NOT NULL AND v_expiration < CURRENT_DATE THEN
    RETURN json_build_object('success', false, 'message', 'card_expired');
  END IF;

  IF v_balance_cents < p_amount_cents THEN
    RETURN json_build_object('success', false, 'message', 'insufficient_balance');
  END IF;

  IF v_daily_spent_date IS DISTINCT FROM CURRENT_DATE THEN
    v_daily_spent_cents := 0;
  END IF;

  IF v_max_daily_cents IS NOT NULL AND v_daily_spent_cents + p_amount_cents > v_max_daily_cents THEN
    RETURN json_build_object('success', false, 'message', 'daily_limit_exceeded');
  END IF;

  UPDATE public.vip_cards
  SET balance_cents = balance_cents - p_amount_cents,
      daily_spent_cents = v_daily_spent_cents + p_amount_cents,
      daily_spent_date = CURRENT_DATE
  WHERE card_uid = p_card_uid;

  INSERT INTO public.vip_card_ledger (card_uid, org_id, kind, amount_cents, balance_before_cents, balance_after_cents)
  VALUES (p_card_uid, v_org_id, 'DEDUCT', p_amount_cents, v_balance_cents, v_balance_cents - p_amount_cents);

  RETURN json_build_object('success', true, 'new_balance_cents', v_balance_cents - p_amount_cents);
END;
$function$;
