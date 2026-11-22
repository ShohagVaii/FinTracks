-- Note photos: storage paths of the photos attached to a note (uploaded to tx-attachments/<uid>/notes/...).
alter table public.notes add column if not exists attachments jsonb not null default '[]'::jsonb;
