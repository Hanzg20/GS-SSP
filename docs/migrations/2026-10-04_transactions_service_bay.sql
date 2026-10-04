-- Aegis Timer dual-bay (2026-10-04): one terminal sells two independent
-- vacuum units. The terminal replaces one of the two original card readers,
-- so the units are numbered (labelled on each hose), not left/right:
-- unit 1 = PIN1 (Pulse 1), unit 2 = PIN2 (Pulse 2).
-- service_bay records which unit a transaction paid for: '1' or '2'; NULL for
-- single-bay terminals and every other vertical. Additive and nullable --
-- older app builds never send it.
--
-- Applied in two steps on 2026-10-04: first with 'L'/'R' values, then
-- renamed to '1'/'2' (the one bench-test row 'R' -> '2') once the naming was
-- corrected. This file is the final state and is safe to re-run.
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS service_bay TEXT;

ALTER TABLE public.transactions DROP CONSTRAINT IF EXISTS transactions_service_bay_check;
UPDATE public.transactions SET service_bay = '1' WHERE service_bay = 'L';
UPDATE public.transactions SET service_bay = '2' WHERE service_bay = 'R';

ALTER TABLE public.transactions ADD CONSTRAINT transactions_service_bay_check
    CHECK (service_bay IS NULL OR service_bay IN ('1', '2'));

COMMENT ON COLUMN public.transactions.service_bay IS
    'Dual-bay Aegis Timer: which numbered unit was paid for (1 = PIN1, 2 = PIN2). NULL = single-bay or non-timer.';
