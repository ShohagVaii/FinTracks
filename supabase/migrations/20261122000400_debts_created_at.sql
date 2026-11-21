-- ধার নেওয়ার তারিখ (created_at)। লাইভ ডাটাবেসে মালিকের পক্ষ থেকে আগেই যোগ করা হয়েছে; এই ফাইল
-- অন্য পরিবেশে একই কাঠামো নিশ্চিত করে। পুরনো ধারগুলো migration-এর তারিখ পায় (আসল তৈরির তারিখ জানা নেই)।
alter table public.debts add column if not exists created_at timestamptz not null default now();
