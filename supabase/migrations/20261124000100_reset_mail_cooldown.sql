-- Reset-mail cooldown (5 hours per email), enforced by the request-password-reset Edge Function.
-- Idempotent: already applied to the live project as "reset_mail_cooldown".
create table if not exists public.password_reset_requests (
  email_key text primary key,
  last_sent_at timestamptz not null
);
alter table public.password_reset_requests enable row level security;
revoke all on public.password_reset_requests from anon, authenticated;
