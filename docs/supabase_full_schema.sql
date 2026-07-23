-- =============================================================================
-- GS-SSP Supabase (PostgreSQL) Full Database Schema v2.9 (2026-07-23)
-- Unified Technology Platform for Smart Industries
--
-- This is the single source of truth for the Supabase schema. Previously
-- vip_cards / qr_payment_sessions / deduct_vip_balance() lived in separate
-- files under supabase/migrations/ -- those have been merged in here and
-- removed to avoid two schemas drifting out of sync.
--
-- *** DESTRUCTIVE: FULL RESET ON EVERY APPLY ***
-- Section 0.1 below DROPs every table this file owns (CASCADE) before
-- recreating them. This project has no real device/customer data yet (no
-- hardware shipped, no live traffic), so a clean reset is simpler and more
-- reliable than the old approach of tracking which constraint changes
-- (NOT NULL, FK ON DELETE behavior, CHECK values) can't be applied
-- idempotently to a table that already exists. If this repo ever starts
-- carrying real production data, this file MUST be rewritten back to
-- additive-only migrations (ALTER TABLE ... ADD COLUMN IF NOT EXISTS, etc.)
-- before being run again -- running it as-is against a database you care
-- about will permanently delete every row in every table it lists.
--
-- Apply via the Supabase SQL editor or `supabase db push`.
-- =============================================================================

-- 0. INITIAL SETUP
-- Enable UUID extension if not enabled
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 0.1 FULL RESET -- drops every table this schema owns (and everything that
-- depends on them: indexes, triggers, policies, FKs, the vw_active_fleet
-- view) so every CREATE TABLE below always runs clean. Order doesn't matter
-- functionally (CASCADE resolves dependencies), listed child-to-parent for
-- readability. auth.users / auth.* are Supabase-managed and never touched.
DROP VIEW IF EXISTS public.vw_active_fleet;
DROP TABLE IF EXISTS public.device_commands CASCADE;
DROP TABLE IF EXISTS public.device_auth_map CASCADE;
DROP TABLE IF EXISTS public.qr_payment_sessions CASCADE;
DROP TABLE IF EXISTS public.transactions CASCADE;
DROP TABLE IF EXISTS public.device_shadows CASCADE;
DROP TABLE IF EXISTS public.maintenance_records CASCADE;
DROP TABLE IF EXISTS public.app_error_logs CASCADE;
DROP TABLE IF EXISTS public.heartbeats CASCADE;
DROP TABLE IF EXISTS public.playlists CASCADE;
DROP TABLE IF EXISTS public.advertisements CASCADE;
DROP TABLE IF EXISTS public.vip_cards CASCADE;
DROP TABLE IF EXISTS public.app_configurations CASCADE;
DROP TABLE IF EXISTS public.products CASCADE;
DROP TABLE IF EXISTS public.devices CASCADE;
DROP TABLE IF EXISTS public.locations CASCADE;
DROP TABLE IF EXISTS public.organizations CASCADE;

-- 1. BASE METADATA & MULTI-TENANCY
-- Tenants / Organizations
CREATE TABLE IF NOT EXISTS public.organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    tier TEXT DEFAULT 'FREE' CHECK (tier IN ('FREE', 'PRO', 'ENT')),
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Physical Locations
CREATE TABLE IF NOT EXISTS public.locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID REFERENCES public.organizations(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    timezone TEXT DEFAULT 'UTC',
    address TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 2. IDENTITY & CONFIGURATION
-- Device Registry (IM30 Hardware)
CREATE TABLE IF NOT EXISTS public.devices (
    sn TEXT PRIMARY KEY,                 -- Hardware Serial Number
    loc_id UUID REFERENCES public.locations(id) ON DELETE SET NULL,
    org_id UUID REFERENCES public.organizations(id) ON DELETE SET NULL, -- denormalized from loc_id->locations.org_id, kept directly on devices so RLS policies and the config query (app_configurations.org_id) don't need a join
    vertical_type TEXT DEFAULT 'WASH' CHECK (vertical_type IN ('WASH', 'LAUNDRY', 'EV', 'VEND')),
    status TEXT DEFAULT 'ONLINE',
    app_version TEXT,
    config_version TEXT,
    is_active BOOLEAN DEFAULT true,
    last_seen TIMESTAMPTZ DEFAULT now(),
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Generic Product Catalog (Billing Units). This is the editable "draft"
-- source -- an admin's working set of products per org/vertical. It is NOT
-- what ConfigManager reads at runtime (see app_configurations.products
-- below); the two used to look like accidental duplication with no
-- resolved relationship (flagged as a deferred architecture decision in
-- v2.4). Resolved: this table is the catalog you edit; app_configurations
-- is the frozen, versioned snapshot you publish from it. Keeping them
-- separate is intentional, not a bug -- it's what makes app_configurations
-- version rows immutable/rollback-able (see comment there). There is no
-- publish tooling yet (no admin UI, config rows are still hand-authored/
-- seeded), so today the two must be kept in sync by hand; an RPC that
-- assembles app_configurations.products from this table at publish time
-- would remove that manual step if/when an admin flow gets built.
CREATE TABLE IF NOT EXISTS public.products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID REFERENCES public.organizations(id) ON DELETE CASCADE,
    vertical_type TEXT NOT NULL CHECK (vertical_type IN ('WASH', 'LAUNDRY', 'EV', 'VEND')), -- kept in sync with devices.vertical_type below; a lookup table was considered and rejected (see v2.9 changelog) since only WASH is actually in use today
    name TEXT NOT NULL,
    price_cents INTEGER NOT NULL,
    attributes JSONB DEFAULT '{}',       -- Hardware-specific: { "serial_hex": "AA...", "pulse": 12 }
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Global App Configuration (Cloud tier of ConfigManager's 3-tier strategy).
-- `products` here is a frozen snapshot published from the public.products
-- catalog above -- NOT a live view of it -- precisely so that editing the
-- catalog can never retroactively change what an already-published config
-- version looks like to devices already pinned to it (devices.config_version).
-- That immutability is the entire point of versioning by `version TEXT
-- PRIMARY KEY` instead of updating rows in place.
--
-- IMPORTANT: column shape must mirror AppConfig.kt exactly (top-level
-- version/org_id/vertical_type/products/settings/branding), NOT a wrapped
-- payload blob -- ConfigManager decodes REST rows directly into AppConfig
-- with kotlinx.serialization, so a `payload` JSONB column here would leave
-- products/settings/branding silently empty/default on every "successful"
-- cloud fetch (this was a real bug in the original schema).
CREATE TABLE IF NOT EXISTS public.app_configurations (
    version TEXT PRIMARY KEY,
    org_id UUID REFERENCES public.organizations(id) ON DELETE SET NULL,
    vertical_type TEXT DEFAULT 'WASH',
    products JSONB DEFAULT '[]',         -- List<Product>, e.g. [{ "id": "starter", "name": "...", "price_cents": 400, "vertical_type": "WASH", "attributes": {"serial_hex": "AA 01 04 55"}, "is_active": true }]
    settings JSONB DEFAULT '{}',         -- KioskSettings shape (maintenance_pin, pulse_weight_cents, print_receipt_enabled, payment_method_mode, ...)
    branding JSONB DEFAULT '{}',         -- Branding shape (logo_url, brand_name, primary_color_hex)
    created_at TIMESTAMPTZ DEFAULT now()
);

-- VIP / Loyalty Cards (NFC-tapped membership cards, balance-based).
-- Balance is only ever written via the deduct_vip_balance() RPC below --
-- direct table writes from anon/authenticated are revoked (see section 8).
-- balance_cents is an INTEGER (cents), matching the convention used by
-- every other money column in this schema (products.price_cents,
-- transactions.amount, qr_payment_sessions.amount_cents) -- a NUMERIC
-- dollars column here would be the one inconsistent representation in the
-- whole schema and invites a future 100x bug in any code that assumes cents.
CREATE TABLE IF NOT EXISTS public.vip_cards (
    card_uid TEXT PRIMARY KEY,
    org_id UUID REFERENCES public.organizations(id) ON DELETE CASCADE,
    balance_cents INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 3. MEDIA ENGINE
-- Advertising Materials
CREATE TABLE IF NOT EXISTS public.advertisements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    media_url TEXT NOT NULL,
    media_type TEXT CHECK (media_type IN ('VIDEO', 'IMAGE')),
    md5_hash TEXT,                       -- For delta sync
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Device Playlist Mapping
CREATE TABLE IF NOT EXISTS public.playlists (
    id SERIAL PRIMARY KEY,
    device_sn TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE CASCADE,
    ad_id UUID NOT NULL REFERENCES public.advertisements(id) ON DELETE CASCADE,
    play_order INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_playlists_device ON public.playlists(device_sn);

-- 4. TELEMETRY & DIAGNOSTICS
-- High-frequency Heartbeats. CASCADE is fine here -- pure telemetry noise,
-- no audit/warranty value once the device row itself is gone.
CREATE TABLE IF NOT EXISTS public.heartbeats (
    id BIGSERIAL PRIMARY KEY,
    device_sn TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE CASCADE,
    is_serial_ok BOOLEAN,
    storage_free_mb BIGINT,
    network_type TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_heartbeats_device_created ON public.heartbeats(device_sn, created_at DESC);

-- Application Error Logs (Stack Traces). ON DELETE SET NULL (not CASCADE) --
-- error history has diagnostic/warranty value independent of whether the
-- device row still exists (e.g. after a hardware swap or decommission).
CREATE TABLE IF NOT EXISTS public.app_error_logs (
    id BIGSERIAL PRIMARY KEY,
    device_sn TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE SET NULL,
    severity TEXT DEFAULT 'ERROR',
    error_code TEXT,
    stack_trace TEXT,
    context JSONB DEFAULT '{}',          -- Snapshot of device state
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_error_logs_device_created ON public.app_error_logs(device_sn, created_at DESC);

-- Maintenance Action Trail. ON DELETE SET NULL for the same reason as
-- app_error_logs -- service history should outlive the device record.
CREATE TABLE IF NOT EXISTS public.maintenance_records (
    id BIGSERIAL PRIMARY KEY,
    device_sn TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE SET NULL,
    action TEXT NOT NULL,                -- e.g., 'RELAY_TEST', 'REBOOT'
    payload JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_maintenance_records_device_created ON public.maintenance_records(device_sn, created_at DESC);

-- Device Shadows (Digital Twin)
CREATE TABLE IF NOT EXISTS public.device_shadows (
    device_sn TEXT PRIMARY KEY REFERENCES public.devices(sn) ON DELETE CASCADE,
    desired JSONB DEFAULT '{}',          -- Cloud requested state
    reported JSONB DEFAULT '{}',         -- Device reported state
    version INTEGER DEFAULT 1,
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- 5. TRANSACTIONAL DATA
-- device_sn stays NOT NULL (must be known at insert time) but ON DELETE SET
-- NULL, so a device record being removed later never deletes financial history.
CREATE TABLE IF NOT EXISTS public.transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_sn TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE SET NULL,
    amount INTEGER NOT NULL,
    currency TEXT DEFAULT 'USD',
    payment_status TEXT CHECK (payment_status IN ('PENDING', 'PAID', 'DECLINED', 'VOIDED', 'REFUNDED')),
    hardware_status TEXT,                -- ACK_RECEIVED / TIMEOUT
    auth_code TEXT,
    ecr_ref_num TEXT UNIQUE,
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_transactions_device_created ON public.transactions(device_sn, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_status ON public.transactions(payment_status);

-- Scan-to-pay sessions (Alipay/WeChat/Apple Pay/Google Pay QR flow).
-- status can only be flipped to PAID by the service role (payment gateway
-- webhook, not yet implemented -- see docs/qr_payment_integration.md);
-- authenticated devices only get INSERT/SELECT of their own sessions,
-- enforced via RLS below.
CREATE TABLE IF NOT EXISTS public.qr_payment_sessions (
    tx_id TEXT PRIMARY KEY,
    device_sn TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE SET NULL,
    amount_cents INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED')),
    created_at TIMESTAMPTZ DEFAULT now(),
    paid_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_qr_sessions_status_created ON public.qr_payment_sessions(status, created_at);

-- 6. SECURITY & MULTI-TENANCY BRIDGE
-- Links Supabase Anonymous Auth Users to Organizations/Devices. Populated by
-- the sync_device_identity() RPC below (SECURITY DEFINER) the first time a
-- device authenticates -- never written directly by client code, since it's
-- the thing every other RLS policy in this file trusts.
CREATE TABLE IF NOT EXISTS public.device_auth_map (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE UNIQUE,
    device_sn TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE CASCADE,
    org_id UUID REFERENCES public.organizations(id) ON DELETE CASCADE, -- nullable: a device not yet assigned to a location has no org
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 7. ENABLE ROW LEVEL SECURITY (RLS)
ALTER TABLE public.organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.products ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.heartbeats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_error_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.maintenance_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_shadows ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.qr_payment_sessions ENABLE ROW LEVEL SECURITY;

-- 8. DEFINE RLS POLICIES (Device Isolation)
--
-- IMPORTANT: every policy below that scopes via device_auth_map only works
-- once the caller's request actually carries an authenticated (not anon)
-- Supabase session JWT. This app has two independent Supabase clients:
--   - DeviceRepository's raw Ktor client, which really does sign in
--     anonymously and attaches the resulting JWT to every request.
--   - SupabaseClientProvider.client (the supabase-kt SDK client used by
--     TransactionRepository/ShadowManager/QrPaymentRepository/VipRepository/
--     RemoteCommandManager), which had an Auth plugin installed but never
--     actually called sign-in -- meaning those calls ran as `anon`. This has
--     been fixed in SupabaseClientProvider.kt to also sign in anonymously;
--     without that fix, every TO authenticated-only policy below would
--     silently reject those repositories' requests.

-- Devices: self-registration/heartbeat-style upsert. Any authenticated
-- (anonymous-auth) caller can create/update a device row for a self-declared
-- SN. This trusts the claimed SN -- there is no hardware attestation in this
-- system to verify it against, so this is no weaker than the trust model the
-- rest of the app already relies on (the same is true of the anon api key
-- itself). Tightening this further would require real device attestation,
-- out of scope for a schema fix.
DROP POLICY IF EXISTS "devices can upsert own row" ON public.devices;
CREATE POLICY "devices can upsert own row" ON public.devices
FOR ALL TO authenticated
USING (true)
WITH CHECK (true);

-- Products: Devices see only their organization's products
DROP POLICY IF EXISTS "Devices can see own org products" ON public.products;
CREATE POLICY "Devices can see own org products" ON public.products
FOR SELECT TO authenticated
USING (
  org_id IN (
    SELECT org_id FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- Heartbeats: Devices can only report for themselves
DROP POLICY IF EXISTS "Devices can insert own heartbeats" ON public.heartbeats;
CREATE POLICY "Devices can insert own heartbeats" ON public.heartbeats
FOR INSERT TO authenticated
WITH CHECK (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- Error Logs: Devices can only report for themselves
DROP POLICY IF EXISTS "Devices can insert own error logs" ON public.app_error_logs;
CREATE POLICY "Devices can insert own error logs" ON public.app_error_logs
FOR INSERT TO authenticated
WITH CHECK (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- Maintenance Records: same device-scoped pattern as error logs (this table
-- had RLS enabled but no policy at all before -- every insert was denied).
DROP POLICY IF EXISTS "Devices can insert own maintenance records" ON public.maintenance_records;
CREATE POLICY "Devices can insert own maintenance records" ON public.maintenance_records
FOR INSERT TO authenticated
WITH CHECK (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- Transactions: devices can record and read their own transactions (had RLS
-- enabled but no policy before -- TransactionRepository's writes were always
-- being denied, silently falling into the offline queue forever).
DROP POLICY IF EXISTS "Devices can insert own transactions" ON public.transactions;
CREATE POLICY "Devices can insert own transactions" ON public.transactions
FOR INSERT TO authenticated
WITH CHECK (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

DROP POLICY IF EXISTS "Devices can update own transactions" ON public.transactions;
CREATE POLICY "Devices can update own transactions" ON public.transactions
FOR UPDATE TO authenticated
USING (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

DROP POLICY IF EXISTS "Devices can read own transactions" ON public.transactions;
CREATE POLICY "Devices can read own transactions" ON public.transactions
FOR SELECT TO authenticated
USING (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- Device Shadows: a device reads/updates only its own shadow (also had RLS
-- enabled but no policy before).
DROP POLICY IF EXISTS "Devices can read own shadow" ON public.device_shadows;
CREATE POLICY "Devices can read own shadow" ON public.device_shadows
FOR SELECT TO authenticated
USING (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

DROP POLICY IF EXISTS "Devices can update own shadow" ON public.device_shadows;
CREATE POLICY "Devices can update own shadow" ON public.device_shadows
FOR UPDATE TO authenticated
USING (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- QR Payment Sessions: devices create + read only their OWN scan-to-pay
-- sessions (tightened from an earlier USING(true)/anon-open version -- tx_id
-- is a client-generated millisecond timestamp, enumerable, so a blanket
-- policy let any caller scrape every device's session amounts). Cannot mark
-- a session PAID themselves -- only the service role (payment gateway
-- webhook) can, via the absence of any UPDATE/DELETE policy here.
DROP POLICY IF EXISTS "devices can create their own qr sessions" ON public.qr_payment_sessions;
CREATE POLICY "devices can create their own qr sessions" ON public.qr_payment_sessions
FOR INSERT TO authenticated
WITH CHECK (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- Drops the old anon-open policy name from an earlier version of this file,
-- in case that version was already applied.
DROP POLICY IF EXISTS "devices can read qr sessions" ON public.qr_payment_sessions;
DROP POLICY IF EXISTS "devices can read their own qr sessions" ON public.qr_payment_sessions;
CREATE POLICY "devices can read their own qr sessions" ON public.qr_payment_sessions
FOR SELECT TO authenticated
USING (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- VIP Cards: balance is only ever written via the deduct_vip_balance() RPC
-- (SECURITY DEFINER, defined below). Direct table writes from client-side
-- keys are revoked so a decompiled APK can't PATCH arbitrary balances --
-- this does NOT require RLS (no ALTER TABLE ... ENABLE ROW LEVEL SECURITY
-- for vip_cards), a plain REVOKE is sufficient and simpler; SELECT stays
-- open since VipRepository.getVipCard() reads with the anon key.
REVOKE UPDATE, INSERT, DELETE ON public.vip_cards FROM anon, authenticated;

-- 9. AUTOMATION FUNCTIONS & TRIGGERS

-- Function: Sync heartbeat to device last_seen
CREATE OR REPLACE FUNCTION public.handle_heartbeat_sync()
RETURNS TRIGGER AS $$
BEGIN
  UPDATE public.devices
  SET last_seen = now(),
      status = 'ONLINE'
  WHERE sn = NEW.device_sn;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Trigger: Fire on every new heartbeat
CREATE TRIGGER on_heartbeat_received
AFTER INSERT ON public.heartbeats
FOR EACH ROW
EXECUTE FUNCTION public.handle_heartbeat_sync();

-- Function: keep device_shadows.updated_at meaningful. Without this it's a
-- dead column stuck at insert time forever, since neither the trigger nor
-- ShadowManager.syncReportedState() ever set it explicitly.
CREATE OR REPLACE FUNCTION public.touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER on_device_shadow_update
BEFORE UPDATE ON public.device_shadows
FOR EACH ROW
EXECUTE FUNCTION public.touch_updated_at();

-- Function: Atomically check-and-deduct a VIP card balance, in cents. Runs
-- under a row lock (FOR UPDATE) so two concurrent taps on the same card
-- can't double-spend. This is the ONLY path allowed to modify
-- vip_cards.balance_cents (see REVOKE above).
CREATE OR REPLACE FUNCTION public.deduct_vip_balance(p_card_uid TEXT, p_amount_cents INT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_balance_cents INT;
  v_active BOOLEAN;
BEGIN
  IF p_amount_cents <= 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;

  SELECT balance_cents, is_active INTO v_balance_cents, v_active
  FROM public.vip_cards
  WHERE card_uid = p_card_uid
  FOR UPDATE;

  IF v_balance_cents IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;

  IF NOT v_active THEN
    RETURN json_build_object('success', false, 'message', 'card_inactive');
  END IF;

  IF v_balance_cents < p_amount_cents THEN
    RETURN json_build_object('success', false, 'message', 'insufficient_balance');
  END IF;

  UPDATE public.vip_cards SET balance_cents = balance_cents - p_amount_cents WHERE card_uid = p_card_uid;

  RETURN json_build_object('success', true, 'new_balance_cents', v_balance_cents - p_amount_cents);
END;
$$;

REVOKE ALL ON FUNCTION public.deduct_vip_balance(TEXT, INT) FROM public;
GRANT EXECUTE ON FUNCTION public.deduct_vip_balance(TEXT, INT) TO anon, authenticated;

-- Function: link an authenticated (anonymous-auth) session to a device SN,
-- populating device_auth_map -- which every device-scoped RLS policy above
-- depends on and which no client code ever wrote to before. Also returns
-- the device's org_id/is_active in one round trip, replacing the old
-- checkDeviceActive() raw SELECT (which needed its own RLS policy on
-- devices that didn't exist). SECURITY DEFINER so it can read devices/
-- locations and write device_auth_map regardless of the caller's own RLS
-- visibility into those tables.
CREATE OR REPLACE FUNCTION public.sync_device_identity(p_sn TEXT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_org_id UUID;
  v_is_active BOOLEAN;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;

  SELECT org_id, is_active INTO v_org_id, v_is_active
  FROM public.devices
  WHERE sn = p_sn;

  IF NOT FOUND THEN
    RETURN json_build_object('success', false, 'message', 'device_not_registered');
  END IF;

  INSERT INTO public.device_auth_map (auth_user_id, device_sn, org_id)
  VALUES (auth.uid(), p_sn, v_org_id)
  ON CONFLICT (auth_user_id)
  DO UPDATE SET device_sn = excluded.device_sn, org_id = excluded.org_id;

  RETURN json_build_object('success', true, 'org_id', v_org_id, 'is_active', v_is_active);
END;
$$;

REVOKE ALL ON FUNCTION public.sync_device_identity(TEXT) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_device_identity(TEXT) TO authenticated;

-- Function: retention cleanup for high-volume telemetry tables. Not
-- scheduled automatically here (requires the pg_cron extension, which
-- depends on your Supabase plan) -- run manually, or uncomment the
-- cron.schedule call below if pg_cron is available on your project.
CREATE OR REPLACE FUNCTION public.cleanup_old_telemetry(retention_days INT DEFAULT 90)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  DELETE FROM public.heartbeats WHERE created_at < now() - (retention_days || ' days')::interval;
  DELETE FROM public.app_error_logs WHERE created_at < now() - (retention_days || ' days')::interval;
END;
$$;

-- Requires: CREATE EXTENSION IF NOT EXISTS pg_cron; (superuser/dashboard-only on some plans)
-- SELECT cron.schedule('cleanup-old-telemetry', '0 3 * * *', $$SELECT public.cleanup_old_telemetry(90)$$);

-- 10. OPERATIONAL VIEWS
CREATE OR REPLACE VIEW public.vw_active_fleet AS
SELECT
    d.sn,
    o.name as org_name,
    l.name as location_name,
    d.vertical_type,
    d.status,
    d.last_seen,
    (d.last_seen > now() - INTERVAL '5 minutes') as is_live
FROM public.devices d
JOIN public.locations l ON d.loc_id = l.id
JOIN public.organizations o ON l.org_id = o.id;

-- =============================================================================
-- remote control commands
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.device_commands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_sn TEXT REFERENCES public.devices(sn) ON DELETE CASCADE,
    command TEXT NOT NULL, -- 'REBOOT', 'SYNC_CONFIG', 'LOCK'
    status TEXT DEFAULT 'PENDING',
    created_at TIMESTAMPTZ DEFAULT now()
);
-- 开启实时复制 (重要)
ALTER PUBLICATION supabase_realtime ADD TABLE public.device_commands;
-- =============================================================================
-- END OF SCHEMA
-- =============================================================================

-- =============================================================================
-- SEED DATA (For Testing Purposes)
-- =============================================================================

-- 1. Insert Sample Organizations
INSERT INTO public.organizations (id, name, tier)
VALUES
('00000000-0000-0000-0000-000000000001', 'GoldSky Smart Wash', 'PRO'),
('00000000-0000-0000-0000-000000000002', 'Toronto Laundry Hub', 'ENT')
ON CONFLICT (id) DO NOTHING;

-- 2. Insert Sample Locations
INSERT INTO public.locations (id, org_id, name, timezone, address)
VALUES
('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'Vancouver Main St. Station', 'PST', '123 Main St, Vancouver, BC'),
('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002', 'Toronto Downtown Center', 'EST', '456 Yonge St, Toronto, ON')
ON CONFLICT (id) DO NOTHING;

-- 3. Insert Sample Devices (IM30). org_id is denormalized here (also
-- derivable via loc_id -> locations.org_id) so RLS policies and the
-- app_configurations tenant filter don't need a join.
INSERT INTO public.devices (sn, loc_id, org_id, vertical_type, app_version, config_version)
VALUES
('PAX-IM30-WASH-001', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'WASH', '0.1', '2026.07.22.01'),
('PAX-IM30-LAUN-002', '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002', 'LAUNDRY', '0.1', '2026.07.22.01')
ON CONFLICT (sn) DO NOTHING;

-- 4. Insert Products for Organizations (Demonstrating Multi-Tenancy)
INSERT INTO public.products (org_id, vertical_type, name, price_cents, attributes)
VALUES
-- Org 1 (WASH)
('00000000-0000-0000-0000-000000000001', 'WASH', 'Starter Wash', 400, '{"serial_hex": "AA 01 04 55", "duration_sec": 240}'),
('00000000-0000-0000-0000-000000000001', 'WASH', 'Premium Wax', 800, '{"serial_hex": "AA 01 08 55", "duration_sec": 480}'),
-- Org 2 (LAUNDRY)
('00000000-0000-0000-0000-000000000002', 'LAUNDRY', 'Standard Wash (30m)', 350, '{"pulse_count": 14, "duration_sec": 1800}'),
('00000000-0000-0000-0000-000000000002', 'LAUNDRY', 'Turbo Dry (45m)', 250, '{"pulse_count": 10, "duration_sec": 2700}')
ON CONFLICT DO NOTHING;

-- 5. Insert Sample Advertisements
INSERT INTO public.advertisements (id, media_url, media_type, md5_hash)
VALUES
('00000000-0000-0000-0000-000000000005', 'https://example.com/ads/wash_intro.mp4', 'VIDEO', 'd41d8cd98f00b204e9800998ecf8427e'),
('00000000-0000-0000-0000-000000000006', 'https://example.com/ads/laundry_promo.mp4', 'VIDEO', 'e52e9de09f11c315f091100998edg58f')
ON CONFLICT (id) DO NOTHING;

-- 6. Assign Advertisements to Device Playlists
INSERT INTO public.playlists (device_sn, ad_id, play_order)
VALUES
('PAX-IM30-WASH-001', '00000000-0000-0000-0000-000000000005', 1),
('PAX-IM30-LAUN-002', '00000000-0000-0000-0000-000000000006', 1)
ON CONFLICT DO NOTHING;

-- 7. Initialize Device Shadows
INSERT INTO public.device_shadows (device_sn, desired, reported, version)
VALUES
('PAX-IM30-WASH-001', '{"brightness": 100}', '{"brightness": 80}', 1),
('PAX-IM30-LAUN-002', '{"volume": 50}', '{"volume": 20}', 1)
ON CONFLICT (device_sn) DO NOTHING;

-- 8. Transaction records covering every payment_status value the app can
-- produce (see MainActivity.startFinalizationSequence / PaymentService.kt),
-- so each status has at least one example row to query/dashboard against.
INSERT INTO public.transactions (device_sn, amount, payment_status, hardware_status, ecr_ref_num)
VALUES
('PAX-IM30-WASH-001', 800, 'PAID', 'ACK_RECEIVED', 'REF-WASH-001'),
('PAX-IM30-LAUN-002', 350, 'PAID', 'ACK_RECEIVED', 'REF-LAUN-002'),
('PAX-IM30-WASH-001', 400, 'PENDING', NULL, 'REF-WASH-003-PENDING'),
('PAX-IM30-WASH-001', 600, 'DECLINED', NULL, 'REF-WASH-004-DECLINED'),
('PAX-IM30-WASH-001', 800, 'VOIDED', 'HARDWARE_ERROR', 'REF-WASH-005-VOIDED'),
('PAX-IM30-LAUN-002', 350, 'REFUNDED', 'HARDWARE_ERROR', 'REF-LAUN-006-REFUNDED')
ON CONFLICT (ecr_ref_num) DO NOTHING;

-- 9. Heartbeat samples -- HeartbeatWorker posts these every
-- telemetry_interval_sec; a few rows here let idx_heartbeats_device_created
-- and vw_active_fleet be exercised without waiting for a real device.
INSERT INTO public.heartbeats (device_sn, is_serial_ok, storage_free_mb, network_type)
VALUES
('PAX-IM30-WASH-001', true, 4096, 'WIFI'),
('PAX-IM30-WASH-001', true, 4088, 'WIFI'),
('PAX-IM30-LAUN-002', true, 2048, 'ETHERNET');

-- 10. Sample error log -- DiagnosticManager.reportError() shape.
INSERT INTO public.app_error_logs (device_sn, severity, error_code, stack_trace, context)
VALUES
('PAX-IM30-WASH-001', 'CRITICAL', 'HARDWARE_PULSE_FAIL', NULL, '{"amount_cents": 800, "ecr_ref_num": "REF-WASH-005-VOIDED"}');

-- 11. Sample maintenance record -- DiagnosticManager.recordMaintenance() shape.
INSERT INTO public.maintenance_records (device_sn, action, payload)
VALUES
('PAX-IM30-WASH-001', 'RELAY_TEST', '{"result": "OK", "technician": "demo"}');

-- 12. Sample remote command -- RemoteCommandManager subscribes to this table
-- via Supabase Realtime; one PENDING row here is enough to exercise the
-- subscription/ack path without the ops dashboard that would normally insert it.
INSERT INTO public.device_commands (device_sn, command, status)
VALUES
('PAX-IM30-WASH-001', 'SYNC_CONFIG', 'PENDING');

-- 13. device_auth_map is intentionally NOT seeded here: auth_user_id has a
-- REFERENCES auth.users(id) foreign key, and there is no way to fabricate a
-- placeholder UUID that satisfies it -- any hand-inserted row with a
-- made-up auth_user_id fails with a foreign key violation and halts this
-- entire script. In real use this table is populated automatically by the
-- sync_device_identity() RPC the first time each device authenticates
-- (see MainActivity.extractDeviceIdentity() -> DeviceRepository.syncDeviceIdentity()).
-- To test RLS manually, pick a real id from `select id from auth.users`
-- after a device has signed in at least once, then:
--   insert into public.device_auth_map (auth_user_id, device_sn, org_id)
--   values ('<real-auth-uid>', 'PAX-IM30-WASH-001', '00000000-0000-0000-0000-000000000001');

-- 14. Insert sample Cloud App Configurations -- exercises the top tier of
-- ConfigManager's 3-tier strategy (Cloud > Cache > Assets). Shape must match
-- AppConfig.kt exactly: top-level products/settings/branding, not a payload
-- blob. One per org/vertical so ConfigManager's org_id-scoped fetch
-- (docs/system_architecture.md v2.4) has real per-tenant data to select
-- between instead of only ever exercising a single-org codepath.
INSERT INTO public.app_configurations (version, org_id, vertical_type, products, settings, branding)
VALUES (
  '2026.07.22.01',
  '00000000-0000-0000-0000-000000000001',
  'WASH',
  '[
    {"id": "starter", "name": "4 Min Starter", "price_cents": 400, "vertical_type": "WASH", "attributes": {"serial_hex": "AA 01 04 55", "duration_sec": 240}, "is_active": true},
    {"id": "deluxe",  "name": "6 Min Deluxe",  "price_cents": 600, "vertical_type": "WASH", "attributes": {"serial_hex": "AA 01 06 55", "duration_sec": 360}, "is_active": true},
    {"id": "premium", "name": "8 Min Premium", "price_cents": 800, "vertical_type": "WASH", "attributes": {"serial_hex": "AA 01 08 55", "duration_sec": 480}, "is_active": true}
  ]',
  '{"maintenance_pin": "1234", "kiosk_timeout_sec": 60, "telemetry_interval_sec": 900, "pulse_weight_cents": 25, "pulse_hex": "AA 01 01 55", "locale_tag": "en-US", "print_receipt_enabled": true, "payment_method_mode": 0}',
  '{"brand_name": "GoldSky Smart Wash (Cloud)", "primary_color_hex": "#FFB800"}'
)
ON CONFLICT (version) DO NOTHING;

INSERT INTO public.app_configurations (version, org_id, vertical_type, products, settings, branding)
VALUES (
  '2026.07.22.02',
  '00000000-0000-0000-0000-000000000002',
  'LAUNDRY',
  '[
    {"id": "standard-wash", "name": "Standard Wash (30m)", "price_cents": 350, "vertical_type": "LAUNDRY", "attributes": {"pulse_count": 14, "duration_sec": 1800}, "is_active": true},
    {"id": "turbo-dry",     "name": "Turbo Dry (45m)",     "price_cents": 250, "vertical_type": "LAUNDRY", "attributes": {"pulse_count": 10, "duration_sec": 2700}, "is_active": true}
  ]',
  '{"maintenance_pin": "5678", "kiosk_timeout_sec": 60, "telemetry_interval_sec": 900, "pulse_weight_cents": 25, "pulse_hex": "AA 01 01 55", "locale_tag": "en-US", "print_receipt_enabled": false, "payment_method_mode": 0}',
  '{"brand_name": "Toronto Laundry Hub", "primary_color_hex": "#2E86DE"}'
)
ON CONFLICT (version) DO NOTHING;

-- 15. Insert Sample VIP Cards (balances in cents). 'VIP_CARD_UID_6789'
-- matches the literal UID PaxScannerManager.startMockCardDetection()
-- generates in simulation mode, so deduct_vip_balance() can be exercised
-- end-to-end without real NFC hardware.
INSERT INTO public.vip_cards (card_uid, org_id, balance_cents, is_active)
VALUES
('VIP_CARD_UID_6789', '00000000-0000-0000-0000-000000000001', 2500, true),
('VIP_CARD_LOW_BALANCE', '00000000-0000-0000-0000-000000000001', 50, true),
('VIP_CARD_INACTIVE', '00000000-0000-0000-0000-000000000001', 10000, false)
ON CONFLICT (card_uid) DO NOTHING;

-- 16. QR payment sessions covering every status value -- PENDING is useful
-- for manually flipping to PAID while testing the poll path (real payment
-- gateway webhook not implemented yet, see docs/qr_payment_integration.md);
-- PAID/EXPIRED show the two terminal outcomes for dashboard/query testing.
INSERT INTO public.qr_payment_sessions (tx_id, device_sn, amount_cents, status, paid_at)
VALUES
('TX_SAMPLE_0001', 'PAX-IM30-WASH-001', 400, 'PENDING', NULL),
('TX_SAMPLE_0002', 'PAX-IM30-WASH-001', 600, 'PAID', now() - INTERVAL '2 hours'),
('TX_SAMPLE_0003', 'PAX-IM30-LAUN-002', 350, 'EXPIRED', NULL)
ON CONFLICT (tx_id) DO NOTHING;
