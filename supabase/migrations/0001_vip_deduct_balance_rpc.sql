-- Moves VIP balance deduction server-side. The IM30 client previously read a
-- card's balance, checked it locally, then PATCHed vip_cards directly using
-- the public anon key -- anyone who extracted the anon key from the APK
-- (trivial: it's in BuildConfig) could PATCH arbitrary balances themselves.
--
-- This RPC does the check-and-deduct atomically, inside a single transaction
-- with a row lock, so concurrent taps on the same card can't double-spend.
-- Apply with: supabase db push  (or paste into the SQL editor)

create or replace function public.deduct_vip_balance(p_card_uid text, p_amount_cents int)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
  v_balance numeric;
  v_active boolean;
  v_amount numeric := p_amount_cents / 100.0;
begin
  if p_amount_cents <= 0 then
    return json_build_object('success', false, 'message', 'invalid_amount');
  end if;

  select balance, is_active into v_balance, v_active
  from vip_cards
  where card_uid = p_card_uid
  for update;

  if v_balance is null then
    return json_build_object('success', false, 'message', 'card_not_found');
  end if;

  if not v_active then
    return json_build_object('success', false, 'message', 'card_inactive');
  end if;

  if v_balance < v_amount then
    return json_build_object('success', false, 'message', 'insufficient_balance');
  end if;

  update vip_cards set balance = balance - v_amount where card_uid = p_card_uid;

  return json_build_object('success', true, 'new_balance', v_balance - v_amount);
end;
$$;

-- Only callable via RPC (SECURITY DEFINER bypasses RLS internally); the
-- underlying table no longer accepts direct writes from client-side keys.
revoke all on function public.deduct_vip_balance(text, int) from public;
grant execute on function public.deduct_vip_balance(text, int) to anon, authenticated;

revoke update, insert, delete on public.vip_cards from anon, authenticated;
