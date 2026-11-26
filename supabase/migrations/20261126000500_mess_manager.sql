-- ধাপ ১২ (ঙ-২): মেস ম্যানেজার — সদস্য সক্রিয়/যোগ-ছাড়ার তারিখ/ফোন/ম্যানেজার, মিলে স্লট ও অতিথি, ফিক্সড খরচ, মাস বন্ধ।

alter table public.mess_members
  add column if not exists is_active boolean not null default true,
  add column if not exists joined_on date,
  add column if not exists left_on date,
  add column if not exists phone text not null default '',
  add column if not exists is_manager boolean not null default false;

alter table public.mess_meals
  add column if not exists slot text not null default 'day',
  add column if not exists guest_count numeric(6,2) not null default 0 check (guest_count >= 0);

create table if not exists public.mess_fixed_costs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  amount numeric(14,2) not null check (amount >= 0),
  cost_date date not null default current_date,
  split_mode text not null default 'equal' check (split_mode in ('equal', 'days')),
  created_at timestamptz not null default now()
);

create table if not exists public.mess_months (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  month text not null,
  rate numeric(14,4) not null default 0,
  final_balances jsonb not null default '{}'::jsonb,
  closed_at timestamptz not null default now(),
  unique (user_id, month)
);

alter table public.mess_fixed_costs enable row level security;
alter table public.mess_months enable row level security;

drop policy if exists "own rows" on public.mess_fixed_costs;
create policy "own rows" on public.mess_fixed_costs for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "own rows" on public.mess_months;
create policy "own rows" on public.mess_months for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
