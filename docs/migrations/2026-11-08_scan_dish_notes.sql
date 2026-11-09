-- 2026-11-08: Beaver explains a dish in the customer's language (Ourea Scan AI plan step 4).
-- "What is poutine / 炸酱面 / ملوخية?" -- the dish sheet's "Beaver, what is this dish?" asks
-- scan-assistant (action "explain"); the answer is kept per dish and language so the next
-- customer gets it at once and without a model call. A change to the dish's facts (name,
-- description, ingredients, allergens, diet, spice, how to eat) changes facts_hash and the old
-- note is simply not used.
--
-- Edge function only (service role): scan_dish_note_get / scan_dish_note_put, through the
-- table's QR secret so a note can only be read or written for a dish of that table's store.

BEGIN;

CREATE TABLE IF NOT EXISTS dine.dish_notes (
  product_id  UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  lang        TEXT NOT NULL,
  facts_hash  TEXT NOT NULL,
  name        TEXT,
  note        TEXT NOT NULL,
  model       TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (product_id, lang)
);
COMMENT ON TABLE dine.dish_notes IS 'Beaver''s explanation of a dish in one language (Ourea Scan); facts_hash = the dish facts it was written from';
ALTER TABLE dine.dish_notes ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON dine.dish_notes FROM public, anon, authenticated;

CREATE OR REPLACE FUNCTION public.scan_dish_note_get(p_token TEXT, p_product_id UUID, p_lang TEXT, p_hash TEXT) RETURNS JSONB
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, dine AS $$
  SELECT jsonb_build_object('name', n.name, 'note', n.note)
    FROM dine.dish_notes n
    JOIN products p ON p.id = n.product_id
    JOIN dine.tables t ON t.org_id = p.org_id AND t.qr_token = p_token AND t.is_active
   WHERE n.product_id = p_product_id AND n.lang = p_lang AND n.facts_hash = p_hash
$$;

CREATE OR REPLACE FUNCTION public.scan_dish_note_put(p_token TEXT, p_product_id UUID, p_lang TEXT, p_hash TEXT, p_name TEXT, p_note TEXT, p_model TEXT) RETURNS VOID
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, dine AS $$
  INSERT INTO dine.dish_notes (product_id, lang, facts_hash, name, note, model)
  SELECT p.id, left(p_lang, 10), left(p_hash, 64), left(p_name, 120), left(p_note, 2000), left(p_model, 80)
    FROM products p JOIN dine.tables t ON t.org_id = p.org_id AND t.qr_token = p_token AND t.is_active
   WHERE p.id = p_product_id
  ON CONFLICT (product_id, lang) DO UPDATE
    SET facts_hash = EXCLUDED.facts_hash, name = EXCLUDED.name, note = EXCLUDED.note, model = EXCLUDED.model, created_at = now()
$$;

REVOKE ALL ON FUNCTION public.scan_dish_note_get(TEXT, UUID, TEXT, TEXT) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.scan_dish_note_put(TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.scan_dish_note_get(TEXT, UUID, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION public.scan_dish_note_put(TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT) TO service_role;

COMMIT;
