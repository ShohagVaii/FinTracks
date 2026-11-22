-- লেনদেনের ছবি: transactions.attachment_url (Storage path, URL নয়) + private bucket + নিজের ফোল্ডারের policy।
alter table public.transactions add column if not exists attachment_url text;

insert into storage.buckets (id, name, public)
values ('tx-attachments', 'tx-attachments', false)
on conflict (id) do nothing;

drop policy if exists tx_attach_select_own on storage.objects;
drop policy if exists tx_attach_insert_own on storage.objects;
drop policy if exists tx_attach_update_own on storage.objects;
drop policy if exists tx_attach_delete_own on storage.objects;

create policy tx_attach_select_own on storage.objects for select to authenticated
  using (bucket_id = 'tx-attachments' and (storage.foldername(name))[1] = auth.uid()::text);
create policy tx_attach_insert_own on storage.objects for insert to authenticated
  with check (bucket_id = 'tx-attachments' and (storage.foldername(name))[1] = auth.uid()::text);
create policy tx_attach_update_own on storage.objects for update to authenticated
  using (bucket_id = 'tx-attachments' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'tx-attachments' and (storage.foldername(name))[1] = auth.uid()::text);
create policy tx_attach_delete_own on storage.objects for delete to authenticated
  using (bucket_id = 'tx-attachments' and (storage.foldername(name))[1] = auth.uid()::text);
