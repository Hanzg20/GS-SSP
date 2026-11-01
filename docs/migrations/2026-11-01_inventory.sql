-- 2026-11-01: retail inventory -- stock counts that follow the sales.
--
-- products.stock_qty becomes a column (database_design §3.4: it's summed and
-- reported now); NULL = not tracked, as before. attributes.stock_qty stays as a
-- mirror kept by a trigger, because the terminals' catalog, Ourea Scan and
-- dine_send_items read it there. A writer that still sets attributes.stock_qty
-- (Cael's product editor and import) sets the column through the same trigger,
-- recorded as a COUNT. products.low_stock_qty: below or at it = "low" (NULL = 5).
--
-- retail.stock_moves: every change, with why --
--   SALE    a PAID sale's line (transaction_items with product_id)
--   RETURN  a refund / void of that sale, or items given back (refunded_qty)
--   RECEIVE goods in;  COUNT  a stock count sets the number;  ADJUST  +/- with a note
-- Sale and return moves are keyed by the sale line, so a replayed write never
-- counts twice; a return only comes back if its sale was counted. Stock may go
-- below zero (counts are imperfect): the next count corrects it.
--
-- Sales move stock only through transaction_items: counter / quick-service /
-- delivery sales, and a dine check paid whole or by items. A check paid in equal
-- shares carries no lines, so it doesn't move stock (known gap).
--
-- The stock triggers never make a sale's write fail: an error is a WARNING.

CREATE SCHEMA IF NOT EXISTS retail;
COMMENT ON SCHEMA retail IS 'Retail (Iris / Ourea counter) extension tables; reached only through public retail_* / cmp_retail_* RPCs';
REVOKE ALL ON SCHEMA retail FROM public, anon, authenticated;

ALTER TABLE public.products
  ADD COLUMN IF NOT EXISTS stock_qty     INTEGER,
  ADD COLUMN IF NOT EXISTS low_stock_qty INTEGER CHECK (low_stock_qty IS NULL OR low_stock_qty >= 0);

UPDATE public.products SET stock_qty = (attributes->>'stock_qty')::INT
 WHERE stock_qty IS NULL AND attributes ? 'stock_qty' AND (attributes->>'stock_qty') ~ '^-?\d+$';

CREATE TABLE IF NOT EXISTS retail.stock_moves (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  product_id  UUID NOT NULL REFERENCES public.products(id) ON DELETE CASCADE,
  reason      TEXT NOT NULL CHECK (reason IN ('SALE', 'RETURN', 'RECEIVE', 'COUNT', 'ADJUST')),
  delta       INTEGER NOT NULL,
  qty_after   INTEGER,
  ref         TEXT,
  note        TEXT,
  staff_name  TEXT,
  device_sn   TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS stock_moves_ref_key ON retail.stock_moves (product_id, reason, ref) WHERE ref IS NOT NULL;
CREATE INDEX IF NOT EXISTS stock_moves_org_at_idx ON retail.stock_moves (org_id, created_at);
CREATE INDEX IF NOT EXISTS stock_moves_product_at_idx ON retail.stock_moves (product_id, created_at);
COMMENT ON TABLE retail.stock_moves IS 'Every stock change of a retail product and why (sale, return, receive, count, adjust); written by triggers on sales and by retail_* / cmp_retail_* RPCs';
REVOKE ALL ON ALL TABLES IN SCHEMA retail FROM public, anon, authenticated;

-- ---------------------------------------------------------------- core (not callable from the API)

-- Moves [p_product]'s stock by [p_delta] (or to [p_set] for a COUNT). Untracked products
-- (stock_qty NULL) don't move, except a COUNT, which starts tracking. Returns the
-- new quantity, or NULL when nothing moved (untracked, or [p_ref] already applied).
CREATE OR REPLACE FUNCTION public.retail_stock_apply(
  p_product UUID, p_reason TEXT, p_delta INT, p_ref TEXT,
  p_set INT DEFAULT NULL, p_note TEXT DEFAULT NULL, p_staff TEXT DEFAULT NULL, p_device TEXT DEFAULT NULL
) RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, retail AS $$
DECLARE
  v_p products%ROWTYPE;
  v_delta INT;
  v_after INT;
  v_move UUID;
BEGIN
  SELECT * INTO v_p FROM products WHERE id = p_product FOR UPDATE;
  IF NOT FOUND OR v_p.org_id IS NULL THEN RETURN NULL; END IF;
  IF p_set IS NULL AND v_p.stock_qty IS NULL THEN RETURN NULL; END IF;
  v_delta := CASE WHEN p_set IS NOT NULL THEN p_set - coalesce(v_p.stock_qty, 0) ELSE p_delta END;
  v_after := coalesce(v_p.stock_qty, 0) + v_delta;

  INSERT INTO retail.stock_moves (org_id, product_id, reason, delta, qty_after, ref, note, staff_name, device_sn)
  VALUES (v_p.org_id, p_product, p_reason, v_delta, v_after, p_ref, nullif(trim(p_note), ''), nullif(trim(p_staff), ''), nullif(trim(p_device), ''))
  ON CONFLICT (product_id, reason, ref) WHERE ref IS NOT NULL DO NOTHING
  RETURNING id INTO v_move;
  IF v_move IS NULL THEN RETURN NULL; END IF;

  -- Mark the write so the mirror trigger doesn't record it a second time.
  PERFORM set_config('retail.stock_writer', 'on', true);
  UPDATE products SET stock_qty = v_after WHERE id = p_product;
  PERFORM set_config('retail.stock_writer', 'off', true);
  RETURN v_after;
END;
$$;

-- attributes.stock_qty mirrors the column; a writer setting attributes.stock_qty
-- (or the column directly) outside retail_stock_apply is recorded as a COUNT.
CREATE OR REPLACE FUNCTION public.products_stock_mirror() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, retail AS $$
DECLARE
  v_attr_new TEXT := NEW.attributes->>'stock_qty';
  v_attr_old TEXT := CASE WHEN TG_OP = 'UPDATE' THEN OLD.attributes->>'stock_qty' END;
  v_old INT := CASE WHEN TG_OP = 'UPDATE' THEN OLD.stock_qty END;
BEGIN
  IF current_setting('retail.stock_writer', true) IS DISTINCT FROM 'on' THEN
    IF NEW.stock_qty IS NOT DISTINCT FROM v_old AND v_attr_new IS DISTINCT FROM v_attr_old
       AND (NEW.attributes ? 'stock_qty') THEN
      -- Set through attributes (Cael's editor / import): null = untracked.
      NEW.stock_qty := CASE WHEN v_attr_new ~ '^-?\d+$' THEN v_attr_new::INT END;
    END IF;
    IF NEW.stock_qty IS DISTINCT FROM v_old AND NEW.org_id IS NOT NULL AND NEW.stock_qty IS NOT NULL THEN
      INSERT INTO retail.stock_moves (org_id, product_id, reason, delta, qty_after, note, staff_name)
      VALUES (NEW.org_id, NEW.id, 'COUNT', NEW.stock_qty - coalesce(v_old, 0), NEW.stock_qty,
              CASE WHEN TG_OP = 'INSERT' THEN 'new product' ELSE 'product edit' END,
              (SELECT coalesce(full_name, email) FROM profiles WHERE id = auth.uid()));
    END IF;
  END IF;
  IF NEW.stock_qty IS NULL THEN
    NEW.attributes := coalesce(NEW.attributes, '{}'::jsonb) - 'stock_qty';
  ELSE
    NEW.attributes := jsonb_set(coalesce(NEW.attributes, '{}'::jsonb), '{stock_qty}', to_jsonb(NEW.stock_qty));
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS products_stock_mirror ON public.products;
CREATE TRIGGER products_stock_mirror BEFORE INSERT OR UPDATE ON public.products
  FOR EACH ROW EXECUTE FUNCTION public.products_stock_mirror();

-- A sale line out (the sale is PAID); keyed by the line.
CREATE OR REPLACE FUNCTION public.retail_stock_sale_line(i transaction_items) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF i.product_id IS NULL THEN RETURN; END IF;
  PERFORM retail_stock_apply(i.product_id, 'SALE', -i.quantity, i.ecr_ref_num || '#' || i.line_no, NULL, NULL, NULL, i.device_sn);
END;
$$;

-- [p_qty] units of a sale line back, only if the sale took them out.
CREATE OR REPLACE FUNCTION public.retail_stock_return_line(i transaction_items, p_qty INT, p_ref TEXT) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, retail AS $$
BEGIN
  IF i.product_id IS NULL OR p_qty <= 0 THEN RETURN; END IF;
  IF NOT EXISTS (SELECT 1 FROM retail.stock_moves WHERE product_id = i.product_id AND reason = 'SALE' AND ref = i.ecr_ref_num || '#' || i.line_no) THEN
    RETURN;
  END IF;
  PERFORM retail_stock_apply(i.product_id, 'RETURN', p_qty, p_ref, NULL, NULL, NULL, i.device_sn);
END;
$$;

-- Lines written after their sale went PAID (cash: the row first, then its lines).
CREATE OR REPLACE FUNCTION public.transaction_items_stock() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  BEGIN
    IF TG_OP = 'INSERT' THEN
      IF EXISTS (SELECT 1 FROM transactions WHERE ecr_ref_num = NEW.ecr_ref_num AND payment_status = 'PAID') THEN
        PERFORM retail_stock_sale_line(NEW);
      END IF;
    ELSIF NEW.refunded_qty > OLD.refunded_qty THEN
      -- Some items given back (partial refund): the ref carries the running count, so each refund is its own move.
      PERFORM retail_stock_return_line(NEW, NEW.refunded_qty - OLD.refunded_qty, NEW.ecr_ref_num || '#' || NEW.line_no || '#' || NEW.refunded_qty);
    END IF;
  EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'stock move failed for % line %: %', NEW.ecr_ref_num, NEW.line_no, SQLERRM;
  END;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS transaction_items_stock ON public.transaction_items;
CREATE TRIGGER transaction_items_stock AFTER INSERT OR UPDATE OF refunded_qty ON public.transaction_items
  FOR EACH ROW EXECUTE FUNCTION public.transaction_items_stock();

-- A card sale going PAID takes its lines out; a PAID sale voided / refunded whole
-- brings back what wasn't already given back.
CREATE OR REPLACE FUNCTION public.transactions_stock() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE i transaction_items;
BEGIN
  BEGIN
    IF NEW.payment_status = 'PAID' AND OLD.payment_status IS DISTINCT FROM 'PAID' THEN
      FOR i IN SELECT * FROM transaction_items WHERE ecr_ref_num = NEW.ecr_ref_num LOOP
        PERFORM retail_stock_sale_line(i);
      END LOOP;
    ELSIF OLD.payment_status = 'PAID' AND NEW.payment_status IN ('VOIDED', 'REFUNDED') THEN
      FOR i IN SELECT * FROM transaction_items WHERE ecr_ref_num = NEW.ecr_ref_num LOOP
        PERFORM retail_stock_return_line(i, i.quantity - i.refunded_qty, i.ecr_ref_num || '#' || i.line_no || '#all');
      END LOOP;
    END IF;
  EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'stock move failed for %: %', NEW.ecr_ref_num, SQLERRM;
  END;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS transactions_stock ON public.transactions;
CREATE TRIGGER transactions_stock AFTER UPDATE OF payment_status ON public.transactions
  FOR EACH ROW EXECUTE FUNCTION public.transactions_stock();

-- A store's retail products with their stock (tracked first, low first).
CREATE OR REPLACE FUNCTION public.retail_stock_list_for(p_org UUID) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, retail AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
           'id', p.id, 'name', p.name, 'barcode', p.barcode, 'category', p.attributes->>'category',
           'price_cents', p.price_cents, 'stock_qty', p.stock_qty, 'low_stock_qty', coalesce(p.low_stock_qty, 5),
           'sold_7d', (SELECT coalesce(-sum(m.delta), 0) FROM retail.stock_moves m
                        WHERE m.product_id = p.id AND m.reason IN ('SALE', 'RETURN') AND m.created_at > now() - interval '7 days'))
         ORDER BY (p.stock_qty IS NULL), (p.stock_qty > coalesce(p.low_stock_qty, 5)), p.name), '[]'::jsonb)
    FROM products p
   WHERE p.org_id = p_org AND p.vertical_type = 'RETAIL' AND p.is_active
$$;

CREATE OR REPLACE FUNCTION public.retail_stock_moves_for(p_org UUID, p_product UUID, p_limit INT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, retail AS $$
  SELECT coalesce(jsonb_agg(row_to_json(x)::jsonb ORDER BY x.created_at DESC), '[]'::jsonb) FROM (
    SELECT m.id, m.product_id, p.name, m.reason, m.delta, m.qty_after, m.ref, m.note, m.staff_name, m.device_sn, m.created_at
      FROM retail.stock_moves m JOIN products p ON p.id = m.product_id
     WHERE m.org_id = p_org AND (p_product IS NULL OR m.product_id = p_product)
     ORDER BY m.created_at DESC LIMIT least(greatest(coalesce(p_limit, 50), 1), 500)) x
$$;

-- RECEIVE (+qty) / COUNT (set to qty) / ADJUST (+/-qty, note required) / UNTRACK / LOW (threshold).
CREATE OR REPLACE FUNCTION public.retail_stock_change_for(
  p_org UUID, p_product UUID, p_kind TEXT, p_qty INT, p_note TEXT, p_ref TEXT, p_staff TEXT, p_device TEXT
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_p products%ROWTYPE;
  v_after INT;
BEGIN
  SELECT * INTO v_p FROM products WHERE id = p_product AND org_id = p_org;
  IF NOT FOUND THEN RETURN jsonb_build_object('success', false, 'message', 'not_found'); END IF;
  CASE p_kind
    WHEN 'RECEIVE' THEN
      IF coalesce(p_qty, 0) <= 0 OR p_qty > 100000 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_qty'); END IF;
      IF v_p.stock_qty IS NULL THEN
        v_after := retail_stock_apply(p_product, 'RECEIVE', p_qty, p_ref, p_qty, p_note, p_staff, p_device);
      ELSE
        v_after := retail_stock_apply(p_product, 'RECEIVE', p_qty, p_ref, NULL, p_note, p_staff, p_device);
      END IF;
    WHEN 'COUNT' THEN
      IF p_qty IS NULL OR p_qty < 0 OR p_qty > 1000000 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_qty'); END IF;
      v_after := retail_stock_apply(p_product, 'COUNT', NULL, p_ref, p_qty, p_note, p_staff, p_device);
    WHEN 'ADJUST' THEN
      IF coalesce(p_qty, 0) = 0 OR abs(p_qty) > 100000 THEN RETURN jsonb_build_object('success', false, 'message', 'bad_qty'); END IF;
      IF coalesce(trim(p_note), '') = '' THEN RETURN jsonb_build_object('success', false, 'message', 'note_required'); END IF;
      IF v_p.stock_qty IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'not_tracked'); END IF;
      v_after := retail_stock_apply(p_product, 'ADJUST', p_qty, p_ref, NULL, p_note, p_staff, p_device);
    WHEN 'UNTRACK' THEN
      PERFORM set_config('retail.stock_writer', 'on', true);
      UPDATE products SET stock_qty = NULL WHERE id = p_product;
      PERFORM set_config('retail.stock_writer', 'off', true);
      RETURN jsonb_build_object('success', true, 'message', 'ok', 'stock_qty', NULL);
    WHEN 'LOW' THEN
      IF p_qty IS NOT NULL AND (p_qty < 0 OR p_qty > 100000) THEN RETURN jsonb_build_object('success', false, 'message', 'bad_qty'); END IF;
      UPDATE products SET low_stock_qty = p_qty WHERE id = p_product;
      RETURN jsonb_build_object('success', true, 'message', 'ok');
    ELSE
      RETURN jsonb_build_object('success', false, 'message', 'bad_kind');
  END CASE;
  -- NULL from apply = this ref was already applied (a double tap / retry): fine.
  RETURN jsonb_build_object('success', true, 'message', CASE WHEN v_after IS NULL THEN 'already_done' ELSE 'ok' END,
                            'stock_qty', (SELECT stock_qty FROM products WHERE id = p_product));
END;
$$;

-- ---------------------------------------------------------------- terminals

CREATE OR REPLACE FUNCTION public.retail_stock_list() RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT CASE WHEN office_device_org() IS NULL THEN '[]'::jsonb ELSE retail_stock_list_for(office_device_org()) END
$$;

CREATE OR REPLACE FUNCTION public.retail_stock_moves(p_product_id UUID DEFAULT NULL, p_limit INT DEFAULT 50) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT CASE WHEN office_device_org() IS NULL THEN '[]'::jsonb ELSE retail_stock_moves_for(office_device_org(), p_product_id, p_limit) END
$$;

CREATE OR REPLACE FUNCTION public.retail_stock_change(
  p_product_id UUID, p_kind TEXT, p_ref TEXT,
  p_qty INT DEFAULT NULL, p_note TEXT DEFAULT NULL, p_staff_name TEXT DEFAULT NULL, p_device_sn TEXT DEFAULT NULL
) RETURNS JSONB
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  SELECT CASE WHEN office_device_org() IS NULL THEN jsonb_build_object('success', false, 'message', 'not_found')
              ELSE retail_stock_change_for(office_device_org(), p_product_id, p_kind, p_qty, p_note, p_ref, p_staff_name, p_device_sn) END
$$;

-- ---------------------------------------------------------------- Cael (catalog.manage to write)

CREATE OR REPLACE FUNCTION public.cmp_retail_stock_list(p_org UUID) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN retail_stock_list_for(p_org);
END;
$$;

CREATE OR REPLACE FUNCTION public.cmp_retail_stock_moves(p_org UUID, p_product_id UUID DEFAULT NULL, p_limit INT DEFAULT 200) RETURNS JSONB
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NOT (is_sys_admin() OR p_org IN (SELECT member_org_ids())) THEN RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501'; END IF;
  RETURN retail_stock_moves_for(p_org, p_product_id, p_limit);
END;
$$;

CREATE OR REPLACE FUNCTION public.cmp_retail_stock_change(
  p_org UUID, p_product_id UUID, p_kind TEXT, p_ref TEXT, p_qty INT DEFAULT NULL, p_note TEXT DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NOT (is_sys_admin() OR (p_org IN (SELECT member_org_ids()) AND has_permission('catalog.manage'))) THEN
    RAISE EXCEPTION 'not allowed' USING ERRCODE = '42501';
  END IF;
  RETURN retail_stock_change_for(p_org, p_product_id, p_kind, p_qty, p_note, p_ref,
                                 (SELECT coalesce(full_name, email) FROM profiles WHERE id = auth.uid()), 'Cael');
END;
$$;

-- ---------------------------------------------------------------- grants

REVOKE ALL ON FUNCTION public.retail_stock_apply(UUID, TEXT, INT, TEXT, INT, TEXT, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.products_stock_mirror() FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.retail_stock_sale_line(transaction_items) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.retail_stock_return_line(transaction_items, INT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.transaction_items_stock() FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.transactions_stock() FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.retail_stock_list_for(UUID) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.retail_stock_moves_for(UUID, UUID, INT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.retail_stock_change_for(UUID, UUID, TEXT, INT, TEXT, TEXT, TEXT, TEXT) FROM public, anon, authenticated;

REVOKE ALL ON FUNCTION public.retail_stock_list() FROM public, anon;
REVOKE ALL ON FUNCTION public.retail_stock_moves(UUID, INT) FROM public, anon;
REVOKE ALL ON FUNCTION public.retail_stock_change(UUID, TEXT, TEXT, INT, TEXT, TEXT, TEXT) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_retail_stock_list(UUID) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_retail_stock_moves(UUID, UUID, INT) FROM public, anon;
REVOKE ALL ON FUNCTION public.cmp_retail_stock_change(UUID, UUID, TEXT, TEXT, INT, TEXT) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.retail_stock_list() TO authenticated;
GRANT EXECUTE ON FUNCTION public.retail_stock_moves(UUID, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.retail_stock_change(UUID, TEXT, TEXT, INT, TEXT, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_retail_stock_list(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_retail_stock_moves(UUID, UUID, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cmp_retail_stock_change(UUID, UUID, TEXT, TEXT, INT, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
