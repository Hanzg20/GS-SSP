-- 2026-11-01: loyalty points follow partial refunds (follows 2026-11-01_loyalty.sql).
--
-- A sale's EARN is now on its whole subtotal (before tax, after discounts); what was
-- given back before tax (refunded_cents - refunded_tax_cents) takes its share of those
-- points back as REVERSE rows: the sale keeps floor(earned * kept / subtotal). Each
-- partial refund is its own row, keyed '<sale>#r<n>' (n = the sale's refund count), so a
-- replay never takes twice; a refund recorded before the points (offline) is caught up
-- when they are. Points used for a reward stay used on a partial refund (the customer
-- kept the discount); a whole void / refund still gives them back, and takes back only
-- what the partial refunds left.

-- Takes back the share of a sale's earned points its partial refunds gave back.
CREATE OR REPLACE FUNCTION public.member_points_sync_refunds(p_ref TEXT) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, member AS $$
DECLARE
  t transactions%ROWTYPE;
  v_earn INT;
  v_back INT;
  v_keep INT;
  v_n INT;
BEGIN
  SELECT * INTO t FROM transactions WHERE ecr_ref_num = p_ref;
  IF NOT FOUND OR t.member_id IS NULL OR t.payment_status <> 'PAID' THEN RETURN; END IF;
  SELECT delta INTO v_earn FROM member.points_ledger WHERE member_id = t.member_id AND reason = 'EARN' AND ecr_ref_num = p_ref;
  IF coalesce(v_earn, 0) <= 0 THEN RETURN; END IF;
  SELECT coalesce(-sum(delta), 0) INTO v_back FROM member.points_ledger
   WHERE member_id = t.member_id AND reason = 'REVERSE' AND starts_with(ecr_ref_num, p_ref || '#r');
  v_keep := CASE WHEN coalesce(t.subtotal_cents, 0) <= 0 THEN 0
                 ELSE (v_earn::BIGINT * greatest(t.subtotal_cents - greatest(t.refunded_cents - t.refunded_tax_cents, 0), 0) / t.subtotal_cents)::INT END;
  IF v_earn - v_keep - v_back <= 0 THEN RETURN; END IF;
  SELECT count(*) INTO v_n FROM transaction_refunds WHERE ecr_ref_num = p_ref;
  PERFORM member_points_apply(t.member_id, 'REVERSE', -(v_earn - v_keep - v_back), p_ref || '#r' || v_n, 'partial refund', NULL, t.device_sn);
END;
$$;

CREATE OR REPLACE FUNCTION public.transactions_member_refund_points() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  BEGIN
    IF NEW.member_id IS NOT NULL AND NEW.refunded_cents > OLD.refunded_cents THEN
      PERFORM member_points_sync_refunds(NEW.ecr_ref_num);
    END IF;
  EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'member points partial refund failed for %: %', NEW.ecr_ref_num, SQLERRM;
  END;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS transactions_member_refund_points ON public.transactions;
CREATE TRIGGER transactions_member_refund_points AFTER UPDATE OF refunded_cents ON public.transactions
  FOR EACH ROW EXECUTE FUNCTION public.transactions_member_refund_points();

-- Whole void / refund: earned points back off (less what partial refunds already took), redeemed back on.
CREATE OR REPLACE FUNCTION public.transactions_member_points() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, member AS $$
DECLARE
  l member.points_ledger%ROWTYPE;
  v_back INT;
BEGIN
  BEGIN
    IF NEW.member_id IS NOT NULL AND OLD.payment_status = 'PAID' AND NEW.payment_status IN ('VOIDED', 'REFUNDED') THEN
      FOR l IN SELECT * FROM member.points_ledger WHERE member_id = NEW.member_id AND ecr_ref_num = NEW.ecr_ref_num AND reason IN ('EARN', 'REDEEM') LOOP
        v_back := 0;
        IF l.reason = 'EARN' THEN
          SELECT coalesce(-sum(delta), 0) INTO v_back FROM member.points_ledger
           WHERE member_id = NEW.member_id AND reason = 'REVERSE' AND starts_with(ecr_ref_num, NEW.ecr_ref_num || '#r');
        END IF;
        IF l.delta - v_back <> 0 THEN
          PERFORM member_points_apply(NEW.member_id, CASE l.reason WHEN 'EARN' THEN 'REVERSE' ELSE 'RESTORE' END,
                                      -(l.delta - v_back), NEW.ecr_ref_num, 'sale ' || lower(NEW.payment_status), NULL, NEW.device_sn);
        END IF;
      END LOOP;
    END IF;
  EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'member points reversal failed for %: %', NEW.ecr_ref_num, SQLERRM;
  END;
  RETURN NEW;
END;
$$;

-- As before, but EARN on the whole subtotal, then partial refunds already made take their share.
CREATE OR REPLACE FUNCTION public.member_record_sale(p_member_id UUID, p_ecr_ref_num TEXT, p_redeem_points INT DEFAULT 0, p_staff_name TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, member AS $$
DECLARE
  v_org UUID := office_device_org();
  v_set JSONB;
  v_m member.members%ROWTYPE;
  v_t transactions%ROWTYPE;
  v_earn INT;
  v_redeem INT;
  v_after INT;
BEGIN
  SELECT * INTO v_m FROM member.members WHERE id = p_member_id AND org_id = v_org;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'not_found'); END IF;
  SELECT * INTO v_t FROM transactions
   WHERE ecr_ref_num = p_ecr_ref_num AND device_sn IN (SELECT device_sn FROM device_auth_map WHERE auth_user_id = auth.uid());
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'not_paid'); END IF;
  IF EXISTS (SELECT 1 FROM member.points_ledger WHERE member_id = p_member_id AND ecr_ref_num = p_ecr_ref_num AND reason = 'EARN') THEN
    RETURN jsonb_build_object('success', true, 'message', 'already', 'member', member_json(v_m));
  END IF;
  IF v_t.payment_status <> 'PAID' THEN RETURN jsonb_build_object('success', false, 'message', 'not_paid'); END IF;
  v_set := member_settings_for(v_org);
  IF NOT (v_set->>'enabled')::BOOLEAN THEN RETURN jsonb_build_object('success', false, 'message', 'loyalty_off'); END IF;

  UPDATE transactions SET member_id = p_member_id WHERE ecr_ref_num = p_ecr_ref_num;
  v_redeem := least(greatest(coalesce(p_redeem_points, 0), 0), greatest(v_m.points, 0));
  IF v_redeem > 0 THEN
    PERFORM member_points_apply(p_member_id, 'REDEEM', -v_redeem, p_ecr_ref_num, NULL, p_staff_name, v_t.device_sn);
  END IF;
  v_earn := (greatest(coalesce(v_t.subtotal_cents, 0), 0) / 100) * (v_set->>'points_per_dollar')::INT;
  -- Always an EARN row (0 too): it marks the sale as recorded.
  v_after := member_points_apply(p_member_id, 'EARN', v_earn, p_ecr_ref_num, NULL, p_staff_name, v_t.device_sn);
  PERFORM member_points_sync_refunds(p_ecr_ref_num);
  UPDATE member.members SET visits = visits + 1, last_visit_at = now() WHERE id = p_member_id RETURNING * INTO v_m;
  RETURN jsonb_build_object('success', true, 'message', 'ok',
    'earned', v_earn - coalesce((SELECT -sum(delta) FROM member.points_ledger
                                  WHERE member_id = p_member_id AND reason = 'REVERSE' AND starts_with(ecr_ref_num, p_ecr_ref_num || '#r')), 0),
    'redeemed', v_redeem, 'member', member_json(v_m));
END;
$$;

REVOKE ALL ON FUNCTION public.member_points_sync_refunds(TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.transactions_member_refund_points() FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.member_record_sale(UUID, TEXT, INT, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.member_record_sale(UUID, TEXT, INT, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
