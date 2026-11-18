-- 2026-11-18: Aegis Flex controller API v1.1 (docs/partner/flex_controller_api_v1.1.md).
--
-- A v1.1 partner controller (first: Outdoor Vend's EC3320, firewood) reports how many units the
-- customer actually took. The terminal prices that from the store's packages (Cael) and keeps it on
-- the sale's row, so the operator sees "paid for 3, took 2" next to the money:
--   {"quantity": 3, "taken_quantity": 2, "adjustment": "SHORT" | "OVER" | "NONE",
--    "pickup_end": "TAKEN" | "TIMEOUT", "start_weight_g": ..., "end_weight_g": ...,
--    "paid_cents": 2100, "charged_cents": 1500, "api_version": "1.1"}
-- Null for every other sale (and for a v1 controller). A SHORT card SALE (charged at once) can't be
-- partly given back on the unattended payment app: the terminal also raises FLEX_SHORT_PICKUP
-- (device_error_logs, WARNING) so the operator refunds the difference from the back office.

ALTER TABLE public.transactions
  ADD COLUMN IF NOT EXISTS vend_result JSONB;

CREATE INDEX IF NOT EXISTS transactions_vend_short_idx ON public.transactions (device_sn)
  WHERE vend_result ->> 'adjustment' IN ('SHORT', 'OVER');

NOTIFY pgrst, 'reload schema';
