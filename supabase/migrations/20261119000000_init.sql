-- FinTracks / SritiSetu core schema (free-tier Supabase)
-- Every table is per-user and protected by Row Level Security.

create extension if not exists pgcrypto;

-- ---------- Profiles ----------
create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  name text not null default 'অতিথি ব্যবহারকারী',
  currency text not null default 'BDT',
  language text not null default 'bn' check (language in ('bn','en')),
  theme text not null default 'dark' check (theme in ('dark','light')),
  theme_color text not null default 'sky' check (theme_color in ('sky','emerald','rose','amber')),
  date_format text not null default 'DD-MM-YYYY',
  reminder_time text not null default '22:00',
  pin_hash text,                       -- SHA-256 hash, never the raw PIN
  is_privacy_mode boolean not null default false,
  created_at timestamptz not null default now()
);

-- ---------- Accounts (ক্যাশ, bKash, Nagad, ব্যাংক ...) ----------
create table if not exists public.accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  kind text not null check (kind in ('Cash','Bkash','Nagad','Rocket','Bank','Card')),
  opening_balance numeric(14,2) not null default 0,
  created_at timestamptz not null default now()
);

-- ---------- Categories ----------
create table if not exists public.categories (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  type text not null check (type in ('Income','Expense')),
  unique (user_id, name, type)
);

-- ---------- Transactions (আয়/ব্যয়/ট্রান্সফার) ----------
create table if not exists public.transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  account_id uuid references public.accounts(id) on delete set null,
  to_account_id uuid references public.accounts(id) on delete set null,
  type text not null check (type in ('Income','Expense','Transfer')),
  amount numeric(14,2) not null check (amount > 0),
  category text not null default 'Others',
  note text not null default '',
  tx_date date not null default current_date,
  payment_mode text not null default 'Cash',
  tags text[] not null default '{}',
  created_at timestamptz not null default now()
);
create index if not exists transactions_user_date on public.transactions (user_id, tx_date desc);

-- ---------- Budgets ----------
create table if not exists public.budgets (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  category text not null,
  monthly_limit numeric(14,2) not null check (monthly_limit >= 0),
  unique (user_id, category)
);

-- ---------- Goals (সঞ্চয় লক্ষ্য) ----------
create table if not exists public.goals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  target numeric(14,2) not null check (target > 0),
  saved numeric(14,2) not null default 0,
  deadline date
);

-- ---------- ধার ও পাওনা (টাকা ধার নেওয়া/দেওয়া) ----------
create table if not exists public.debts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  person text not null,
  amount numeric(14,2) not null check (amount > 0),
  direction text not null check (direction in ('owe','owed')), -- owe = আমি ধারি, owed = আমার পাওনা
  due_date date,
  settled boolean not null default false,
  note text not null default ''
);

-- ---------- লোন ----------
create table if not exists public.loans (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  principal numeric(14,2) not null check (principal > 0),
  rate numeric(6,3) not null default 0,
  term_months integer not null default 12 check (term_months > 0),
  start_date date not null default current_date,
  paid numeric(14,2) not null default 0
);

-- ---------- সাবস্ক্রিপশন ----------
create table if not exists public.subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  amount numeric(14,2) not null check (amount >= 0),
  frequency text not null default 'Monthly' check (frequency in ('Daily','Weekly','Monthly','Yearly')),
  renewal_date date,
  is_active boolean not null default true
);

-- ---------- ইনভেস্টমেন্ট / সম্পদ ----------
create table if not exists public.investments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  kind text not null check (kind in ('Stock','SavingsCertificate','Crypto','Gold','RealEstate','Other')),
  invested numeric(14,2) not null default 0,
  current_value numeric(14,2) not null default 0,
  bought_on date
);

-- ---------- বাকি: দোকান (যেখান থেকে ধারে কেনা হয়) ----------
create table if not exists public.credit_shops (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  phone text not null default '',
  note text not null default '',
  created_at timestamptz not null default now()
);

-- ---------- বাকি: কেনা আইটেম (মালদা, চাল ইত্যাদি) ----------
create table if not exists public.credit_entries (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  shop_id uuid not null references public.credit_shops(id) on delete cascade,
  item_name text not null,
  quantity numeric(12,3) not null default 1 check (quantity > 0),
  unit_price numeric(14,2) not null default 0 check (unit_price >= 0),
  total numeric(14,2) generated always as (round(quantity * unit_price, 2)) stored,
  purchase_date date not null default current_date,
  due_date date,
  note text not null default '',
  created_at timestamptz not null default now()
);
create index if not exists credit_entries_shop on public.credit_entries (shop_id, purchase_date desc);

-- ---------- বাকি: পরিশোধ ----------
create table if not exists public.credit_payments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  shop_id uuid not null references public.credit_shops(id) on delete cascade,
  amount numeric(14,2) not null check (amount > 0),
  paid_on date not null default current_date,
  note text not null default ''
);

-- Shop-wise running balance (বাকি বাকি কত আছে)
create or replace view public.credit_shop_balances with (security_invoker = true) as
select
  s.id as shop_id,
  s.user_id,
  s.name,
  coalesce((select sum(e.total) from public.credit_entries e where e.shop_id = s.id), 0)
    - coalesce((select sum(p.amount) from public.credit_payments p where p.shop_id = s.id), 0) as balance
from public.credit_shops s;

-- ---------- Row Level Security ----------
alter table public.profiles enable row level security;
alter table public.accounts enable row level security;
alter table public.categories enable row level security;
alter table public.transactions enable row level security;
alter table public.budgets enable row level security;
alter table public.goals enable row level security;
alter table public.debts enable row level security;
alter table public.loans enable row level security;
alter table public.subscriptions enable row level security;
alter table public.investments enable row level security;
alter table public.credit_shops enable row level security;
alter table public.credit_entries enable row level security;
alter table public.credit_payments enable row level security;

create policy "own profile" on public.profiles
  for all to authenticated using (id = auth.uid()) with check (id = auth.uid());

do $$
declare t text;
begin
  foreach t in array array['accounts','categories','transactions','budgets','goals','debts','loans',
                           'subscriptions','investments','credit_shops','credit_entries','credit_payments']
  loop
    execute format('create policy "own rows" on public.%I for all to authenticated
                    using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
  end loop;
end $$;

-- ---------- Auto-create profile on signup ----------
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles (id, name)
  values (new.id, coalesce(new.raw_user_meta_data->>'name', 'অতিথি ব্যবহারকারী'))
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();
