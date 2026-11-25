-- Bike fuel entries made from the + form have no odometer reading, so odo may be empty.
-- Not applied by CI: the owner runs this in the Supabase SQL editor before the app uses the + form bike category.

alter table public.bike_fuel_entries alter column odo drop not null;
