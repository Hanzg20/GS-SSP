-- 2026-10-27: drop the legacy jc_* member tables (owner: back up first, then drop).
-- They held old test data (jc_vip_cards 185 cards imported 2025-11, no org_id, 35 jc_transaction_history rows,
-- 5 member levels, 10 settings; members / cards / points empty),
-- no terminal app used them, and RLS let anon read / update jc_vip_cards (name, phone, email).
-- Cael's pages for them were removed first (gs-ssp-cmp 842dda3). The real VIP cards are vip_cards.
-- jc_system_config was that member system's settings (name, points rules). Kept: jc_advertisements /
-- jc_advertisement_logs (the esp32-ads edge function; its jc_api_keys table doesn't exist).
-- No CASCADE: anything still depending on these tables makes the DROP fail instead of
-- silently taking it along.
BEGIN;
CREATE SCHEMA IF NOT EXISTS backup;
REVOKE ALL ON SCHEMA backup FROM anon, authenticated;

DO $$
DECLARE t TEXT;
BEGIN
  -- Referencing tables before the ones they point at.
  FOREACH t IN ARRAY ARRAY['jc_transaction_history', 'jc_points_transactions', 'jc_consumption_records',
                           'jc_membership_cards', 'jc_vip_cards', 'jc_members', 'jc_member_levels',
                           'jc_system_config']
  LOOP
    IF to_regclass('public.' || t) IS NOT NULL THEN
      EXECUTE format('CREATE TABLE backup.%I AS TABLE public.%I', t || '_20261027', t);
      EXECUTE format('DROP TABLE public.%I', t);
      RAISE NOTICE 'backed up and dropped %', t;
    END IF;
  END LOOP;
END $$;

SELECT c.relname, c.reltuples::bigint AS rows_estimate
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'backup' AND c.relname LIKE 'jc\_%\_20261027' ORDER BY 1;
COMMIT;
