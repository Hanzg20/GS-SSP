-- 2026-10-21: what a command found, in words -- the first user is SCANNER_TEST
-- (Cael asks a terminal to open its scanner when idle; nobody on site to look).
-- Written by the terminal with the final status; NULL for commands that only
-- have a status.
BEGIN;

ALTER TABLE public.device_commands ADD COLUMN IF NOT EXISTS result TEXT;
COMMENT ON COLUMN public.device_commands.result IS
    'Set by the terminal with the final status: what the command found (e.g. SCANNER_TEST: opened / why not). NULL for status-only commands.';

COMMIT;
