-- Step 5: shared-section fields and a one-time backfill of বাকি payments.

-- Bill split: equal (default) or custom per-person amounts. shares = {"name": amount} for custom splits.
alter table public.bill_splits
  add column if not exists split_mode text not null default 'equal',
  add column if not exists shares jsonb not null default '{}'::jsonb;

alter table public.bill_splits
  drop constraint if exists bill_splits_split_mode_check;
alter table public.bill_splits
  add constraint bill_splits_split_mode_check check (split_mode in ('equal', 'custom'));

-- Shopping item: actual price paid, filled when the item is marked as bought. NULL = not bought yet.
alter table public.shopping_items
  add column if not exists actual_price numeric(14,2);

alter table public.shopping_items
  drop constraint if exists shopping_items_actual_price_check;
alter table public.shopping_items
  add constraint shopping_items_actual_price_check check (actual_price is null or actual_price >= 0);

-- One-time backfill: বাকি payments recorded before the expense link existed get their expense row.
-- The NOT EXISTS guard means a payment that already has a linked expense is never duplicated.
-- Label and category match the app (PaymentDialog → recordDebtPayment).
insert into public.transactions (user_id, type, amount, category, note, tx_date, payment_mode, source, source_ref)
select
  p.user_id,
  'Expense',
  p.amount,
  'বাকি পরিশোধ',
  'দোকান: ' || s.name || case when btrim(p.note) <> '' then ' · ' || btrim(p.note) else '' end,
  p.paid_on,
  'Cash',
  'debt_payment',
  p.id::text
from public.credit_payments p
join public.credit_shops s on s.id = p.shop_id
where not exists (
  select 1 from public.transactions t
  where t.source = 'debt_payment' and t.source_ref = p.id::text
);
