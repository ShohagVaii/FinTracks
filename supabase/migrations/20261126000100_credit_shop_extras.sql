-- ধাপ ১২ (ঘ): দোকানের ধরন ও ক্রেডিট-সীমা, কেনায় ছবি, বাকি পরিশোধের পদ্ধতি।
alter table public.credit_shops add column if not exists category text not null default 'other';
alter table public.credit_shops add column if not exists credit_limit numeric(14,2);
alter table public.credit_entries add column if not exists attachment_url text;
alter table public.credit_payments add column if not exists method text not null default 'Cash';
