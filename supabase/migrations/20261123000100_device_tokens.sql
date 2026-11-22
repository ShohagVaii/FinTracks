-- FinTrack push: one row per device FCM token. Writes go only through the two functions below
-- (they are SECURITY DEFINER and always use auth.uid()), so a user can never write another user's token.
create table if not exists public.device_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  fcm_token text not null unique,
  platform text not null default 'android',
  updated_at timestamptz not null default now()
);
create index if not exists device_tokens_user_idx on public.device_tokens(user_id);

alter table public.device_tokens enable row level security;
create policy "device_tokens_select_own" on public.device_tokens
  for select to authenticated using (user_id = auth.uid());
create policy "device_tokens_delete_own" on public.device_tokens
  for delete to authenticated using (user_id = auth.uid());

create or replace function public.register_device_token(p_token text, p_platform text default 'android')
returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'not signed in' using errcode = '42501'; end if;
  if p_token is null or length(p_token) < 20 or length(p_token) > 4096 then raise exception 'invalid token'; end if;
  insert into public.device_tokens (user_id, fcm_token, platform, updated_at)
  values (auth.uid(), p_token, coalesce(nullif(p_platform, ''), 'android'), now())
  on conflict (fcm_token) do update
    set user_id = excluded.user_id, platform = excluded.platform, updated_at = now();
end $$;

create or replace function public.unregister_device_token(p_token text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'not signed in' using errcode = '42501'; end if;
  delete from public.device_tokens where fcm_token = p_token and user_id = auth.uid();
end $$;

revoke all on function public.register_device_token(text, text) from public;
revoke all on function public.unregister_device_token(text) from public;
-- Supabase grants EXECUTE to anon by default; revoke it explicitly.
revoke execute on function public.register_device_token(text, text) from anon;
revoke execute on function public.unregister_device_token(text) from anon;
grant execute on function public.register_device_token(text, text) to authenticated;
grant execute on function public.unregister_device_token(text) to authenticated;
