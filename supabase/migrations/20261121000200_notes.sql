-- Step 6: নোটপ্যাড. id is generated on the device so notes can be created offline.
-- RLS: each user reads and writes only their own notes.

create table if not exists public.notes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null default '',
  body text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists notes_user_updated on public.notes (user_id, updated_at desc);

alter table public.notes enable row level security;
create policy "own rows" on public.notes for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
