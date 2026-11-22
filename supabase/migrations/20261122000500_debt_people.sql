-- ধার: ব্যক্তি-কেন্দ্রিক হিসাব (debt_people) + ব্যক্তির এন্ট্রি (debts.person_id)
-- + আংশিক পরিশোধে লেনদেন সংযোগ (transactions.source = 'personal_debt_payment')।
-- একবারই চলবে: যে ধারের person_id আছে তাকে আর ছোঁয়া হয় না।

create table if not exists public.debt_people (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null check (btrim(name) <> ''),
  phone text not null default '',
  address text not null default '',
  created_at timestamptz not null default now()
);

create index if not exists debt_people_user_idx on public.debt_people(user_id);

alter table public.debt_people enable row level security;

drop policy if exists "own rows" on public.debt_people;
create policy "own rows" on public.debt_people
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

alter table public.debts add column if not exists person_id uuid references public.debt_people(id) on delete cascade;
create index if not exists debts_person_idx on public.debts(person_id);

-- পুরনো ধার → নাম অনুযায়ী ব্যক্তি (একই নামের ব্যক্তি একবারই)। কারণ খালি হলে 'পুরনো ধার'।
insert into public.debt_people (user_id, name)
select distinct d.user_id, coalesce(nullif(btrim(d.person), ''), 'অজ্ঞাত')
  from public.debts d
 where d.person_id is null
   and not exists (
     select 1 from public.debt_people p
      where p.user_id = d.user_id
        and p.name = coalesce(nullif(btrim(d.person), ''), 'অজ্ঞাত'));

update public.debts d
   set person_id = p.id,
       note = case when btrim(d.note) = '' then 'পুরনো ধার' else d.note end
  from public.debt_people p
 where d.person_id is null
   and p.user_id = d.user_id
   and p.name = coalesce(nullif(btrim(d.person), ''), 'অজ্ঞাত');

-- পরিশোধ মুছলে তার লেনদেনও মুছবে
create or replace function public.debt_payment_unlink_tx()
returns trigger
language plpgsql
as $$
begin
  delete from public.transactions t
   where t.source = 'personal_debt_payment' and t.source_ref = old.id::text;
  return null;
end $$;

drop trigger if exists debt_payment_unlink_tx on public.debt_payments;
create trigger debt_payment_unlink_tx
  after delete on public.debt_payments
  for each row execute function public.debt_payment_unlink_tx();

-- লেনদেন মুছলে তার পরিশোধও মুছবে (দুই দিক সবসময় মিলবে)
create or replace function public.transaction_unlink_debt_payment()
returns trigger
language plpgsql
as $$
begin
  if old.source = 'personal_debt_payment' and old.source_ref is not null then
    delete from public.debt_payments p where p.id::text = old.source_ref;
  end if;
  return null;
end $$;

drop trigger if exists transaction_unlink_debt_payment on public.transactions;
create trigger transaction_unlink_debt_payment
  after delete on public.transactions
  for each row execute function public.transaction_unlink_debt_payment();
