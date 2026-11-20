// FinTrack: permanently deletes the signed-in user's account.
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
  const { error: delError } = await admin.auth.admin.deleteUser(data.user.id);
  if (delError) {
    return new Response(JSON.stringify({ error: "ডিলিট করা যায়নি: " + delError.message }), { status: 500, headers });
  }
  return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
});
