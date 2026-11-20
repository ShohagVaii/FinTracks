-- বাকি পরিশোধের সীমা: একই id-র সারি (restore/upsert) দুবার গোনা হবে না।
create or replace function public.credit_check_payment() returns trigger
language plpgsql security invoker set search_path = public as $$
declare
  due numeric;
  paid numeric;
begin
  select coalesce(sum(e.total), 0) into due from public.credit_entries e where e.shop_id = new.shop_id;
  select coalesce(sum(p.amount), 0) into paid from public.credit_payments p
    where p.shop_id = new.shop_id and p.id <> new.id;
  if paid + new.amount > due then
    raise exception 'বাকির বেশি টাকা পরিশোধ করা যাবে না (বাকি: %)', due - paid
      using errcode = 'check_violation';
  end if;
  return new;
end $$;
