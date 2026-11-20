-- Step 6: মেস "জমা এন্ট্রি" (ক্যাশ ডিপোজিট). জমা মোট = বাজার এন্ট্রি + জমা এন্ট্রি.

create table if not exists public.mess_deposits (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  member_id uuid not null references public.mess_members(id) on delete cascade,
  amount numeric(14,2) not null check (amount > 0),
  deposit_date date not null default current_date,
  note text not null default ''
);

alter table public.mess_deposits enable row level security;
create policy "own rows" on public.mess_deposits for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- One-time: old mess_members.deposit values count again, as one entry each, dated the day of this migration.
-- The old column is left untouched. The NOT EXISTS guard prevents a second copy.
insert into public.mess_deposits (user_id, member_id, amount, deposit_date, note)
select m.user_id, m.id, m.deposit, current_date, 'পুরনো জমা'
from public.mess_members m
where m.deposit > 0
  and not exists (select 1 from public.mess_deposits d where d.member_id = m.id and d.note = 'পুরনো জমা');
