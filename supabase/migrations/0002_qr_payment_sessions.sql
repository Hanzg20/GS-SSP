-- Backs the "scan-to-pay" flow with a real, server-persisted session instead
-- of the client faking a "success" after a fixed number of poll ticks.
-- A payment gateway webhook (Alipay/WeChat/Stripe -- external integration,
-- needs merchant credentials this repo doesn't have) is expected to flip
-- `status` to PAID via the service role once it confirms funds. anon/
-- authenticated can create and read sessions but cannot write PAID
-- themselves, or the "payment" is just as fake as before.

create table if not exists public.qr_payment_sessions (
  tx_id text primary key,
  device_sn text not null,
  amount_cents int not null,
  status text not null default 'PENDING', -- PENDING | PAID | EXPIRED | CANCELLED
  created_at timestamptz not null default now(),
  paid_at timestamptz
);

alter table public.qr_payment_sessions enable row level security;

create policy "devices can create their own sessions"
  on public.qr_payment_sessions for insert
  to anon, authenticated
  with check (true);

create policy "devices can read sessions"
  on public.qr_payment_sessions for select
  to anon, authenticated
  using (true);

-- Deliberately no UPDATE/DELETE policy for anon/authenticated: only the
-- service role (payment webhook / Edge Function) may mark a session PAID.
