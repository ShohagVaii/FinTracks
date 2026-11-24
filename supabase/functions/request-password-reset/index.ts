// Sends a password-reset mail at most once per email every 5 hours.
// The last-sent time lives on the server (table password_reset_requests), not on the phone.
const COOLDOWN_MS = 5 * 60 * 60 * 1000;
const REDIRECT = "https://shohagvaii.github.io/FinTracks/reset.html";
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });
  const input = await req.json().catch(() => ({}));
  const email = String(input?.email ?? "").trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return json(400, { error: "invalid_email" });

  const base = Deno.env.get("SUPABASE_URL")!;
  const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const db = { apikey: service, Authorization: `Bearer ${service}` };

  // 1) Server-side check of the last reset time for this email.
  const found = await fetch(`${base}/rest/v1/password_reset_requests?email_key=eq.${encodeURIComponent(email)}&select=last_sent_at`, { headers: db });
  if (!found.ok) return json(500, { error: "lookup_failed" });
  const rows = await found.json();
  const now = Date.now();
  const last = rows[0] ? Date.parse(rows[0].last_sent_at) : NaN;
  if (!isNaN(last) && now - last < COOLDOWN_MS) {
    return json(429, { error: "cooldown", retry_after_seconds: Math.ceil((last + COOLDOWN_MS - now) / 1000) });
  }

  // 2) Send the mail through Supabase Auth.
  const sent = await fetch(`${base}/auth/v1/recover?redirect_to=${encodeURIComponent(REDIRECT)}`, {
    method: "POST",
    headers: { apikey: anon, "Content-Type": "application/json" },
    body: JSON.stringify({ email }),
  });
  if (!sent.ok) return json(sent.status, { error: "send_failed" });

  // 3) Record the send time (the mail is already on its way, so a failed record does not fail the reply).
  await fetch(`${base}/rest/v1/password_reset_requests?on_conflict=email_key`, {
    method: "POST",
    headers: { ...db, "Content-Type": "application/json", Prefer: "resolution=merge-duplicates" },
    body: JSON.stringify({ email_key: email, last_sent_at: new Date(now).toISOString() }),
  });
  return json(200, { ok: true, retry_after_seconds: 0 });
});
