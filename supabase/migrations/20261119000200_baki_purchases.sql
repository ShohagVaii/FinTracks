-- বাকি খাতা v2: একটি কেনা (purchase) = একাধিক আইটেম + ± সমন্বয়। পুরোনো সারিগুলো একটি-আইটেম কেনা হিসেবেই থাকবে।

-- total আগে generated ছিল; এখন সাধারণ কলাম, অ্যাপ হিসাব করে লেখে। পুরোনো মান অক্ষত থাকে।
alter table public.credit_entries alter column total drop expression;

alter table public.credit_entries
  add column if not exists items jsonb not null default '[]'::jsonb,
  add column if not exists adjustment numeric(14,2) not null default 0;

-- items: [{"name": "মালদা", "qty": 2, "price": 120}, ...]
alter table public.credit_entries
  add constraint credit_entries_items_is_array check (jsonb_typeof(items) = 'array');

-- পরিশোধ: বাকির বেশি দেওয়া যাবে না (ডাটাবেসেও নিশ্চিত)
create or replace function public.credit_check_payment() returns trigger
language plpgsql security invoker set search_path = public as $$
declare
  due numeric;
  paid numeric;
begin
  select coalesce(sum(e.total), 0) into due from public.credit_entries e where e.shop_id = new.shop_id;
  select coalesce(sum(p.amount), 0) into paid from public.credit_payments p
    where p.shop_id = new.shop_id and (tg_op = 'INSERT' or p.id <> new.id);
  if paid + new.amount > due then
    raise exception 'বাকির বেশি টাকা পরিশোধ করা যাবে না (বাকি: %)', due - paid
      using errcode = 'check_violation';
  end if;
  return new;
end $$;

drop trigger if exists credit_payments_limit on public.credit_payments;
create trigger credit_payments_limit
  before insert or update of amount, shop_id on public.credit_payments
  for each row execute function public.credit_check_payment();
