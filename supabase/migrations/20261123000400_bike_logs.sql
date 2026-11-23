-- Bike logs (fuel fills and maintenance items) synced per user.
-- Not applied by CI: the owner runs this in the Supabase SQL editor first, then the app starts using it.
-- Each row keeps tx_id = the expense transaction it created (transactions.id), so deleting either side can be mirrored.

create table if not exists public.bike_fuel_entries (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  tx_date date not null,
  odo numeric not null,
  total numeric not null,
  liters numeric,
  price_per_l numeric,
  station text not null default '',
  full_tank boolean not null default true,
  note text not null default '',
  tx_id uuid,
  created_at timestamptz not null default now()
);

create table if not exists public.bike_maintenance_entries (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  tx_date date not null,
  kind text not null,
  item text not null default '',
  cost numeric not null,
  odo numeric,
  next_km numeric,
  next_date date,
  note text not null default '',
  tx_id uuid,
  created_at timestamptz not null default now()
);

create index if not exists bike_fuel_user_idx on public.bike_fuel_entries(user_id, odo);
create index if not exists bike_maint_user_idx on public.bike_maintenance_entries(user_id, tx_date);

alter table public.bike_fuel_entries enable row level security;
alter table public.bike_maintenance_entries enable row level security;

create policy "bike_fuel_own" on public.bike_fuel_entries for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy "bike_maint_own" on public.bike_maintenance_entries for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
