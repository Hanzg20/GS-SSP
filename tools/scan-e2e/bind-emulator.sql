-- Moves the Ourea emulator (devices.sn = 'unknown') into the E2E store, and back.
--   psql "$DB" -v ON_ERROR_STOP=1 -v store=e2e   -f tools/scan-e2e/bind-emulator.sql
--   psql "$DB" -v ON_ERROR_STOP=1 -v store=back  -f tools/scan-e2e/bind-emulator.sql
-- "back" = GoldSky Test Store, where the emulator lived before (2026-10-13).
\if :{?store}
\else
  \echo 'set -v store=e2e or -v store=back'
  \quit
\endif
SELECT CASE :'store'
         WHEN 'e2e'  THEN 'e2e5ca00-0000-4000-8000-000000000001'
         WHEN 'back' THEN '426431a4-8f4b-4e8e-afe1-9a0e67907c3e' END AS org,
       CASE :'store'
         WHEN 'e2e'  THEN 'e2e5ca00-0000-4000-8000-000000000002'
         WHEN 'back' THEN '9b7a0225-62ba-4fd7-bfed-94779d95ab1d' END AS loc \gset
BEGIN;
UPDATE devices         SET org_id = :'org'::uuid, loc_id = :'loc'::uuid WHERE sn = 'unknown';
UPDATE device_auth_map SET org_id = :'org'::uuid                       WHERE device_sn = 'unknown';
COMMIT;
