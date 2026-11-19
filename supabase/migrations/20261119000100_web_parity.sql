-- Web-parity additions: currency/mood on transactions, shared expenses (bill split, mess, shopping).

alter table public.transactions
  add column if not exists currency text not null default 'BDT',
  add column if not exists mood text not null default '',
  add column if not exists is_recurring boolean not null default false;

alter table public.subscriptions
  add column if not exists currency text not null default 'BDT';

create table if not exists public.bill_splits (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null,
  total_amount numeric(14,2) not null check (total_amount > 0),
  participants text[] not null default '{}',
  payer text not null default '',
  split_date date not null default current_date,
  is_settled boolean not null default false
);

create table if not exists public.shopping_items (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  estimated_price numeric(14,2) not null default 0,
  is_done boolean not null default false
);

create table if not exists public.mess_members (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  deposit numeric(14,2) not null default 0
);

create table if not exists public.mess_bazaars (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  member_id uuid not null references public.mess_members(id) on delete cascade,
  amount numeric(14,2) not null check (amount >= 0),
  item text not null default '',
  bazaar_date date not null default current_date
);

create table if not exists public.mess_meals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  member_id uuid not null references public.mess_members(id) on delete cascade,
  meal_count numeric(6,2) not null default 1 check (meal_count >= 0),
  meal_date date not null default current_date
);

alter table public.bill_splits enable row level security;
alter table public.shopping_items enable row level security;
alter table public.mess_members enable row level security;
alter table public.mess_bazaars enable row level security;
alter table public.mess_meals enable row level security;

create policy "own rows" on public.bill_splits for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own rows" on public.shopping_items for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own rows" on public.mess_members for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own rows" on public.mess_bazaars for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own rows" on public.mess_meals for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
