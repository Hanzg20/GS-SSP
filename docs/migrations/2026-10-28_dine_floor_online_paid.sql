-- 2026-10-28: the floor tells staff when a customer paid online (Ourea Scan pay
-- at the table) and shows what each check still owes.
--
-- * checks[].paid_cents: payments so far (food + tax, as the check closes on),
--   so a table card shows the balance, not the full check.
-- * online_paid: the store's online payments of the last 30 minutes (not
--   refunded), newest first -- the terminal chimes once for each new one and
--   shows "T3 paid online $26.94 · settled / $13.50 still owed".

BEGIN;

CREATE OR REPLACE FUNCTION public.dine_floor() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object(
    'tables', dine_list_tables(),
    'checks', coalesce((
      SELECT jsonb_agg(to_jsonb(c) || jsonb_build_object(
               'total_cents', dine_check_total(c.id),
               'paid_cents', (SELECT coalesce(sum(p.amount_cents), 0) FROM dine.payments p WHERE p.check_id = c.id)))
        FROM dine.checks c
       WHERE c.org_id = dine_device_org() AND c.status IN ('OPEN', 'BILLED')), '[]'::jsonb),
    'scan_pending', dine_scan_pending(),
    'online_paid', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
               'ecr_ref_num', p.ecr_ref_num,
               'table_name', t.name,
               'check_no', c.check_no,
               'amount_cents', p.amount_cents + p.tip_cents,
               'check_closed', c.status = 'PAID',
               'balance_cents', greatest(dine_check_total(c.id) - (SELECT coalesce(sum(q.amount_cents), 0) FROM dine.payments q WHERE q.check_id = c.id), 0),
               'created_at', p.created_at) ORDER BY p.created_at DESC)
        FROM dine.payments p JOIN dine.checks c ON c.id = p.check_id JOIN dine.tables t ON t.id = c.table_id
       WHERE c.org_id = dine_device_org() AND p.method = 'ONLINE' AND p.refund_status IS NULL
         AND p.created_at > now() - interval '30 minutes'), '[]'::jsonb))
$$;

COMMIT;
