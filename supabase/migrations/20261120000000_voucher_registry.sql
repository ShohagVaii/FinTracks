-- FinTrack voucher registry: one row per TRX (transaction or summary batch).
-- Owners read/write their own rows. Anonymous clients cannot read the table; public verification
-- goes only through the verify-voucher Edge Function (service role, server side).
create table if not exists public.voucher_registry (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users(id) on delete cascade,
  trx_id       text not null,
  checksum     text not null,
  type         text not null check (type in ('INCOME', 'EXPENSE', 'SUMMARY')),
  amount       numeric(14, 2) not null default 0,
  date         date not null,
  category     text,
  method       text,
  profile_name text not null default '',
  batch_id     text,
  period       text,
  status       text not null default 'active' check (status in ('active', 'void')),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  unique (trx_id, checksum)
);

create index if not exists voucher_registry_user_batch on public.voucher_registry (user_id, type, batch_id);

alter table public.voucher_registry enable row level security;

drop policy if exists "voucher_registry owner select" on public.voucher_registry;
drop policy if exists "voucher_registry owner insert" on public.voucher_registry;
drop policy if exists "voucher_registry owner update" on public.voucher_registry;

create policy "voucher_registry owner select" on public.voucher_registry
  for select to authenticated using (auth.uid() = user_id);

create policy "voucher_registry owner insert" on public.voucher_registry
  for insert to authenticated with check (auth.uid() = user_id);

create policy "voucher_registry owner update" on public.voucher_registry
  for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- No delete policy: rows are never deleted by the app; a deleted transaction is set to status = 'void'.
revoke all on public.voucher_registry from anon;

create or replace function public.voucher_registry_touch() returns trigger
language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end $$;

drop trigger if exists voucher_registry_touch on public.voucher_registry;
create trigger voucher_registry_touch before update on public.voucher_registry
  for each row execute function public.voucher_registry_touch();
