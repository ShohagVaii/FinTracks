-- বাজেট এনভেলপ: অব্যবহৃত টাকা পরের মাসে যাবে (rollover) কি না।
alter table public.budgets add column if not exists rollover boolean not null default false;
