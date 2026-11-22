// FinTrack: permanently deletes the signed-in user's account and their transaction photos in Storage.
// Deploy with verify_jwt = true. All user tables reference auth.users(id) ON DELETE CASCADE,
// so deleting the auth user removes every row that belongs to them.
import { createClient } from "npm:@supabase/supabase-js@2";

const headers = { "Content-Type": "application/json" };

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), { status: 405, headers });
  }
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) {
    return new Response(JSON.stringify({ error: "লগইন করা নেই" }), { status: 401, headers });
  }

  const url = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

  // Identify the caller from their own token (never trust a user id sent in the body).
  const userClient = createClient(url, anonKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data, error } = await userClient.auth.getUser(token);
  if (error || !data.user) {
    return new Response(JSON.stringify({ error: "সেশন বৈধ নয়, আবার লগইন করুন" }), { status: 401, headers });
  }

  const admin = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });

  // Remove the user's transaction photos first (stored at "<uid>/<txId>.jpg" in the tx-attachments bucket).
  // If this fails we stop before deleting the auth user, so the user can retry and no photo is orphaned.
  const uid = data.user.id;
  const bucket = admin.storage.from("tx-attachments");
  for (let guard = 0; guard < 1000; guard++) {
    const { data: files, error: listError } = await bucket.list(uid, { limit: 1000, offset: 0 });
    if (listError) {
      return new Response(JSON.stringify({ error: "ছবি মোছা যায়নি: " + listError.message }), { status: 500, headers });
    }
    const paths = (files ?? []).filter((f) => f.name && f.id).map((f) => uid + "/" + f.name);
    if (paths.length === 0) break;
    const { error: rmError } = await bucket.remove(paths);
    if (rmError) {
      return new Response(JSON.stringify({ error: "ছবি মোছা যায়নি: " + rmError.message }), { status: 500, headers });
    }
  }

  const { error: delError } = await admin.auth.admin.deleteUser(uid);
  if (delError) {
    return new Response(JSON.stringify({ error: "ডিলিট করা যায়নি: " + delError.message }), { status: 500, headers });
  }
  return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
});
