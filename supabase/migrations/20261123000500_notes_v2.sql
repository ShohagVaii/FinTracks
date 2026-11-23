-- Notes V2: pin to top, colour, archive. Additive only; existing rows keep working.
alter table public.notes add column if not exists pinned boolean not null default false;
alter table public.notes add column if not exists archived boolean not null default false;
alter table public.notes add column if not exists color text not null default '';
