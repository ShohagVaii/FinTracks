-- Calendar marks: the user's own special days (one-off or yearly) and the birthday (kind = birthday, yearly).
-- Synced per user. Not applied by CI: the owner runs this in the Supabase SQL editor before the app uses it.
-- The app treats this table as optional, so older servers do not break sync.

create table if not exists public.calendar_marks (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  kind text not null default 'special' check (kind in ('special', 'birthday')),
  title text not null default '',
  mark_date date not null,
  color text not null default '#39C7E0',
  yearly boolean not null default false,
  created_at timestamptz not null default now()
);

create index if not exists calendar_marks_user_idx on public.calendar_marks(user_id, mark_date);

alter table public.calendar_marks enable row level security;

create policy "calendar_marks_own" on public.calendar_marks for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
