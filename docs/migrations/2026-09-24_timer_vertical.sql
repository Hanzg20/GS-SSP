-- 2026-09-24: Aegis Timer (time-based products: self-service vacuum, air pump, ...).
-- Adds 'TIMER' to the vertical_type CHECK on both tables that carry it
-- (kept identical on purpose, see supabase_full_schema.sql). Without this,
-- creating a TIMER product fails with 23514 products_vertical_type_check --
-- the same wall RETAIL hit on 2026-08-27.
-- Idempotent; run once against the live database.

ALTER TABLE public.products DROP CONSTRAINT IF EXISTS products_vertical_type_check;
ALTER TABLE public.products ADD CONSTRAINT products_vertical_type_check
    CHECK (vertical_type IN ('WASH', 'LAUNDRY', 'EV', 'VEND', 'RETAIL', 'TIMER'));

ALTER TABLE public.devices DROP CONSTRAINT IF EXISTS devices_vertical_type_check;
ALTER TABLE public.devices ADD CONSTRAINT devices_vertical_type_check
    CHECK (vertical_type IN ('WASH', 'LAUNDRY', 'EV', 'VEND', 'RETAIL', 'TIMER'));
