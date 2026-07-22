-- Ensures the admin "kill switch" column exists on devices. Already
-- documented in docs/database_design.md; this migration just makes sure it's
-- actually present so DeviceRepository.checkDeviceActive() has something to read.
alter table if exists public.devices
  add column if not exists is_active boolean not null default true;
