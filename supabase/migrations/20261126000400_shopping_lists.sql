-- ধাপ ১২ (ঙ-৩): শপিং লিস্ট — একাধিক তালিকা (নিজস্ব বাজেট), আইটেমে পরিমাণ/একক/ক্যাটাগরি/অগ্রাধিকার/নোট, লেনদেনে যোগ।

create table if not exists public.shopping_lists (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  budget numeric(14,2) not null default 0 check (budget >= 0),
  archived boolean not null default false,
  add_to_tx boolean not null default false,
  tx_posted boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.shopping_lists enable row level security;
drop policy if exists "own rows" on public.shopping_lists;
create policy "own rows" on public.shopping_lists for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

alter table public.shopping_items
  add column if not exists list_id uuid references public.shopping_lists(id) on delete cascade,
  add column if not exists qty numeric(14,3) not null default 1,
  add column if not exists unit text not null default 'পিস',
  add column if not exists category text not null default 'অন্যান্য',
  add column if not exists priority text not null default 'normal',
  add column if not exists note text not null default '',
  add column if not exists assignee text not null default '';

alter table public.shopping_items drop constraint if exists shopping_items_priority_check;
alter table public.shopping_items add constraint shopping_items_priority_check check (priority in ('high', 'normal', 'low'));

-- আগের সব আইটেম প্রতিটি ব্যবহারকারীর একটি "শপিং" তালিকায় যায়।
insert into public.shopping_lists (user_id, name)
select distinct user_id, 'শপিং' from public.shopping_items where list_id is null;
update public.shopping_items i set list_id = l.id
from public.shopping_lists l
where i.list_id is null and l.user_id = i.user_id and l.name = 'শপিং';
