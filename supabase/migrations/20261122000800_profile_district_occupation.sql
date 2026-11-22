-- প্রোফাইল: জেলা ও পেশা (বাছাই বা "অন্যান্য" + নিজের লেখা)। ফাঁকা = কিছু বাছা হয়নি।
alter table public.profiles add column if not exists district text;
alter table public.profiles add column if not exists occupation text;
