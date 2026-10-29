-- 2026-10-28: Cael sets a store's Nuvei online payment account (Ourea Scan pay
-- at the table) instead of hand-run SQL. The secret is written only by the
-- nuvei-online-setup edge function (sys admins, after a live test against
-- Nuvei) and never read back: the safe view only says whether one is set.

BEGIN;

-- Appended last: CREATE OR REPLACE VIEW can only add columns at the end.
CREATE OR REPLACE VIEW public.merchant_acquirer_accounts_safe AS
SELECT id, org_id, acquirer, external_merchant_id, merchant_site_id, env,
       status, raw_response, submitted_at, approved_at, created_at,
       application_details_cache, application_details_cached_at,
       (shared_secret IS NOT NULL AND shared_secret <> '') AS has_secret
FROM public.merchant_acquirer_accounts
-- The view runs as its owner, so the table's RLS doesn't apply to it: until
-- 2026-10-28 anyone with the public API key could list every store's accounts
-- (merchant ids, application details). Same rule as the table's policies now.
WHERE public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids());

REVOKE ALL ON public.merchant_acquirer_accounts_safe FROM anon;

COMMIT;
