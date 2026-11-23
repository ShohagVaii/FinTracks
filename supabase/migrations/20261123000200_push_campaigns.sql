-- FinTrack push campaigns: the owner's history of pushes. Written only by the server (send-push, service role).
-- Users can READ sent campaigns that are for everyone or for them; nobody else can write.
create table if not exists public.push_campaigns (
  id uuid primary key default gen_random_uuid(),
  title text not null check (char_length(title) between 1 and 120),
  body text not null check (char_length(body) between 1 and 500),
  image_url text check (image_url is null or image_url like 'https://%'),
  target text not null default 'dashboard' check (char_length(target) between 1 and 40),
  audience text not null default 'all' check (audience in ('all', 'user')),
  audience_user_id uuid references auth.users(id) on delete cascade,
  status text not null default 'draft' check (status in ('draft', 'scheduled', 'sent', 'failed')),
  scheduled_at timestamptz,
  sent_at timestamptz,
  delivered_count integer not null default 0,
  failed_count integer not null default 0,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint push_campaigns_user_audience check (audience <> 'user' or audience_user_id is not null)
);
create index if not exists push_campaigns_sent_idx on public.push_campaigns(status, sent_at desc);

alter table public.push_campaigns enable row level security;
create policy "push_campaigns_read_sent" on public.push_campaigns
  for select to authenticated
  using (status = 'sent' and (audience = 'all' or audience_user_id = auth.uid()));

-- Read state for the in-app inbox: one row per (user, campaign). Each user sees and writes only their own rows.
create table if not exists public.push_reads (
  user_id uuid not null references auth.users(id) on delete cascade,
  campaign_id uuid not null references public.push_campaigns(id) on delete cascade,
  read_at timestamptz not null default now(),
  primary key (user_id, campaign_id)
);
alter table public.push_reads enable row level security;
create policy "push_reads_select_own" on public.push_reads for select to authenticated using (user_id = auth.uid());
create policy "push_reads_insert_own" on public.push_reads for insert to authenticated with check (user_id = auth.uid());
create policy "push_reads_update_own" on public.push_reads for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
