-- Trash (soft delete). Adds deleted_at to every user data table. Deleting a row only sets deleted_at;
-- the app hides rows with deleted_at set, and the Trash tool restores them or removes them for good.
-- Not wired in the app yet: the owner runs this file, then the app is connected to it.

do $$
declare
  t text;
  tables text[] := array[
    'accounts', 'categories', 'transactions', 'budgets', 'goals',
    'debt_people', 'debts', 'debt_payments', 'loans', 'subscriptions', 'investments',
    'credit_shops', 'credit_entries', 'credit_payments', 'bill_splits', 'shopping_items',
    'mess_members', 'mess_bazaars', 'mess_meals', 'mess_deposits', 'notes', 'cash_counts',
    'bike_fuel_entries', 'bike_maintenance_entries'
  ];
begin
  foreach t in array tables loop
    execute format('alter table public.%I add column if not exists deleted_at timestamptz', t);
    execute format('create index if not exists %I on public.%I (user_id, deleted_at) where deleted_at is not null', t || '_trash_idx', t);
  end loop;
end $$;

-- Auto-purge: removes rows that have been in the Trash for more than 30 days.
-- Children are removed before parents. Only the server job may run it (no client role can call it).
create or replace function public.fintrack_purge_trash() returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  t text;
  tables text[] := array[
    'bike_maintenance_entries', 'bike_fuel_entries', 'cash_counts', 'notes', 'mess_deposits', 'mess_meals',
    'mess_bazaars', 'mess_members', 'shopping_items', 'bill_splits', 'credit_payments', 'credit_entries',
    'credit_shops', 'investments', 'subscriptions', 'loans', 'debt_payments', 'debts', 'debt_people',
    'goals', 'budgets', 'transactions', 'categories', 'accounts'
  ];
  n integer := 0;
  c integer;
begin
  foreach t in array tables loop
    execute format('delete from public.%I where deleted_at is not null and deleted_at < now() - interval ''30 days''', t);
    get diagnostics c = row_count;
    n := n + c;
  end loop;
  return n;
end $$;

revoke all on function public.fintrack_purge_trash() from public, anon, authenticated;
