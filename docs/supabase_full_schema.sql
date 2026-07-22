-- =============================================================================
-- GS-SSP Supabase (PostgreSQL) Full Database Schema v2.0
-- Unified Technology Platform for Smart Industries
-- =============================================================================

-- 0. INITIAL SETUP
-- Enable UUID extension if not enabled
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

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
    vertical_type TEXT DEFAULT 'WASH' CHECK (vertical_type IN ('WASH', 'LAUNDRY', 'EV', 'VEND')),
    status TEXT DEFAULT 'ONLINE',
    app_version TEXT,
    config_version TEXT,
    is_active BOOLEAN DEFAULT true,
    last_seen TIMESTAMPTZ DEFAULT now(),
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Generic Product Catalog (Billing Units)
CREATE TABLE IF NOT EXISTS public.products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID REFERENCES public.organizations(id) ON DELETE CASCADE,
    vertical_type TEXT NOT NULL,
    name TEXT NOT NULL,
    price_cents INTEGER NOT NULL,
    attributes JSONB DEFAULT '{}',       -- Hardware-specific: { "serial_hex": "AA...", "pulse": 12 }
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Global App Configuration (Legacy/Fallback)
CREATE TABLE IF NOT EXISTS public.app_configurations (
    version TEXT PRIMARY KEY,
    payload JSONB NOT NULL,
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
    device_sn TEXT REFERENCES public.devices(sn) ON DELETE CASCADE,
    ad_id UUID REFERENCES public.advertisements(id) ON DELETE CASCADE,
    play_order INTEGER DEFAULT 0
);

-- 4. TELEMETRY & DIAGNOSTICS
-- High-frequency Heartbeats
CREATE TABLE IF NOT EXISTS public.heartbeats (
    id BIGSERIAL PRIMARY KEY,
    device_sn TEXT REFERENCES public.devices(sn) ON DELETE CASCADE,
    is_serial_ok BOOLEAN,
    storage_free_mb BIGINT,
    network_type TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Application Error Logs (Stack Traces)
CREATE TABLE IF NOT EXISTS public.app_error_logs (
    id BIGSERIAL PRIMARY KEY,
    device_sn TEXT REFERENCES public.devices(sn) ON DELETE CASCADE,
    severity TEXT DEFAULT 'ERROR',
    error_code TEXT,
    stack_trace TEXT,
    context JSONB DEFAULT '{}',          -- Snapshot of device state
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Maintenance Action Trail
CREATE TABLE IF NOT EXISTS public.maintenance_records (
    id BIGSERIAL PRIMARY KEY,
    device_sn TEXT REFERENCES public.devices(sn) ON DELETE CASCADE,
    action TEXT NOT NULL,                -- e.g., 'RELAY_TEST', 'REBOOT'
    payload JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Device Shadows (Digital Twin)
CREATE TABLE IF NOT EXISTS public.device_shadows (
    device_sn TEXT PRIMARY KEY REFERENCES public.devices(sn) ON DELETE CASCADE,
    desired JSONB DEFAULT '{}',          -- Cloud requested state
    reported JSONB DEFAULT '{}',         -- Device reported state
    version INTEGER DEFAULT 1,
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- 5. TRANSACTIONAL DATA
CREATE TABLE IF NOT EXISTS public.transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_sn TEXT REFERENCES public.devices(sn) ON DELETE SET NULL,
    amount INTEGER NOT NULL,
    currency TEXT DEFAULT 'USD',
    payment_status TEXT CHECK (payment_status IN ('PAID', 'DECLINED', 'VOIDED', 'REFUNDED')),
    hardware_status TEXT,                -- ACK_RECEIVED / TIMEOUT
    auth_code TEXT,
    ecr_ref_num TEXT UNIQUE,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 6. SECURITY & MULTI-TENANCY BRIDGE
-- Links Supabase Anonymous Auth Users to Organizations/Devices
CREATE TABLE IF NOT EXISTS public.device_auth_map (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE UNIQUE,
    device_sn TEXT REFERENCES public.devices(sn) ON DELETE CASCADE,
    org_id UUID REFERENCES public.organizations(id) ON DELETE CASCADE,
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

-- 8. DEFINE RLS POLICIES (Device Isolation)

-- Products: Devices see only their organization's products
CREATE POLICY "Devices can see own org products" ON public.products
FOR SELECT TO authenticated
USING (
  org_id IN (
    SELECT org_id FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- Heartbeats: Devices can only report for themselves
CREATE POLICY "Devices can insert own heartbeats" ON public.heartbeats
FOR INSERT TO authenticated
WITH CHECK (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

-- Error Logs: Devices can only report for themselves
CREATE POLICY "Devices can insert own error logs" ON public.app_error_logs
FOR INSERT TO authenticated
WITH CHECK (
  device_sn IN (
    SELECT device_sn FROM public.device_auth_map
    WHERE auth_user_id = auth.uid()
  )
);

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

CREATE TABLE public.device_commands (
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

-- 3. Insert Sample Devices (IM30)
INSERT INTO public.devices (sn, loc_id, vertical_type, app_version, config_version)
VALUES
('PAX-IM30-WASH-001', '00000000-0000-0000-0000-000000000003', 'WASH', 'v1.0.0', '2026.07.21.01'),
('PAX-IM30-LAUN-002', '00000000-0000-0000-0000-000000000004', 'LAUNDRY', 'v1.0.0', '2026.07.21.01')
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

-- 8. Add initial transaction records
INSERT INTO public.transactions (device_sn, amount, payment_status, hardware_status, ecr_ref_num)
VALUES
('PAX-IM30-WASH-001', 800, 'PAID', 'ACK_RECEIVED', 'REF-WASH-001'),
('PAX-IM30-LAUN-002', 350, 'PAID', 'ACK_RECEIVED', 'REF-LAUN-002')
ON CONFLICT (ecr_ref_num) DO NOTHING;

-- 9. Insert Sample Device Auth Mappings (Dummy Auth IDs)
-- Replace these UUIDs with real ones from 'auth.users' after devices log in.
INSERT INTO public.device_auth_map (auth_user_id, device_sn, org_id)
VALUES
('7fd37d3a-ab4a-47df-b069-6f559297ae04', 'PAX-IM30-WASH-001', '00000000-0000-0000-0000-000000000001'),
('3c1f7bb5-170b-4443-ac59-0483f67d8b61', 'PAX-IM30-LAUN-002', '00000000-0000-0000-0000-000000000002')
ON CONFLICT (auth_user_id) DO NOTHING;

-- NOTE: To test RLS, you must manually insert a record into 'device_auth_map'
-- linking a real 'auth.uid()' from your Supabase Auth list to 'PAX-IM30-TEST-001'.
