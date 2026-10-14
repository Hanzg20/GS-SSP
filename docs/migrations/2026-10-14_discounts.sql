-- 2026-10-14: discounts on counter sales (Ourea / Iris), Canadian practice:
-- line discounts first, then the order discount spread over the lines in
-- proportion, tax on what is left (Pricing.kt). Design:
-- https://claude.ai/artifact/B4cXVxmX1HRKfHdRcxo34q
--
-- transaction_items.line_total_cents is the line AFTER its discounts (what the
-- customer paid for it, what tax is on); discount_cents = how much came off it,
-- its own discount plus its share of the order's. transactions.discount_cents =
-- the sale's total discount; transactions.discounts = what was applied, for
-- receipts, reports and audit:
--   [{"scope":"LINE"|"ORDER","line_no":2,"label":"Senior","percent_bps":1000,
--     "amount_cents":130,"preset_id":null,"reason":null,"approved_by":null}]
--
-- discount_presets: the store's ready-made discounts (Cael > Catalog > Discounts),
-- e.g. "Senior 10%", "Loyalty $3". Terminals read their own store's; staff use a
-- preset without approval unless requires_manager. Typed-in discounts over the
-- store's limit need a manager PIN on the terminal (not stored here).

BEGIN;

ALTER TABLE public.transaction_items ADD COLUMN IF NOT EXISTS discount_cents INTEGER NOT NULL DEFAULT 0 CHECK (discount_cents >= 0);
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS discount_cents INTEGER NOT NULL DEFAULT 0 CHECK (discount_cents >= 0);
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS discounts JSONB;
COMMENT ON COLUMN public.transaction_items.discount_cents IS 'Taken off this line: its own discount plus its share of the order discount; line_total_cents is after it';
COMMENT ON COLUMN public.transactions.discount_cents IS 'Total discount on the sale (before tax); subtotal_cents is after it';
COMMENT ON COLUMN public.transactions.discounts IS 'Discounts applied: [{scope, line_no, label, percent_bps, amount_cents, preset_id, reason, approved_by}]';

CREATE TABLE IF NOT EXISTS public.discount_presets (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id           UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name             TEXT NOT NULL CHECK (char_length(trim(name)) BETWEEN 1 AND 40),
  percent_bps      INTEGER CHECK (percent_bps BETWEEN 1 AND 10000),
  amount_cents     INTEGER CHECK (amount_cents > 0),
  applies_to       TEXT NOT NULL DEFAULT 'ANY' CHECK (applies_to IN ('ANY', 'LINE', 'ORDER')),
  requires_manager BOOLEAN NOT NULL DEFAULT false,
  sort             INTEGER NOT NULL DEFAULT 0,
  is_active        BOOLEAN NOT NULL DEFAULT true,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT discount_presets_one_kind CHECK ((percent_bps IS NULL) <> (amount_cents IS NULL))
);
CREATE INDEX IF NOT EXISTS discount_presets_org_idx ON public.discount_presets (org_id) WHERE is_active;
COMMENT ON TABLE public.discount_presets IS 'Store discount presets (Cael > Catalog > Discounts), used on Ourea / Iris';
ALTER TABLE public.discount_presets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.discount_presets FROM anon;

DROP POLICY IF EXISTS "Members read store discount presets" ON public.discount_presets;
CREATE POLICY "Members read store discount presets" ON public.discount_presets FOR SELECT TO authenticated
  USING (is_sys_admin() OR org_id IN (SELECT member_org_ids()));
DROP POLICY IF EXISTS "Catalog managers write discount presets" ON public.discount_presets;
CREATE POLICY "Catalog managers write discount presets" ON public.discount_presets FOR ALL TO authenticated
  USING (is_sys_admin() OR (org_id IN (SELECT member_org_ids()) AND has_permission('catalog.manage')))
  WITH CHECK (is_sys_admin() OR (org_id IN (SELECT member_org_ids()) AND has_permission('catalog.manage')));
DROP POLICY IF EXISTS "Devices read their store discount presets" ON public.discount_presets;
CREATE POLICY "Devices read their store discount presets" ON public.discount_presets FOR SELECT TO authenticated
  USING (org_id IN (SELECT org_id FROM public.device_auth_map WHERE auth_user_id = auth.uid()));
GRANT SELECT ON public.discount_presets TO authenticated;
GRANT INSERT, UPDATE, DELETE ON public.discount_presets TO authenticated;

COMMIT;
