-- 2026-10-13: gp_*, jc_*, e_* are not read or written by any GS-SSP app and
-- their owners are unknown. Marked PENDING (docs/database_design.md §2):
-- don't build on them; move to `legacy` or drop once their owners are known.
-- Comments only, data untouched.
DO $$
DECLARE t TEXT;
BEGIN
  FOR t IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind = 'r'
              AND (c.relname LIKE 'gp\_%' OR c.relname LIKE 'jc\_%' OR c.relname IN ('e_merchants', 'e_transactions'))
  LOOP
    EXECUTE format('COMMENT ON TABLE public.%I IS %L', t,
      'PENDING (待处理): not used by GS-SSP, owner unknown. Do not build on it; to move to legacy or drop. See docs/database_design.md section 2.');
  END LOOP;
END $$;
SELECT c.relname, obj_description(c.oid) IS NOT NULL AS marked
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
   AND (c.relname LIKE 'gp\_%' OR c.relname LIKE 'jc\_%' OR c.relname LIKE 'e\_%') ORDER BY 1;
