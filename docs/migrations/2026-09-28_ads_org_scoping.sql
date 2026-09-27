-- 2026-09-28: scope advertising to merchants, and make proof-of-play real.
--
-- Found live (2026-09-28), all different from docs/supabase_full_schema.sql:
--   * advertisements has no org_id -> every ad is in one shared pool, and a
--     terminal with no playlist (AdSyncWorker's fallback) plays EVERY ad,
--     other merchants' included.
--   * The CMP write policies only check "has a profiles row", so any
--     merchant admin could edit/delete any other merchant's ads/playlists.
--   * ad_playback_logs does not exist -> every proof-of-play insert fails
--     and CMP's ad analytics has nothing to read.
--
-- Rules after this migration:
--   * org_id NULL = GoldSky global ad (any terminal may play it; only
--     SYS_ADMIN may create/edit/delete it).
--   * org_id set = that merchant's ad; managed with config.publish on that
--     org (ads are pushed to terminals like configuration).
--   * A playlist entry may only use the device's own org's ads or global ads.
--   * Ad media in Storage lives under <org_id>/..., writable with
--     config.publish on that org; root-level legacy files: SYS_ADMIN only.
--
-- Idempotent; run once in the Supabase SQL editor.

BEGIN;

-- 1. advertisements.org_id (+ an optional display name for the library)
ALTER TABLE public.advertisements
    ADD COLUMN IF NOT EXISTS org_id UUID REFERENCES public.organizations(id) ON DELETE CASCADE;
ALTER TABLE public.advertisements ADD COLUMN IF NOT EXISTS title TEXT;
CREATE INDEX IF NOT EXISTS idx_advertisements_org ON public.advertisements(org_id);

-- 2. Existing data (reviewed 2026-09-28):
--   * the 2 placeholder videos on example.com never existed -> remove
--     (their playlist rows cascade);
--   * the operator notice and the VIP promo text are Eagleson's, not global;
--   * the 5 uploaded videos are GoldSky promos -> stay global (org_id NULL).
DELETE FROM public.advertisements WHERE media_url LIKE 'https://example.com/%';
UPDATE public.advertisements
   SET org_id = '3c8e86c7-4c6d-4d1a-aa15-90aeed7aae76'
 WHERE org_id IS NULL AND media_type IN ('TEXT', 'TEXT_AD');

-- 3. Replace every policy on the two tables (live names differ from the doc).
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT policyname, tablename FROM pg_policies
           WHERE schemaname = 'public' AND tablename IN ('advertisements', 'playlists')
  LOOP
    EXECUTE format('DROP POLICY %I ON public.%I', r.policyname, r.tablename);
  END LOOP;
END $$;

ALTER TABLE public.advertisements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.playlists ENABLE ROW LEVEL SECURITY;

-- The org of the device behind the current (anonymous) device session.
CREATE OR REPLACE FUNCTION public.current_device_org_ids()
RETURNS SETOF UUID
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT d.org_id FROM public.device_auth_map m
  JOIN public.devices d ON d.sn = m.device_sn
  WHERE m.auth_user_id = auth.uid() AND d.org_id IS NOT NULL;
$$;
GRANT EXECUTE ON FUNCTION public.current_device_org_ids() TO authenticated;

-- advertisements: read
CREATE POLICY "Devices read own org and global ads" ON public.advertisements
FOR SELECT TO authenticated
USING (org_id IS NULL OR org_id IN (SELECT public.current_device_org_ids()));

CREATE POLICY "Org members read own org and global ads" ON public.advertisements
FOR SELECT TO authenticated
USING (org_id IS NULL OR public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids()));

-- advertisements: write
CREATE POLICY "Publishers manage own org ads" ON public.advertisements
FOR ALL TO authenticated
USING (
  (org_id IS NULL AND public.is_sys_admin())
  OR (org_id IS NOT NULL AND public.has_permission_for_org('config.publish', org_id))
)
WITH CHECK (
  (org_id IS NULL AND public.is_sys_admin())
  OR (org_id IS NOT NULL AND public.has_permission_for_org('config.publish', org_id))
);

-- playlists: read
CREATE POLICY "Devices read own playlist" ON public.playlists
FOR SELECT TO authenticated
USING (device_sn IN (SELECT device_sn FROM public.device_auth_map WHERE auth_user_id = auth.uid()));

CREATE POLICY "Org members read their devices' playlists" ON public.playlists
FOR SELECT TO authenticated
USING (
  public.is_sys_admin()
  OR device_sn IN (SELECT sn FROM public.devices WHERE org_id IN (SELECT public.member_org_ids()))
);

-- playlists: write (the device's org decides)
CREATE POLICY "Publishers manage their devices' playlists" ON public.playlists
FOR ALL TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.devices d
  WHERE d.sn = playlists.device_sn AND d.org_id IS NOT NULL
    AND public.has_permission_for_org('config.publish', d.org_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM public.devices d
  WHERE d.sn = playlists.device_sn AND d.org_id IS NOT NULL
    AND public.has_permission_for_org('config.publish', d.org_id)
));

-- 4. A playlist may only reference the device's own org's ads or global ads.
CREATE OR REPLACE FUNCTION public.check_playlist_org_consistency()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  v_device_org UUID;
  v_ad_org UUID;
BEGIN
  SELECT org_id INTO v_device_org FROM public.devices WHERE sn = NEW.device_sn;
  SELECT org_id INTO v_ad_org FROM public.advertisements WHERE id = NEW.ad_id;
  IF v_ad_org IS NOT NULL AND v_ad_org IS DISTINCT FROM v_device_org THEN
    RAISE EXCEPTION 'Cross-tenant ad mapping denied: ad belongs to org %, device to org %', v_ad_org, v_device_org;
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS on_playlist_upsert ON public.playlists;
CREATE TRIGGER on_playlist_upsert
BEFORE INSERT OR UPDATE ON public.playlists
FOR EACH ROW EXECUTE FUNCTION public.check_playlist_org_consistency();

-- 5. Proof of play (AnalyticsManager.kt has been inserting into this all along).
CREATE TABLE IF NOT EXISTS public.ad_playback_logs (
    id BIGSERIAL PRIMARY KEY,
    device_sn TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE CASCADE,
    ad_id UUID NOT NULL REFERENCES public.advertisements(id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    duration_sec INTEGER NOT NULL,
    completion_state TEXT CHECK (completion_state IN ('COMPLETED', 'INTERRUPTED_BY_USER')),
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ad_playback_device_ad ON public.ad_playback_logs(device_sn, ad_id);
CREATE INDEX IF NOT EXISTS idx_ad_playback_created ON public.ad_playback_logs(created_at DESC);
ALTER TABLE public.ad_playback_logs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Devices can insert own playback logs" ON public.ad_playback_logs;
CREATE POLICY "Devices can insert own playback logs" ON public.ad_playback_logs
FOR INSERT TO authenticated
WITH CHECK (device_sn IN (SELECT device_sn FROM public.device_auth_map WHERE auth_user_id = auth.uid()));

DROP POLICY IF EXISTS "Org members read their devices' playback logs" ON public.ad_playback_logs;
CREATE POLICY "Org members read their devices' playback logs" ON public.ad_playback_logs
FOR SELECT TO authenticated
USING (
  public.is_sys_admin()
  OR device_sn IN (SELECT sn FROM public.devices WHERE org_id IN (SELECT public.member_org_ids()))
);

-- Per-ad performance for CMP; security_invoker so the reader's RLS applies.
CREATE OR REPLACE VIEW public.vw_ad_performance
WITH (security_invoker = true) AS
SELECT
  a.id AS ad_id,
  a.org_id,
  a.title,
  a.media_type,
  a.text_content,
  a.media_url,
  count(l.id) AS total_impressions,
  count(l.id) FILTER (WHERE l.completion_state = 'INTERRUPTED_BY_USER') AS total_interactions,
  round(avg(l.duration_sec), 1) AS avg_dwell_time_sec,
  max(l.started_at) AS last_played_at
FROM public.advertisements a
JOIN public.ad_playback_logs l ON l.ad_id = a.id
GROUP BY a.id, a.org_id, a.title, a.media_type, a.text_content, a.media_url;
GRANT SELECT ON public.vw_ad_performance TO authenticated;

-- 6. Storage: ad media under <org_id>/..., per-org write access.
DROP POLICY IF EXISTS "CMP admins can upload ad media" ON storage.objects;
DROP POLICY IF EXISTS "CMP admins can delete ad media" ON storage.objects;
DROP POLICY IF EXISTS "Publishers upload own org ad media" ON storage.objects;
DROP POLICY IF EXISTS "Publishers delete own org ad media" ON storage.objects;

CREATE POLICY "Publishers upload own org ad media" ON storage.objects
FOR INSERT TO authenticated
WITH CHECK (
  bucket_id = 'ad-media' AND (
    public.is_sys_admin()
    OR EXISTS (
      SELECT 1 FROM public.organizations o
      WHERE o.id::text = (storage.foldername(name))[1]
        AND public.has_permission_for_org('config.publish', o.id)
    )
  )
);

CREATE POLICY "Publishers delete own org ad media" ON storage.objects
FOR DELETE TO authenticated
USING (
  bucket_id = 'ad-media' AND (
    public.is_sys_admin()
    OR EXISTS (
      SELECT 1 FROM public.organizations o
      WHERE o.id::text = (storage.foldername(name))[1]
        AND public.has_permission_for_org('config.publish', o.id)
    )
  )
);

COMMIT;
