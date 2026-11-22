-- AI চ্যাট: প্রতিটি প্রশ্ন ও উত্তর অ্যাকাউন্টের সাথে সার্ভারে সেভ থাকে (তারিখ-সময় সহ),
-- যাতে অন্য ফোনে বা পরে আবার লগইন করে সেই চ্যাট দেখা যায়। শুধু নিজের সারি (RLS)।

create table if not exists public.ai_chat_messages (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  role text not null check (role in ('user', 'assistant', 'local')),
  text text not null default '',
  created_at timestamptz not null default now()
);

create index if not exists ai_chat_messages_user_idx on public.ai_chat_messages(user_id, created_at);

alter table public.ai_chat_messages enable row level security;

drop policy if exists "own rows" on public.ai_chat_messages;
create policy "own rows" on public.ai_chat_messages
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
