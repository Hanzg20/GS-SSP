-- =============================================================================
-- GS-SSP Supabase (PostgreSQL) Full Database Schema v2.17 (2026-07-25)
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
-- readability. auth.users/auth.* rows themselves are Supabase-managed and
-- never touched -- the one exception is the on_auth_user_created trigger
-- (public.handle_new_profile(), see §1 below), which this file owns and
-- must drop/recreate on every reset the same as everything else here.
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
DROP VIEW IF EXISTS public.vw_active_fleet;
DROP TABLE IF EXISTS public.device_commands CASCADE;
DROP TABLE IF EXISTS public.device_auth_map CASCADE;
DROP TABLE IF EXISTS public.audit_logs CASCADE;
DROP TABLE IF EXISTS public.org_members CASCADE;
DROP TABLE IF EXISTS public.coupon_redemptions CASCADE;
DROP TABLE IF EXISTS public.coupons CASCADE;
DROP TABLE IF EXISTS public.profiles CASCADE;
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

-- Recursive hierarchy for future DISTRIBUTOR/regional tiers (see
-- docs/cloud_management_platform_design.md §1/§2). NULL = top-level org
-- (every org today). Additive/inert on its own -- nothing reads this column
-- yet: redeem_coupon()/issue_compensation_coupon() and every existing RLS
-- policy still match org_id by exact equality, not by walking this tree.
-- Actually scoping a DISTRIBUTOR to "this org + its descendants" requires a
-- WITH RECURSIVE lookup added to each of those call sites -- separate,
-- not-yet-designed work; this column alone doesn't unlock that role.
-- ON DELETE SET NULL (not CASCADE): deleting a parent org should orphan its
-- children back to top-level, never cascade-delete an entire org subtree
-- (and its devices/transactions) as a side effect of removing one row.
ALTER TABLE public.organizations ADD COLUMN IF NOT EXISTS parent_id UUID REFERENCES public.organizations(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_organizations_parent ON public.organizations(parent_id);

-- Human identity for the cloud management platform (CMP) -- distinct from
-- device identity (devices/device_auth_map below, which is anonymous-auth
-- and has no human attached). One row per signed-up Supabase Auth user,
-- auto-created by the handle_new_profile() trigger on auth.users (see §9) --
-- nothing else populates this table. See docs/cloud_management_platform_design.md
-- §2/§7.1: this was the single blocking prerequisite for the CMP's human
-- IAM subsystem; MVP scope only (see org_members below), not the full
-- five-role model that section describes.
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID NOT NULL,
    email TEXT NOT NULL,
    full_name TEXT NULL,
    created_at TIMESTAMPTZ NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NULL DEFAULT now(),
    CONSTRAINT profiles_pkey PRIMARY KEY (id),
    CONSTRAINT profiles_id_fkey FOREIGN KEY (id) REFERENCES auth.users (id) ON DELETE CASCADE
);

-- Who has which role on which org (MVP: SYS_ADMIN global / MERCHANT_ADMIN
-- per-org -- see docs/cloud_management_platform_design.md §2's role table;
-- DISTRIBUTOR/LOC_MANAGER deliberately deferred -- organizations.parent_id
-- exists now, but the CHECK below still only allows SYS_ADMIN/MERCHANT_ADMIN,
-- and no recursive-scoping logic has been added anywhere yet; there's also
-- no real multi-site customer needing them today). org_id NULL = global scope,
-- enforced by the CHECK below so the two scopes can never be confused --
-- this also means a single role column on profiles wouldn't have worked
-- (a MERCHANT_ADMIN is scoped to exactly one org; a future DISTRIBUTOR
-- would need several), hence a separate table.
-- capability is a second, orthogonal axis from role: role answers "which
-- org's data can this profile see" (scope), capability answers "what can
-- they do within that scope" (permission level). Originally the CMP portal
-- had a second, uncoordinated role system for this (a Lovable-scaffold
-- `user_roles`/`app_role` enum: admin/employee/decision_maker, no org
-- concept at all) -- merged into this single table instead of leaving two
-- systems live (see §9 for the DROP of that scaffold). Defaults to 'admin'
-- so every pre-existing SYS_ADMIN/MERCHANT_ADMIN row (there were none live
-- yet when this column was added, but future ones via plain INSERT without
-- this column) isn't silently locked out of write actions.
CREATE TABLE IF NOT EXISTS public.org_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    org_id UUID REFERENCES public.organizations(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('SYS_ADMIN', 'MERCHANT_ADMIN')),
    capability TEXT NOT NULL DEFAULT 'admin' CHECK (capability IN ('admin', 'employee', 'decision_maker')),
    CONSTRAINT org_members_scope_check CHECK ((role = 'SYS_ADMIN') = (org_id IS NULL)),
    created_at TIMESTAMPTZ DEFAULT now()
);
-- Two partial unique indexes instead of one UNIQUE(profile_id, org_id) --
-- Postgres treats every NULL as distinct for uniqueness purposes, so a
-- plain unique constraint would silently allow duplicate SYS_ADMIN
-- (org_id IS NULL) rows for the same profile.
CREATE UNIQUE INDEX IF NOT EXISTS idx_org_members_org_role_uniq ON public.org_members(profile_id, org_id) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_org_members_global_role_uniq ON public.org_members(profile_id) WHERE org_id IS NULL;

-- gs-ssp-cmp (the Lovable-generated CMP frontend repo) provisioned its own,
-- uncoordinated identity/role system on this same Supabase project before
-- org_members existed: an `app_role` enum (admin/employee/decision_maker),
-- a `user_roles` table (auth.users.id -> role, no org concept at all), and
-- a `has_role()` check function. Found live 2026-07-25 with 2 real rows
-- (both real portal logins) while wiring RLS for the CMP -- org_members.role
-- (scope) + org_members.capability (permission level, added above) is the
-- one system going forward; migrate those 2 rows into org_members by hand
-- before running this DROP against a database that still needs them.
DROP TABLE IF EXISTS public.user_roles CASCADE;
DROP TYPE IF EXISTS public.app_role CASCADE;
DROP FUNCTION IF EXISTS public.has_role(UUID, public.app_role);

-- Insert-only audit trail for portal/admin actions (distinct from
-- maintenance_records/app_error_logs below, which cover device-side
-- technician actions). Written only by SECURITY DEFINER RPCs (see
-- issue_compensation_coupon() in §9) -- never directly by client code, same
-- trust boundary as every other audit-sensitive table in this file.
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_profile_id UUID REFERENCES public.profiles(id),
    org_id UUID REFERENCES public.organizations(id), -- nullable: a SYS_ADMIN action may not be org-scoped
    action TEXT NOT NULL,
    target_table TEXT,
    target_id TEXT,
    details JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_org_created ON public.audit_logs(org_id, created_at DESC);

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

-- 12-character member QR code (see docs/coupon_redemption_integration.md
-- §2.1) -- a separate generated field, NOT card_uid (which is the NFC
-- serial). Nullable: not every card has one issued yet.
ALTER TABLE public.vip_cards ADD COLUMN IF NOT EXISTS qr_code TEXT UNIQUE;
CREATE INDEX IF NOT EXISTS idx_vip_cards_qr_code ON public.vip_cards(qr_code);

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

-- Coupons / promotions / compensation vouchers (see
-- docs/coupon_redemption_integration.md for the full design). Writes
-- (issuance) are NOT done by IM30 -- the cloud management platform is the
-- only writer; IM30 only ever calls redeem_coupon() below. Defined here
-- (after transactions, not back up with vip_cards/app_configurations) --
-- related_transaction_id/coupon_redemptions.transaction_id both reference
-- public.transactions(id), which must already exist as a table before this
-- one is created (moved here 2026-07-24 after this exact ordering bug broke
-- a live apply: "relation public.transactions does not exist").
CREATE TABLE IF NOT EXISTS public.coupons (
    code TEXT PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('PERCENT_OFF', 'FIXED_OFF', 'FREE_WASH')), -- compensation vouchers are FIXED_OFF + issued_reason='COMPENSATION', not a separate type
    value INTEGER NOT NULL,              -- PERCENT_OFF: 0-100; FIXED_OFF: cents; FREE_WASH: ignored
    applicable_product_id UUID REFERENCES public.products(id), -- NULL = any package
    max_uses INTEGER NOT NULL DEFAULT 1,
    uses_count INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ,
    issued_reason TEXT,                  -- 'PROMOTION' | 'COMPENSATION' | 'MARKETING' -- audit/reporting only
    -- Which MERCHANT_ADMIN/SYS_ADMIN issued this (compensation coupons via
    -- issue_compensation_coupon(), §9) -- nullable since not every coupon is
    -- human-issued (e.g. bulk marketing imports might not be, if that path
    -- is ever added).
    issued_by_profile_id UUID REFERENCES public.profiles(id),
    related_transaction_id UUID REFERENCES public.transactions(id), -- compensation: which failed transaction this offsets
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_coupons_org ON public.coupons(org_id) WHERE is_active;

-- Redemption audit trail -- one row per successful redemption, even for a
-- coupon whose max_uses > 1.
CREATE TABLE IF NOT EXISTS public.coupon_redemptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_code TEXT NOT NULL REFERENCES public.coupons(code),
    device_sn TEXT NOT NULL REFERENCES public.devices(sn) ON DELETE SET NULL,
    -- The resulting (discounted) transaction, if the customer went on to
    -- complete payment -- left NULL otherwise (scanned but abandoned).
    -- Not populated by the app in the initial redemption flow; would need
    -- TransactionRepository to surface the generated transaction id first.
    transaction_id UUID REFERENCES public.transactions(id),
    redeemed_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_coupon_redemptions_code ON public.coupon_redemptions(coupon_code);

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
--
-- Every one of these is force-enabled by this Supabase project regardless
-- of whether it's listed here (confirmed live 2026-07-24 via
-- pg_class.relrowsecurity -- true for every public-schema table this file
-- creates, including ones this file never explicitly enabled it for). These
-- ALTER TABLE statements are kept for documentation/portability (a vanilla
-- Postgres instance without that platform default would need them), but
-- don't assume "not listed here" means "no RLS, plain GRANTs apply" on THIS
-- project -- it doesn't. Every table needs an actual policy, or it's
-- default-deny for anon/authenticated no matter what's GRANTed.
ALTER TABLE public.organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.org_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_auth_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.products ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.vip_cards ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_configurations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.advertisements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.playlists ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_commands ENABLE ROW LEVEL SECURITY;
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

-- Human/portal identity (profiles/org_members) -- self-read-only. Scoped by
-- a *real* Supabase Auth session (auth.uid() = a signed-up human's
-- auth.users id), not the device anonymous-auth sessions the rest of this
-- section is about. No write policies here on purpose: writes to org_members
-- (granting roles) are a manual/service_role bootstrap step for now (see the
-- seed-data comment below).
--
-- REVISED 2026-07-25: docs/cloud_management_platform_design.md §7.2 had
-- originally called for Edge-Functions-with-service_role instead of
-- per-table RLS for the portal, same reasoning as issue_compensation_coupon()
-- below (money-moving writes want a single audited choke point). But the
-- actual gs-ssp-cmp frontend that got built (Lovable-generated) already
-- calls supabase.from(...) directly from ~6 components for reads (devices,
-- transactions, coupons, products, app_configurations, device_commands) --
-- routing all of that through new Edge Functions would mean rewriting the
-- frontend's data layer, not just adding backend policy. Chose org-scoped
-- RLS for reads instead (below), matching the device side's existing
-- pattern, and keeping SECURITY DEFINER RPCs for the actual money-moving
-- writes (issue_compensation_coupon still the only way to mint a
-- compensation coupon; plain org-scoped RLS covers direct marketing-coupon
-- CRUD and config/product edits, which aren't ledger-sensitive the same way).
DROP POLICY IF EXISTS "Users can view own profile" ON public.profiles;
CREATE POLICY "Users can view own profile" ON public.profiles
FOR SELECT TO authenticated
USING (id = auth.uid());

DROP POLICY IF EXISTS "Users can view own org memberships" ON public.org_members;
CREATE POLICY "Users can view own org memberships" ON public.org_members
FOR SELECT TO authenticated
USING (profile_id = auth.uid());

-- Helper functions for the CMP org-scoped policies below. Plain SQL
-- functions (not SECURITY DEFINER) so they run as the calling role -- safe
-- because org_members' own RLS policy above ("own row only") already lets a
-- caller see their own membership rows, which is all these need to read.
CREATE OR REPLACE FUNCTION public.member_org_ids()
RETURNS SETOF UUID
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = public
AS $$
  SELECT org_id FROM public.org_members WHERE profile_id = auth.uid() AND org_id IS NOT NULL;
$$;

CREATE OR REPLACE FUNCTION public.is_sys_admin()
RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = public
AS $$
  SELECT EXISTS (SELECT 1 FROM public.org_members WHERE profile_id = auth.uid() AND role = 'SYS_ADMIN');
$$;

-- True if the caller has an org_members row (any org, or the global
-- SYS_ADMIN row) with the given capability -- used to gate writes
-- (device commands, product/config edits, marketing coupons) separately
-- from the read-scoping the other two helpers provide.
CREATE OR REPLACE FUNCTION public.has_capability(target_capability TEXT)
RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = public
AS $$
  SELECT EXISTS (SELECT 1 FROM public.org_members WHERE profile_id = auth.uid() AND capability = target_capability);
$$;

-- CMP portal (human) read/write access to fleet data, scoped by
-- org_members. Additive to the device-side policies elsewhere in this
-- section -- a device's own anonymous session and a human portal session
-- reach these tables through entirely different policies, neither
-- interferes with the other.
DROP POLICY IF EXISTS "Org members can view org devices" ON public.devices;
CREATE POLICY "Org members can view org devices" ON public.devices
FOR SELECT TO authenticated
USING (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids()));

DROP POLICY IF EXISTS "Org members can view org products" ON public.products;
CREATE POLICY "Org members can view org products" ON public.products
FOR SELECT TO authenticated
USING (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids()));

DROP POLICY IF EXISTS "Org admins can edit org products" ON public.products;
CREATE POLICY "Org admins can edit org products" ON public.products
FOR INSERT TO authenticated
WITH CHECK (public.has_capability('admin') AND (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids())));

DROP POLICY IF EXISTS "Org admins can update org products" ON public.products;
CREATE POLICY "Org admins can update org products" ON public.products
FOR UPDATE TO authenticated
USING (public.has_capability('admin') AND (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids())));

DROP POLICY IF EXISTS "Org members can view org app configurations" ON public.app_configurations;
CREATE POLICY "Org members can view org app configurations" ON public.app_configurations
FOR SELECT TO authenticated
USING (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids()));

DROP POLICY IF EXISTS "Org admins can publish app configurations" ON public.app_configurations;
CREATE POLICY "Org admins can publish app configurations" ON public.app_configurations
FOR INSERT TO authenticated
WITH CHECK (public.has_capability('admin') AND (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids())));

DROP POLICY IF EXISTS "Org members can view org coupons" ON public.coupons;
CREATE POLICY "Org members can view org coupons" ON public.coupons
FOR SELECT TO authenticated
USING (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids()));

DROP POLICY IF EXISTS "Org admins can create org coupons" ON public.coupons;
CREATE POLICY "Org admins can create org coupons" ON public.coupons
FOR INSERT TO authenticated
WITH CHECK (public.has_capability('admin') AND (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids())));

DROP POLICY IF EXISTS "Org admins can update org coupons" ON public.coupons;
CREATE POLICY "Org admins can update org coupons" ON public.coupons
FOR UPDATE TO authenticated
USING (public.has_capability('admin') AND (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids())));

-- vip_cards: additive to "Devices can see own org vip cards" above (that one
-- scopes a device's own anonymous session via device_auth_map; this scopes a
-- human portal login via org_members) -- lets the CMP's VIP card page list
-- real vip_cards rows instead of only the unrelated jc_vip_cards table.
-- balance_cents itself stays unwritable by any client role (INSERT/UPDATE/
-- DELETE revoked, see §6/§8) -- only admin_create_vip_card()/
-- admin_topup_vip_card() below can change it or create a row.
DROP POLICY IF EXISTS "Org members can view org vip cards" ON public.vip_cards;
CREATE POLICY "Org members can view org vip cards" ON public.vip_cards
FOR SELECT TO authenticated
USING (public.is_sys_admin() OR org_id IN (SELECT public.member_org_ids()));

-- transactions/device_commands only carry device_sn, not org_id directly --
-- scoped via a join through devices.org_id instead of member_org_ids()
-- directly.
DROP POLICY IF EXISTS "Org members can view org transactions" ON public.transactions;
CREATE POLICY "Org members can view org transactions" ON public.transactions
FOR SELECT TO authenticated
USING (
  public.is_sys_admin() OR
  device_sn IN (SELECT sn FROM public.devices WHERE org_id IN (SELECT public.member_org_ids()))
);

DROP POLICY IF EXISTS "Org members can view org device commands" ON public.device_commands;
CREATE POLICY "Org members can view org device commands" ON public.device_commands
FOR SELECT TO authenticated
USING (
  public.is_sys_admin() OR
  device_sn IN (SELECT sn FROM public.devices WHERE org_id IN (SELECT public.member_org_ids()))
);

DROP POLICY IF EXISTS "Org admins can send device commands" ON public.device_commands;
CREATE POLICY "Org admins can send device commands" ON public.device_commands
FOR INSERT TO authenticated
WITH CHECK (
  public.has_capability('admin') AND (
    public.is_sys_admin() OR
    device_sn IN (SELECT sn FROM public.devices WHERE org_id IN (SELECT public.member_org_ids()))
  )
);

-- coupons/app_configurations had table-level grants fully or partially
-- revoked (see §6) back when nothing but SECURITY DEFINER RPCs and the
-- device's own session touched them -- re-grant what the portal policies
-- above now need. RLS (above) still does the actual per-row gating; these
-- GRANTs just stop it being blocked one layer earlier.
GRANT SELECT, INSERT, UPDATE ON public.coupons TO authenticated;
GRANT INSERT ON public.app_configurations TO authenticated;

-- device_auth_map: a device can read its OWN row. This was missing entirely
-- -- found live 2026-07-24 testing the coupon feature on an emulator, but
-- it's not a coupon-specific bug: this Supabase project auto-enables RLS on
-- every new public-schema table (confirmed via pg_class.relrowsecurity --
-- true for every table in this file, including ones like vip_cards/
-- app_configurations that were never given an explicit ENABLE ROW LEVEL
-- SECURITY here), and RLS-enabled-with-zero-policies is default-deny, not
-- "fall through to plain GRANTs". Without this policy, EVERY other table's
-- policy that subqueries device_auth_map (products, heartbeats,
-- app_error_logs, device_shadows, transactions, qr_payment_sessions --
-- effectively all of §8) silently returns zero rows for every device,
-- because that subquery itself runs under the caller's own RLS-restricted
-- view of device_auth_map. Verified live: before this policy existed, a
-- real device querying its own device_auth_map row, or products in its own
-- org, both returned empty despite matching rows actually existing.
DROP POLICY IF EXISTS "devices can read own auth map row" ON public.device_auth_map;
CREATE POLICY "devices can read own auth map row" ON public.device_auth_map
FOR SELECT TO authenticated
USING (auth_user_id = auth.uid());

-- vip_cards / app_configurations: both were designed assuming "no RLS,
-- SELECT via plain table GRANT" (see the REVOKE-only comments on these two
-- tables above) -- wrong on this project for the same reason as
-- device_auth_map above (RLS is force-enabled regardless). Scoped by org
-- via device_auth_map, same pattern as "Devices can see own org products"
-- below -- a device from one org must not be able to look up another org's
-- VIP cards or pull another org's config.
DROP POLICY IF EXISTS "Devices can see own org vip cards" ON public.vip_cards;
CREATE POLICY "Devices can see own org vip cards" ON public.vip_cards
FOR SELECT TO authenticated
USING (
  org_id IN (
    SELECT org_id FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

DROP POLICY IF EXISTS "Devices can see own org app configurations" ON public.app_configurations;
CREATE POLICY "Devices can see own org app configurations" ON public.app_configurations
FOR SELECT TO authenticated
USING (
  org_id IN (
    SELECT org_id FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- advertisements: no org_id column at all (global media library, not
-- tenant-scoped by design) -- open SELECT for any authenticated device.
DROP POLICY IF EXISTS "Authenticated devices can read advertisements" ON public.advertisements;
CREATE POLICY "Authenticated devices can read advertisements" ON public.advertisements
FOR SELECT TO authenticated
USING (true);

-- playlists: device-scoped (not org-scoped) -- a device only needs its own
-- playlist mapping.
DROP POLICY IF EXISTS "Devices can see own playlist" ON public.playlists;
CREATE POLICY "Devices can see own playlist" ON public.playlists
FOR SELECT TO authenticated
USING (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- device_commands: RemoteCommandManager both subscribes via Realtime
-- (which enforces the same RLS as a direct SELECT) and UPDATEs status after
-- executing a command -- needs both, scoped to the device's own commands.
DROP POLICY IF EXISTS "Devices can see own commands" ON public.device_commands;
CREATE POLICY "Devices can see own commands" ON public.device_commands
FOR SELECT TO authenticated
USING (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);
DROP POLICY IF EXISTS "Devices can update own command status" ON public.device_commands;
CREATE POLICY "Devices can update own command status" ON public.device_commands
FOR UPDATE TO authenticated
USING (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

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
-- keys are revoked so a decompiled APK can't PATCH arbitrary balances.
-- SELECT is handled by the "Devices can see own org vip cards" RLS policy
-- above (§8), not by a plain GRANT -- this table WAS assumed RLS-free
-- ("plain REVOKE is sufficient") until live testing 2026-07-24 showed RLS
-- is force-enabled on this project regardless, which made SELECT return
-- nothing at all until that policy was added.
REVOKE UPDATE, INSERT, DELETE ON public.vip_cards FROM anon, authenticated;

-- App Configurations: same gap as vip_cards had, found during a review of
-- docs/cloud_management_platform_design.md -- this table was never locked
-- down (no REVOKE), so any authenticated device session could
-- INSERT/UPDATE arbitrary rows here, including config belonging to a
-- different org_id (no per-tenant write policy exists to stop it either).
-- ConfigManager only ever SELECTs this table from the app (via the "Devices
-- can see own org app configurations" RLS policy above, §8); nothing on the
-- device side writes to it, so revoking write access breaks nothing.
-- Publishing a new version (once that tooling exists, see
-- docs/cloud_management_platform_design.md 3.2) must go through
-- service_role or a dedicated SECURITY DEFINER RPC, never a device's own
-- authenticated session.
REVOKE UPDATE, INSERT, DELETE ON public.app_configurations FROM anon, authenticated;

-- Coupons / coupon_redemptions: unlike vip_cards, nothing in the app reads
-- these tables directly -- every interaction goes through the
-- redeem_coupon() SECURITY DEFINER RPC below (see
-- docs/coupon_redemption_integration.md §3), so there's no need to leave
-- SELECT open the way vip_cards does for VipRepository.getVipCard(). RLS
-- enabled with no policies (default-deny) plus an explicit REVOKE ALL is
-- belt-and-suspenders against both the table-level grant and a future
-- accidental policy add.
ALTER TABLE public.coupons ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.coupon_redemptions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.coupons FROM anon, authenticated;
REVOKE ALL ON public.coupon_redemptions FROM anon, authenticated;

-- Profiles / org_members: SELECT stays open at the table-privilege level so
-- the "own row only" RLS policies above can actually apply (REVOKE SELECT
-- would block that regardless of policy) -- same pattern as vip_cards.
-- Writes are fully revoked: profiles is only ever written by
-- handle_new_profile() (auth.users trigger, §9); org_members role grants are
-- a manual/service_role bootstrap step for MVP (see seed-data comment below).
REVOKE UPDATE, INSERT, DELETE ON public.profiles, public.org_members FROM anon, authenticated;

-- audit_logs: no policies at all (default-deny) -- nothing in the app or
-- portal reads this directly yet (no audit UI built), only SECURITY DEFINER
-- RPCs write to it. Same belt-and-suspenders REVOKE ALL as coupons above.
REVOKE ALL ON public.audit_logs FROM anon, authenticated;

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

CREATE TRIGGER on_profiles_update
BEFORE UPDATE ON public.profiles
FOR EACH ROW
EXECUTE FUNCTION public.touch_updated_at();

-- Function: auto-provision a public.profiles row for every new Supabase Auth
-- human sign-up (standard Supabase pattern). Nothing else in this schema
-- populates profiles -- without this trigger, a real login would create an
-- auth.users row with no matching profiles row, and every FK depending on
-- profiles (coupons.issued_by_profile_id, org_members.profile_id,
-- audit_logs.actor_profile_id) would have nothing to reference. SECURITY
-- DEFINER because the auth.users insert happens under Supabase's internal
-- auth role, which has no direct grant on public.profiles.
--
-- MUST skip anonymous sign-ins (IF NEW.is_anonymous below): every IM30
-- device authenticates anonymously (auth.users.email IS NULL for those
-- rows -- see the comment above the RLS policies in §8), but profiles.email
-- is NOT NULL. Without this guard, every new anonymous device session
-- fails the profiles insert, which as an AFTER INSERT trigger rolls back
-- the anonymous auth.users insert itself -- confirmed live 2026-07-24 on
-- an emulator run: SupabaseClientProvider's anonymous sign-in broke with
-- "Database error creating anonymous user" the first time this trigger
-- shipped without the guard. Anonymous sessions have no human identity to
-- record here anyway, so skipping them is also the semantically correct
-- behavior, not just a workaround.
CREATE OR REPLACE FUNCTION public.handle_new_profile()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF NEW.is_anonymous THEN
    RETURN NEW;
  END IF;
  INSERT INTO public.profiles (id, email) VALUES (NEW.id, NEW.email);
  RETURN NEW;
END;
$$;

CREATE TRIGGER on_auth_user_created
AFTER INSERT ON auth.users
FOR EACH ROW
EXECUTE FUNCTION public.handle_new_profile();

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

-- Function: Atomically check-and-redeem a coupon, in the same style as
-- deduct_vip_balance() above -- FOR UPDATE row lock so two near-simultaneous
-- redemptions of the same code (screenshot forwarded to two people, printed
-- voucher photocopied) can't both succeed. This is the ONLY path allowed to
-- modify coupons.uses_count / write coupon_redemptions (see REVOKE above).
-- Only 'authenticated' is granted EXECUTE (not 'anon' like
-- deduct_vip_balance) since by the time this is reachable the app has
-- already completed its anonymous sign-in (see SupabaseClientProvider.kt).
CREATE OR REPLACE FUNCTION public.redeem_coupon(p_code TEXT, p_device_sn TEXT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_coupon RECORD;
  v_device_org_id UUID;
BEGIN
  SELECT org_id INTO v_device_org_id FROM public.devices WHERE sn = p_device_sn;
  IF NOT FOUND THEN
    RETURN json_build_object('success', false, 'message', 'device_not_registered');
  END IF;

  SELECT * INTO v_coupon FROM public.coupons WHERE code = p_code FOR UPDATE;

  IF NOT FOUND THEN
    RETURN json_build_object('success', false, 'message', 'not_found');
  END IF;
  IF NOT v_coupon.is_active THEN
    RETURN json_build_object('success', false, 'message', 'inactive');
  END IF;
  IF v_coupon.expires_at IS NOT NULL AND v_coupon.expires_at < now() THEN
    RETURN json_build_object('success', false, 'message', 'expired');
  END IF;
  IF v_coupon.uses_count >= v_coupon.max_uses THEN
    RETURN json_build_object('success', false, 'message', 'already_used');
  END IF;
  IF v_coupon.org_id != v_device_org_id THEN -- no cross-tenant coupons; org_id is NOT NULL on both sides
    RETURN json_build_object('success', false, 'message', 'wrong_org');
  END IF;

  UPDATE public.coupons SET uses_count = uses_count + 1 WHERE code = p_code;
  INSERT INTO public.coupon_redemptions (coupon_code, device_sn) VALUES (p_code, p_device_sn);

  RETURN json_build_object(
    'success', true,
    'type', v_coupon.type,
    'value', v_coupon.value,
    'applicable_product_id', v_coupon.applicable_product_id
  );
END;
$$;

REVOKE ALL ON FUNCTION public.redeem_coupon(TEXT, TEXT) FROM public;
GRANT EXECUTE ON FUNCTION public.redeem_coupon(TEXT, TEXT) TO authenticated;

-- Function: operationalizes docs/cloud_management_platform_design.md
-- §3.3.1's "Service Compensation (One-Click)" workflow -- a MERCHANT_ADMIN
-- (or SYS_ADMIN) issues a compensation coupon, typically against a specific
-- failed transaction spotted in the Dynamic Transaction Monitor. Authorization
-- is checked here, not via table-level RLS on coupons (see the RLS section
-- comment above org_members' policies for why) -- this is the only path
-- allowed to write coupons.issued_by_profile_id with a real value. The
-- coupon code is generated server-side (pgcrypto, already enabled via the
-- extension at the top of this file) rather than accepted from the caller,
-- per docs/coupon_redemption_integration.md §4.2's "must be unpredictable,
-- not guessable" requirement.
CREATE OR REPLACE FUNCTION public.issue_compensation_coupon(
  p_org_id UUID,
  p_value_cents INT,
  p_max_uses INT DEFAULT 1,
  p_related_transaction_id UUID DEFAULT NULL
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_code TEXT;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;

  IF p_value_cents <= 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.org_members
    WHERE profile_id = auth.uid() AND capability = 'admin' AND (
      role = 'SYS_ADMIN' OR (role = 'MERCHANT_ADMIN' AND org_id = p_org_id)
    )
  ) THEN
    RETURN json_build_object('success', false, 'message', 'not_authorized');
  END IF;

  -- gen_random_uuid() (built into core Postgres 13+, always resolvable
  -- regardless of search_path) instead of pgcrypto's gen_random_bytes() --
  -- on Supabase, pgcrypto installs into the `extensions` schema, not
  -- `public`, so the unqualified call failed under this function's
  -- SET search_path = public (caught live during testing 2026-07-24:
  -- "function gen_random_bytes(integer) does not exist"). Same
  -- unpredictability requirement from docs/coupon_redemption_integration.md
  -- §4.2 is still met -- a v4 UUID has 122 bits of randomness, more than
  -- gen_random_bytes(8)'s 64.
  v_code := 'COMP-' || replace(gen_random_uuid()::text, '-', '');

  INSERT INTO public.coupons (
    code, org_id, type, value, max_uses, issued_reason, issued_by_profile_id, related_transaction_id
  ) VALUES (
    v_code, p_org_id, 'FIXED_OFF', p_value_cents, p_max_uses, 'COMPENSATION', auth.uid(), p_related_transaction_id
  );

  INSERT INTO public.audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (
    auth.uid(), p_org_id, 'ISSUE_COMPENSATION_COUPON', 'coupons', v_code,
    json_build_object('value_cents', p_value_cents, 'max_uses', p_max_uses, 'related_transaction_id', p_related_transaction_id)
  );

  RETURN json_build_object('success', true, 'code', v_code);
END;
$$;

REVOKE ALL ON FUNCTION public.issue_compensation_coupon(UUID, INT, INT, UUID) FROM public;
GRANT EXECUTE ON FUNCTION public.issue_compensation_coupon(UUID, INT, INT, UUID) TO authenticated;

-- Function: portal-side VIP card provisioning. Same authorization shape as
-- issue_compensation_coupon() above (capability='admin' AND (SYS_ADMIN OR
-- MERCHANT_ADMIN of p_org_id)) -- card_uid is the physical NFC card's own
-- serial (read by whatever card reader the front-desk uses to provision it),
-- supplied by the caller rather than generated, since it must match the
-- number actually encoded on the card. qr_code IS generated server-side, but
-- MUST be exactly 12 alphanumeric characters -- per
-- docs/coupon_redemption_integration.md §2.1, the IM30 scanner routes a scan
-- to the member-QR path purely by matching ^[A-Za-z0-9]{12}$ (coupon codes
-- are deliberately 16+ chars so the two never collide on length). Using
-- issue_compensation_coupon's gen_random_uuid()-based approach here would
-- produce a 35-char string that the client would silently misroute to
-- redeem_coupon() instead -- built from the same 36-char alphabet
-- cmpService.generateVipQrCode() already uses client-side for the same
-- format, not gen_random_bytes() (pgcrypto lives in the extensions schema,
-- not public, under this function's SET search_path).
CREATE OR REPLACE FUNCTION public.admin_create_vip_card(
  p_org_id UUID,
  p_card_uid TEXT,
  p_initial_balance_cents INT DEFAULT 0
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_qr_code TEXT;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;

  IF p_initial_balance_cents < 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.org_members
    WHERE profile_id = auth.uid() AND capability = 'admin' AND (
      role = 'SYS_ADMIN' OR (role = 'MERCHANT_ADMIN' AND org_id = p_org_id)
    )
  ) THEN
    RETURN json_build_object('success', false, 'message', 'not_authorized');
  END IF;

  -- floor(), not a bare ::int cast -- Postgres rounds a float->int cast to
  -- the nearest integer rather than truncating, so ::int alone occasionally
  -- yields 36 (when random()*36 lands in [35.5, 36)), an out-of-range substr
  -- position that silently returns NULL and gets dropped by string_agg,
  -- producing an 11-char code (caught live: "HNYN0F93N3Y" before this fix).
  SELECT string_agg(
    substr('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789', floor(random() * 36)::int + 1, 1), ''
  ) INTO v_qr_code FROM generate_series(1, 12);

  BEGIN
    INSERT INTO public.vip_cards (card_uid, org_id, balance_cents, is_active, qr_code)
    VALUES (p_card_uid, p_org_id, p_initial_balance_cents, true, v_qr_code);
  EXCEPTION WHEN unique_violation THEN
    RETURN json_build_object('success', false, 'message', 'card_uid_or_qr_code_exists');
  END;

  INSERT INTO public.audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), p_org_id, 'CREATE_VIP_CARD', 'vip_cards', p_card_uid,
    json_build_object('initial_balance_cents', p_initial_balance_cents));

  RETURN json_build_object('success', true, 'card_uid', p_card_uid, 'qr_code', v_qr_code);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_create_vip_card(UUID, TEXT, INT) FROM public;
GRANT EXECUTE ON FUNCTION public.admin_create_vip_card(UUID, TEXT, INT) TO authenticated;

-- Function: portal-side balance top-up (the admin-facing counterpart to
-- deduct_vip_balance() -- that one is the kiosk spending a card down, this is
-- staff crediting one up, e.g. a manual reload or goodwill credit). Looks up
-- org_id from the card itself (the caller doesn't supply it) so authorization
-- can't be spoofed by passing a different org_id than the card actually
-- belongs to. FOR UPDATE row lock, same reasoning as deduct_vip_balance --
-- a top-up racing a kiosk deduction on the same card must not lose an update.
CREATE OR REPLACE FUNCTION public.admin_topup_vip_card(p_card_uid TEXT, p_amount_cents INT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_org_id UUID;
  v_new_balance INT;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;

  IF p_amount_cents <= 0 THEN
    RETURN json_build_object('success', false, 'message', 'invalid_amount');
  END IF;

  SELECT org_id INTO v_org_id FROM public.vip_cards WHERE card_uid = p_card_uid FOR UPDATE;
  IF v_org_id IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.org_members
    WHERE profile_id = auth.uid() AND capability = 'admin' AND (
      role = 'SYS_ADMIN' OR (role = 'MERCHANT_ADMIN' AND org_id = v_org_id)
    )
  ) THEN
    RETURN json_build_object('success', false, 'message', 'not_authorized');
  END IF;

  UPDATE public.vip_cards SET balance_cents = balance_cents + p_amount_cents
  WHERE card_uid = p_card_uid
  RETURNING balance_cents INTO v_new_balance;

  INSERT INTO public.audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), v_org_id, 'TOPUP_VIP_CARD', 'vip_cards', p_card_uid,
    json_build_object('amount_cents', p_amount_cents, 'new_balance_cents', v_new_balance));

  RETURN json_build_object('success', true, 'new_balance_cents', v_new_balance);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_topup_vip_card(TEXT, INT) FROM public;
GRANT EXECUTE ON FUNCTION public.admin_topup_vip_card(TEXT, INT) TO authenticated;

-- Function: activate/deactivate a VIP card from the portal (lost-card
-- freeze, reissue, etc.) -- same authorization + audit-log shape as the two
-- functions above. is_active isn't money, but it's still gated through a
-- function rather than a direct UPDATE grant for consistency with the rest
-- of this table (all writes to vip_cards go through one of these three RPCs,
-- never a raw client UPDATE).
CREATE OR REPLACE FUNCTION public.admin_set_vip_card_status(p_card_uid TEXT, p_is_active BOOLEAN)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_org_id UUID;
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'not_authenticated');
  END IF;

  SELECT org_id INTO v_org_id FROM public.vip_cards WHERE card_uid = p_card_uid FOR UPDATE;
  IF v_org_id IS NULL THEN
    RETURN json_build_object('success', false, 'message', 'card_not_found');
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.org_members
    WHERE profile_id = auth.uid() AND capability = 'admin' AND (
      role = 'SYS_ADMIN' OR (role = 'MERCHANT_ADMIN' AND org_id = v_org_id)
    )
  ) THEN
    RETURN json_build_object('success', false, 'message', 'not_authorized');
  END IF;

  UPDATE public.vip_cards SET is_active = p_is_active WHERE card_uid = p_card_uid;

  INSERT INTO public.audit_logs (actor_profile_id, org_id, action, target_table, target_id, details)
  VALUES (auth.uid(), v_org_id, 'SET_VIP_CARD_STATUS', 'vip_cards', p_card_uid,
    json_build_object('is_active', p_is_active));

  RETURN json_build_object('success', true);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_set_vip_card_status(TEXT, BOOLEAN) FROM public;
GRANT EXECUTE ON FUNCTION public.admin_set_vip_card_status(TEXT, BOOLEAN) TO authenticated;

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

-- 12-char member QR code for the same card, so the scan-to-identify path
-- (docs/coupon_redemption_integration.md §2.1) has a real row to resolve.
UPDATE public.vip_cards SET qr_code = 'MBRQR6789ABC' WHERE card_uid = 'VIP_CARD_UID_6789';

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

-- 17. Sample coupons -- codes deliberately NOT 12 alphanumeric characters
-- (some contain hyphens, others are a different length) so they can never
-- collide with the 12-char member-QR-code format's routing regex
-- (docs/coupon_redemption_integration.md §2.1). All applicable_product_id
-- NULL ("any package") -- see the client-side matching gap noted in
-- MainActivity's coupon handling for why a product-restricted coupon isn't
-- seeded here yet. Org 1 / WASH vertical, matching PAX-IM30-WASH-001 and
-- the "starter" package ($4.00) from the app_configurations seed above.
INSERT INTO public.coupons (code, org_id, type, value, max_uses, uses_count, expires_at, issued_reason, is_active)
VALUES
-- 20% off any package.
('DEMO-PCT20-COUPON', '00000000-0000-0000-0000-000000000001', 'PERCENT_OFF', 20, 1, 0, NULL, 'PROMOTION', true),
-- Compensation voucher covering exactly the $4.00 "starter" package -- redeeming
-- this and picking Starter should reach finalPriceCents == 0 (free wash path).
('DEMO-FIXED4-COUPON', '00000000-0000-0000-0000-000000000001', 'FIXED_OFF', 400, 1, 0, NULL, 'COMPENSATION', true),
-- Already at max_uses -- exercises the 'already_used' rejection path.
('DEMO-USED-COUPON', '00000000-0000-0000-0000-000000000001', 'FIXED_OFF', 200, 1, 1, NULL, 'PROMOTION', true),
-- Expired yesterday -- exercises the 'expired' rejection path.
('DEMO-EXPIRED-COUPON', '00000000-0000-0000-0000-000000000001', 'PERCENT_OFF', 50, 1, 0, now() - INTERVAL '1 day', 'PROMOTION', true)
ON CONFLICT (code) DO NOTHING;

-- 18. profiles/org_members are intentionally NOT seeded here -- same reason
-- as §13's device_auth_map: profiles.id has a REFERENCES auth.users(id)
-- foreign key, and there's no way to fabricate a placeholder UUID that
-- satisfies it. A profiles row only exists once a real human signs up
-- (auto-created by the on_auth_user_created trigger, §9). To test
-- issue_compensation_coupon() manually, sign up a real user, then:
--   insert into public.org_members (profile_id, org_id, role)
--   values ('<real-auth-uid>', '00000000-0000-0000-0000-000000000001', 'MERCHANT_ADMIN');
-- (org_id NULL + role 'SYS_ADMIN' for a global admin instead.)
