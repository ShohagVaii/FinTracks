-- ধাপ ১২ (ঙ-১): বিল স্প্লিটার — গ্রুপ, ভাগের ধরন, ট্যাক্স/টিপ, একাধিক পরিশোধকারী, আংশিক পরিশোধ।

create table if not exists public.bill_groups (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  members text[] not null default '{}',
  created_at timestamptz not null default now()
);
alter table public.bill_groups enable row level security;
drop policy if exists "own rows" on public.bill_groups;
create policy "own rows" on public.bill_groups for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

alter table public.bill_splits
  add column if not exists group_id uuid,
  add column if not exists tax numeric(14,2) not null default 0,
  add column if not exists tip numeric(14,2) not null default 0,
  add column if not exists payers jsonb not null default '{}'::jsonb;

alter table public.bill_splits drop constraint if exists bill_splits_split_mode_check;
alter table public.bill_splits add constraint bill_splits_split_mode_check check (split_mode in ('equal', 'custom', 'percent', 'shares'));
alter table public.bill_splits drop constraint if exists bill_splits_tax_tip_check;
alter table public.bill_splits add constraint bill_splits_tax_tip_check check (tax >= 0 and tip >= 0);

create table if not exists public.bill_settlements (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  bill_id uuid not null references public.bill_splits(id) on delete cascade,
  from_name text not null,
  to_name text not null,
  amount numeric(14,2) not null check (amount > 0),
  paid_on date not null default current_date,
  created_at timestamptz not null default now()
);
alter table public.bill_settlements enable row level security;
drop policy if exists "own rows" on public.bill_settlements;
create policy "own rows" on public.bill_settlements for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
