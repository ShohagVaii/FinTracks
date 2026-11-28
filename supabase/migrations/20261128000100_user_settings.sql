-- Per-user settings synced across phones. First use: the category and payment-method overrides (key catstore_v1).
-- The value is one JSON text; updated_at decides which phone's copy wins (last write wins).
create table if not exists public.user_settings (
  user_id uuid not null references auth.users(id) on delete cascade,
  key text not null,
  value text not null default '',
  updated_at timestamptz not null default now(),
  primary key (user_id, key)
);

alter table public.user_settings enable row level security;

drop policy if exists "user_settings own rows" on public.user_settings;
create policy "user_settings own rows" on public.user_settings
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
