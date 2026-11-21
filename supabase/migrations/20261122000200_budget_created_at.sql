-- বাজেট রোলওভার: বাজেট তৈরির আগের মাসের অব্যবহৃত টাকা যোগ হবে না।
alter table public.budgets add column if not exists created_at timestamptz not null default now();
