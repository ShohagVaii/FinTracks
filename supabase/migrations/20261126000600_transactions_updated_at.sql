-- Last save or edit time of a transaction, so an edited row rises in the home "recent" list on every phone.
-- Existing rows start from created_at, so old rows keep their current order.
alter table public.transactions add column if not exists updated_at timestamptz;

update public.transactions set updated_at = created_at where updated_at is null;

create index if not exists transactions_user_updated_idx on public.transactions (user_id, updated_at desc);
