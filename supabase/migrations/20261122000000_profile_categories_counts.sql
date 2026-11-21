-- Step 7: Settings rebuild + cash counter sync.
-- Profile details, custom categories with an icon, and synced cash-count history.
-- Every new column or table is protected by the existing per-user RLS pattern.

-- ---------- Profile details ----------
alter table public.profiles
  add column if not exists username text
    check (username is null or username ~ '^[a-z0-9_]{3,20}$'),
  add column if not exists date_of_birth date,
  add column if not exists phone text not null default '',
  add column if not exists address text not null default '',
  add column if not exists avatar_url text not null default '';

-- Usernames are unique regardless of case; empty/null usernames are not constrained.
create unique index if not exists profiles_username_unique
  on public.profiles (lower(username)) where username is not null;

-- ---------- Custom categories: icon (emoji) ----------
alter table public.categories
  add column if not exists icon text not null default '📦';

-- ---------- Cash counter history ----------
create table if not exists public.cash_counts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  counted_at timestamptz not null default now(),
  total numeric(14,2) not null default 0,
  pieces integer not null default 0,
  counts jsonb not null default '{}'::jsonb,   -- {"1000": 2, "500": 0, ...}
  created_at timestamptz not null default now()
);

create index if not exists cash_counts_user_time on public.cash_counts (user_id, counted_at desc);

alter table public.cash_counts enable row level security;
create policy "own rows" on public.cash_counts for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
