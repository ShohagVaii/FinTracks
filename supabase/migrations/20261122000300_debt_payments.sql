-- ধার ও পাওনা: আংশিক পরিশোধ।
-- বাকি = মোট − মোট পরিশোধ। মোটের বেশি পরিশোধ আটকায়। বাকি ০ হলে debts.settled = true (ট্রিগারে)।
-- ধার পরিশোধে কোনো লেনদেন তৈরি হয় না (বর্তমান ধারের নিষ্পত্তির আচরণের সঙ্গে মিল)।

create table if not exists public.debt_payments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  debt_id uuid not null references public.debts(id) on delete cascade,
  amount numeric(14,2) not null check (amount > 0),
  paid_on date not null default current_date,
  note text not null default '',
  created_at timestamptz not null default now()
);

create index if not exists debt_payments_debt_idx on public.debt_payments(debt_id);

alter table public.debt_payments enable row level security;

drop policy if exists "own rows" on public.debt_payments;
create policy "own rows" on public.debt_payments
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- মোটের বেশি পরিশোধ আটকানো
create or replace function public.debt_payments_guard()
returns trigger
language plpgsql
as $$
declare
  d_amount numeric;
  already numeric;
begin
  select d.amount into d_amount from public.debts d where d.id = new.debt_id;
  if d_amount is null then
    raise exception 'debt not found' using errcode = 'foreign_key_violation';
  end if;
  select coalesce(sum(p.amount), 0) into already
    from public.debt_payments p
   where p.debt_id = new.debt_id and p.id <> new.id;
  if already + new.amount > d_amount + 0.005 then
    raise exception 'debt payment exceeds remaining balance' using errcode = 'check_violation';
  end if;
  return new;
end $$;

drop trigger if exists debt_payments_guard on public.debt_payments;
create trigger debt_payments_guard
  before insert or update of amount, debt_id on public.debt_payments
  for each row execute function public.debt_payments_guard();

-- পরিশোধ বদলালে debts.settled ঠিক রাখা
create or replace function public.debt_payments_sync_settled()
returns trigger
language plpgsql
as $$
declare
  did uuid;
begin
  if tg_op = 'DELETE' then did := old.debt_id; else did := new.debt_id; end if;
  update public.debts d
     set settled = coalesce((select sum(p.amount) from public.debt_payments p where p.debt_id = d.id), 0) >= d.amount - 0.005
   where d.id = did;
  return null;
end $$;

drop trigger if exists debt_payments_settled on public.debt_payments;
create trigger debt_payments_settled
  after insert or update or delete on public.debt_payments
  for each row execute function public.debt_payments_sync_settled();

-- একবারের ব্যাকফিল: আগে "নিষ্পন্ন" চিহ্নিত ধারের পুরো পরিমাণ একটি পরিশোধ হিসেবে যোগ হবে (ডুপ্লিকেট ছাড়া)।
insert into public.debt_payments (user_id, debt_id, amount, paid_on, note)
select d.user_id, d.id, d.amount, current_date, 'পুরনো নিষ্পত্তি'
  from public.debts d
 where d.settled
   and not exists (select 1 from public.debt_payments p where p.debt_id = d.id);
