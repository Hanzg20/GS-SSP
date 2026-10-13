-- Quick-service counter (Ourea: Settings > Business type = Quick-service restaurant).
-- Each paid order gets a call number (1..999 per terminal, from 1 each day),
-- dine-in or take-out, and an optional note for the kitchen. Retail sales
-- leave all three null (the app leaves them out of the insert).

ALTER TABLE public.transactions
  ADD COLUMN IF NOT EXISTS order_number integer,
  ADD COLUMN IF NOT EXISTS order_type   text,
  ADD COLUMN IF NOT EXISTS order_note   text;

DO $$ BEGIN
  ALTER TABLE public.transactions
    ADD CONSTRAINT transactions_order_type_check CHECK (order_type IN ('DINE_IN', 'TAKEOUT'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

COMMENT ON COLUMN public.transactions.order_number IS 'Quick-service call number (per terminal, daily, 1..999)';
COMMENT ON COLUMN public.transactions.order_type   IS 'Quick-service: DINE_IN or TAKEOUT';
COMMENT ON COLUMN public.transactions.order_note   IS 'Quick-service: the order note printed on the kitchen ticket';

-- Dish options live in products.attributes.modifier_groups (no CMP editor yet).
--   id / name           -- the group, e.g. "spice" / "辣度 Spice"
--   max_select          -- 1 = pick one; leave out for any number
--   required            -- must be picked before the dish goes in the cart
--   options[].price_cents -- added to the dish price (0 = free)
-- Example:
--
-- UPDATE public.products
--    SET attributes = coalesce(attributes, '{}'::jsonb) || jsonb_build_object('modifier_groups', '[
--      {"id":"spice","name":"辣度 Spice","max_select":1,"required":true,"options":[
--        {"id":"spice0","name":"不辣 Not spicy"},
--        {"id":"spice1","name":"微辣 Mild"},
--        {"id":"spice2","name":"中辣 Medium"}]},
--      {"id":"addon","name":"加料 Add-ons","options":[
--        {"id":"egg","name":"加蛋 Egg","price_cents":150},
--        {"id":"beef","name":"加牛肉 Extra beef","price_cents":300}]}
--    ]'::jsonb)
--  WHERE id = '<product id>';
