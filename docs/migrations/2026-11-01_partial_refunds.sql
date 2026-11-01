-- 2026-11-01: partial refunds on Iris / Ourea -- some items of a sale back, not the whole sale.
--
-- A whole-sale refund keeps its model (payment_status VOIDED / REFUNDED). A partial one
-- leaves the sale PAID and adds up on it: transactions.refunded_cents / refunded_tax_cents
-- (what went back, tax included / the tax part), refunded_at + approver = the latest one.
-- Each partial refund is a row in public.transaction_refunds, keyed by the terminal's own
-- refund_ref so a retry from the offline queue never counts twice; transaction_items.refunded_qty
-- stops the same item going back twice. A sale with a partial refund is not refunded whole
-- afterwards (the terminal offers "the rest" as one more partial), so reports read:
--   VOIDED / REFUNDED -> amount back;  PAID -> refunded_cents back.
-- Card: a reversal is all or nothing, so a partial card refund is PAYWizard's Refund (customer's
-- card) or done in the merchant portal; only recorded here.
-- Written only through transactions_partial_refund (terminal of the sale's own device).

ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS refunded_cents INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS refunded_tax_cents INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public.transaction_items ADD COLUMN IF NOT EXISTS refunded_qty INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN public.transactions.refunded_cents IS
  'Partial refunds so far, tax included (sale stays PAID). Whole-sale refunds use payment_status VOIDED/REFUNDED instead.';
COMMENT ON COLUMN public.transactions.refunded_tax_cents IS 'The tax part of refunded_cents.';
COMMENT ON COLUMN public.transaction_items.refunded_qty IS 'Units of this line given back by partial refunds.';

CREATE TABLE IF NOT EXISTS public.transaction_refunds (
  refund_ref TEXT PRIMARY KEY,
  ecr_ref_num TEXT NOT NULL REFERENCES public.transactions(ecr_ref_num) ON DELETE CASCADE,
  amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
  tax_cents INTEGER NOT NULL DEFAULT 0 CHECK (tax_cents >= 0 AND tax_cents <= amount_cents),
  items JSONB NOT NULL DEFAULT '[]',          -- [{"line_no": 1, "quantity": 2}, ...]
  how TEXT,                                   -- CASH / CARD_REFUND / PORTAL
  approved_by_staff_id UUID REFERENCES public.staff(id) ON DELETE SET NULL,
  approved_by_name TEXT,
  device_sn TEXT REFERENCES public.devices(sn) ON DELETE SET NULL,
  refunded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS transaction_refunds_ecr_ref_num_idx ON public.transaction_refunds (ecr_ref_num);
COMMENT ON TABLE public.transaction_refunds IS 'Partial refunds of a sale (Iris / Ourea), one row each; see transactions.refunded_cents.';
ALTER TABLE public.transaction_refunds ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Org members can view org refunds" ON public.transaction_refunds;
CREATE POLICY "Org members can view org refunds" ON public.transaction_refunds FOR SELECT TO authenticated
  USING (is_sys_admin() OR device_sn IN (SELECT sn FROM devices WHERE org_id IN (SELECT member_org_ids())));
GRANT SELECT ON public.transaction_refunds TO authenticated;

-- Returns 'ok' (also for a refund_ref already recorded), or why not:
-- not_found / not_paid / too_much / bad_items.
CREATE OR REPLACE FUNCTION public.transactions_partial_refund(
  p_refund_ref TEXT, p_ecr_ref_num TEXT, p_amount_cents INTEGER, p_tax_cents INTEGER, p_items JSONB,
  p_how TEXT, p_approver_id UUID, p_approver_name TEXT, p_refunded_at TIMESTAMPTZ DEFAULT now()
) RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  t transactions%ROWTYPE;
  it RECORD;
BEGIN
  IF EXISTS (SELECT 1 FROM transaction_refunds WHERE refund_ref = p_refund_ref) THEN RETURN 'ok'; END IF;
  SELECT * INTO t FROM transactions
   WHERE ecr_ref_num = p_ecr_ref_num
     AND device_sn IN (SELECT device_sn FROM device_auth_map WHERE auth_user_id = auth.uid())
   FOR UPDATE;
  IF NOT FOUND THEN RETURN 'not_found'; END IF;
  IF t.payment_status <> 'PAID' THEN RETURN 'not_paid'; END IF;
  IF p_amount_cents IS NULL OR p_amount_cents <= 0 OR coalesce(p_tax_cents, 0) < 0 OR p_tax_cents > p_amount_cents
     OR t.refunded_cents + p_amount_cents > t.amount THEN RETURN 'too_much'; END IF;

  FOR it IN SELECT (e->>'line_no')::int AS line_no, (e->>'quantity')::int AS qty
              FROM jsonb_array_elements(coalesce(p_items, '[]')) e LOOP
    UPDATE transaction_items SET refunded_qty = refunded_qty + it.qty
     WHERE ecr_ref_num = p_ecr_ref_num AND line_no = it.line_no
       AND it.qty > 0 AND refunded_qty + it.qty <= quantity;
    IF NOT FOUND THEN RAISE EXCEPTION 'bad_items' USING ERRCODE = 'P0001'; END IF;
  END LOOP;

  INSERT INTO transaction_refunds (refund_ref, ecr_ref_num, amount_cents, tax_cents, items, how,
                                   approved_by_staff_id, approved_by_name, device_sn, refunded_at)
  VALUES (p_refund_ref, p_ecr_ref_num, p_amount_cents, coalesce(p_tax_cents, 0), coalesce(p_items, '[]'), p_how,
          p_approver_id, p_approver_name, t.device_sn, coalesce(p_refunded_at, now()));
  UPDATE transactions SET refunded_cents = refunded_cents + p_amount_cents,
                          refunded_tax_cents = refunded_tax_cents + coalesce(p_tax_cents, 0),
                          refunded_at = coalesce(p_refunded_at, now()),
                          refund_approved_by_staff_id = p_approver_id,
                          refund_approved_by_name = p_approver_name
   WHERE id = t.id;
  RETURN 'ok';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM = 'bad_items' THEN RETURN 'bad_items'; END IF;
  RAISE;
END $$;
REVOKE ALL ON FUNCTION public.transactions_partial_refund(TEXT, TEXT, INTEGER, INTEGER, JSONB, TEXT, UUID, TEXT, TIMESTAMPTZ) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.transactions_partial_refund(TEXT, TEXT, INTEGER, INTEGER, JSONB, TEXT, UUID, TEXT, TIMESTAMPTZ) TO authenticated;

NOTIFY pgrst, 'reload schema';
