-- Admin panel support (owner only). The browser never calls these functions directly:
-- the admin-api Edge Function checks the owner's login, then calls them with the service role.
alter table public.profiles add column if not exists last_seen_at timestamptz;

create or replace function public.admin_stats()
returns jsonb language sql stable security definer set search_path = public, auth as $$
  select jsonb_build_object(
    'total_users', (select count(*) from auth.users),
    'new_today', (select count(*) from auth.users
                  where created_at >= (date_trunc('day', now() at time zone 'Asia/Dhaka') at time zone 'Asia/Dhaka')),
    'new_week', (select count(*) from auth.users where created_at >= now() - interval '7 days'),
    'new_month', (select count(*) from auth.users where created_at >= now() - interval '30 days'),
    'active_24h', (select count(*) from public.profiles where last_seen_at >= now() - interval '24 hours'),
    'active_7d', (select count(*) from public.profiles where last_seen_at >= now() - interval '7 days'),
    'total_transactions', (select count(*) from public.transactions)
  );
$$;

-- Metadata only: no transaction details are returned, only a count.
create or replace function public.admin_users(p_search text default '', p_limit int default 50, p_offset int default 0)
returns table (id uuid, email text, name text, joined_at timestamptz, last_seen_at timestamptz,
               last_sign_in_at timestamptz, tx_count bigint)
language sql stable security definer set search_path = public, auth as $$
  select u.id, u.email::text, p.name, u.created_at, p.last_seen_at, u.last_sign_in_at,
         (select count(*) from public.transactions t where t.user_id = u.id)
  from auth.users u
  left join public.profiles p on p.id = u.id
  where coalesce(p_search, '') = ''
     or position(lower(p_search) in lower(coalesce(u.email, ''))) > 0
     or position(lower(p_search) in lower(coalesce(p.name, ''))) > 0
  order by u.created_at desc
  limit least(greatest(p_limit, 1), 200) offset greatest(p_offset, 0);
$$;

create or replace function public.admin_find_user_id(p_email text)
returns uuid language sql stable security definer set search_path = public, auth as $$
  select id from auth.users where lower(email) = lower(p_email) limit 1;
$$;

revoke all on function public.admin_stats() from public, anon, authenticated;
revoke all on function public.admin_users(text, int, int) from public, anon, authenticated;
revoke all on function public.admin_find_user_id(text) from public, anon, authenticated;
grant execute on function public.admin_stats() to service_role;
grant execute on function public.admin_users(text, int, int) to service_role;
grant execute on function public.admin_find_user_id(text) to service_role;
