-- Each payment's tax part, so a split check's payments add up to exactly the check's tax.
ALTER TABLE dine_check_payments ADD COLUMN IF NOT EXISTS tax_cents INTEGER NOT NULL DEFAULT 0;

DROP FUNCTION IF EXISTS public.dine_record_payment(UUID, TEXT, INT, INT, TEXT, TEXT, UUID[]);
CREATE OR REPLACE FUNCTION public.dine_record_payment(p_check_id UUID, p_ecr_ref_num TEXT, p_amount_cents INT, p_tax_cents INT, p_tip_cents INT,
                                                      p_method TEXT, p_paid_by TEXT, p_item_ids UUID[])
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_org UUID := dine_device_org(); v_check dine_checks%ROWTYPE; v_total INT; v_paid INT;
BEGIN
  SELECT * INTO v_check FROM dine_checks WHERE id = p_check_id FOR UPDATE;
  IF NOT FOUND OR v_org IS NULL OR v_check.org_id <> v_org THEN RETURN jsonb_build_object('success', false, 'message', 'check_not_found'); END IF;
  IF coalesce(p_amount_cents, 0) <= 0 OR coalesce(trim(p_ecr_ref_num), '') = '' THEN
    RETURN jsonb_build_object('success', false, 'message', 'bad_payment');
  END IF;
  INSERT INTO dine_check_payments (check_id, ecr_ref_num, amount_cents, tax_cents, tip_cents, method, paid_by)
  VALUES (p_check_id, trim(p_ecr_ref_num), p_amount_cents, greatest(coalesce(p_tax_cents, 0), 0), greatest(coalesce(p_tip_cents, 0), 0), p_method, nullif(trim(p_paid_by), ''))
  ON CONFLICT (ecr_ref_num) DO NOTHING;
  IF p_item_ids IS NOT NULL THEN
    UPDATE dine_check_items SET paid_ecr_ref_num = trim(p_ecr_ref_num)
     WHERE check_id = p_check_id AND id = ANY (p_item_ids) AND paid_ecr_ref_num IS NULL;
  END IF;
  v_total := dine_check_total(p_check_id);
  SELECT coalesce(sum(amount_cents), 0) INTO v_paid FROM dine_check_payments WHERE check_id = p_check_id;
  IF v_paid >= v_total AND v_total > 0 THEN
    UPDATE dine_checks SET status = 'PAID', closed_at = coalesce(closed_at, now()), updated_at = now() WHERE id = p_check_id;
  ELSE
    UPDATE dine_checks SET updated_at = now() WHERE id = p_check_id;
  END IF;
  UPDATE transactions SET check_id = p_check_id WHERE ecr_ref_num = trim(p_ecr_ref_num) AND check_id IS NULL;
  RETURN jsonb_build_object('success', true, 'total_cents', v_total, 'paid_cents', v_paid,
                            'balance_cents', greatest(v_total - v_paid, 0), 'closed', v_paid >= v_total AND v_total > 0);
END $$;
REVOKE ALL ON FUNCTION public.dine_record_payment(UUID, TEXT, INT, INT, INT, TEXT, TEXT, UUID[]) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.dine_record_payment(UUID, TEXT, INT, INT, INT, TEXT, TEXT, UUID[]) TO authenticated;
