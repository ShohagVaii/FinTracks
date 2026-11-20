-- Links automatically created transactions to their origin.
-- source = 'debt_payment' : created when a credit (বাকি) payment is recorded; source_ref = credit_payments.id
-- source = 'sms'          : accepted from the SMS parser
-- Plain manual transactions keep source NULL.
alter table public.transactions add column if not exists source text;
alter table public.transactions add column if not exists source_ref text;
create index if not exists transactions_source_ref on public.transactions (source_ref) where source_ref is not null;
