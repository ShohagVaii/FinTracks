// FinTrack push sender (FCM HTTP v1). Owner only. NOT DEPLOYED YET: needs the FCM_SERVICE_ACCOUNT secret.
//
// Request (POST, Authorization: Bearer <owner's login>):
//   { "campaign_id": "<uuid>" }   send one campaign now
//   { "due": true }               send every scheduled campaign whose time has come (max 20 per call)
//   { "kind": "text" | "banner" | "alert" }   optional; default: "banner" if the campaign has an image, else "text"
//
// Payload kinds:
//   text    title + body, normal priority
//   banner  title + body + big picture (campaign image_url, https only)
//   alert   title + body, high priority, alerts channel
// Every kind carries data.target (the screen to open on tap) and data.campaign_id.
//
// Secrets (Supabase Edge secrets only, never in the repo or the browser):
//   FCM_SERVICE_ACCOUNT   the Firebase service-account JSON (read here, never logged)
import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";

const OWNER_UID = "cf8861d9-580a-4225-8211-fd95c959eb4a";
const OWNER_EMAIL = "shohagvaiiofc@gmail.com";
const ALLOWED_ORIGIN = "https://shohagvaii.github.io";
const TARGETS = new Set([
  "dashboard", "transactions", "shared", "tools", "inbox", "settings", "notes", "budgets", "goals",
  "debts", "reports", "subscriptions", "accounts", "loan", "chat", "counter", "zakat", "tax",
  "currency", "age", "credit", "investments",
]);
const SMALL_ICON = "ic_stat_fintrack"; // white silhouette in the app's drawable folders
const CHANNEL_PUSH = "fintrack_push";
const CHANNEL_ALERT = "fintrack_alerts";
const SEND_CONCURRENCY = 10;

type Kind = "text" | "banner" | "alert";
type ServiceAccount = { project_id: string; client_email: string; private_key: string; token_uri?: string };
type Campaign = {
  id: string; title: string; body: string; image_url: string | null; target: string;
  audience: "all" | "user"; audience_user_id: string | null; status: string; scheduled_at: string | null;
};

const CORS = {
  "Access-Control-Allow-Origin": ALLOWED_ORIGIN,
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin",
};
function reply(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { ...CORS, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

// ---------- Google OAuth for FCM (service account JWT, RS256) ----------
function b64url(bytes: Uint8Array | string): string {
  const raw = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  let s = "";
  for (const b of raw) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

let tokenCache: { value: string; exp: number } | null = null;
async function fcmAccessToken(sa: ServiceAccount): Promise<string> {
  if (tokenCache && tokenCache.exp - 60 > Date.now() / 1000) return tokenCache.value;
  const now = Math.floor(Date.now() / 1000);
  const aud = sa.token_uri ?? "https://oauth2.googleapis.com/token";
  const unsigned = `${b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64url(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud, iat: now, exp: now + 3600,
  }))}`;
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const res = await fetch(aud, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${b64url(sig)}` }),
  });
  if (!res.ok) throw new Error(`FCM token exchange failed (${res.status})`);
  const j = (await res.json()) as { access_token: string; expires_in: number };
  tokenCache = { value: j.access_token, exp: now + (j.expires_in ?? 3600) };
  return j.access_token;
}

function buildMessage(token: string, c: Campaign, kind: Kind) {
  const alert = kind === "alert";
  const image = kind === "banner" && c.image_url?.startsWith("https://") ? c.image_url : undefined;
  const android: Record<string, unknown> = {
    priority: alert ? "HIGH" : "NORMAL",
    notification: { channel_id: alert ? CHANNEL_ALERT : CHANNEL_PUSH, icon: SMALL_ICON, ...(image ? { image } : {}) },
  };
  return {
    message: {
      token,
      notification: { title: c.title, body: c.body, ...(image ? { image } : {}) },
      android,
      data: { target: TARGETS.has(c.target) ? c.target : "dashboard", campaign_id: c.id, kind },
    },
  };
}

async function sendTo(projectId: string, accessToken: string, message: unknown): Promise<"ok" | "gone" | "failed"> {
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
    body: JSON.stringify(message),
  });
  if (res.ok) return "ok";
  // 404 UNREGISTERED / 400 INVALID_ARGUMENT on a token: the device no longer exists or the token is bad.
  if (res.status === 404 || res.status === 400) {
    const text = await res.text();
    if (text.includes("UNREGISTERED") || text.includes("INVALID_ARGUMENT")) return "gone";
  }
  return "failed";
}

async function sendCampaign(admin: SupabaseClient, sa: ServiceAccount, c: Campaign, kindIn: Kind | null) {
  const kind: Kind = kindIn ?? (c.image_url ? "banner" : "text");
  let q = admin.from("device_tokens").select("id,fcm_token");
  if (c.audience === "user") q = q.eq("user_id", c.audience_user_id!);
  const { data: rows, error } = await q.limit(5000);
  if (error) throw error;
  const devices = rows ?? [];
  const accessToken = await fcmAccessToken(sa);

  let delivered = 0, failed = 0;
  const gone: string[] = [];
  for (let i = 0; i < devices.length; i += SEND_CONCURRENCY) {
    const results = await Promise.all(devices.slice(i, i + SEND_CONCURRENCY).map(async (d) => {
      const r = await sendTo(sa.project_id, accessToken, buildMessage(d.fcm_token, c, kind));
      return { id: d.id, r };
    }));
    for (const { id, r } of results) {
      if (r === "ok") delivered++;
      else if (r === "gone") gone.push(id);
      else failed++;
    }
  }
  if (gone.length) await admin.from("device_tokens").delete().in("id", gone);

  const { error: upErr } = await admin.from("push_campaigns").update({
    status: failed > 0 && delivered === 0 ? "failed" : "sent",
    sent_at: new Date().toISOString(),
    delivered_count: delivered,
    failed_count: failed,
  }).eq("id", c.id);
  if (upErr) throw upErr;
  return { campaign_id: c.id, kind, devices: devices.length, delivered, failed, removed_tokens: gone.length };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (req.method !== "POST") return reply({ error: "Method not allowed" }, 405);

  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return reply({ error: "login required" }, 401);

  const url = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const userClient = createClient(url, anonKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: who, error: whoErr } = await userClient.auth.getUser(token);
  if (whoErr || !who.user) return reply({ error: "login expired" }, 401);
  if (who.user.id !== OWNER_UID || (who.user.email ?? "").toLowerCase() !== OWNER_EMAIL) {
    return reply({ error: "owner only" }, 403);
  }

  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return reply({ error: "bad request" }, 400); }
  const kindIn = body.kind === "text" || body.kind === "banner" || body.kind === "alert" ? (body.kind as Kind) : null;

  const saRaw = Deno.env.get("FCM_SERVICE_ACCOUNT");
  if (!saRaw) return reply({ error: "push service is not configured (FCM_SERVICE_ACCOUNT missing)" }, 503);
  let sa: ServiceAccount;
  try { sa = JSON.parse(saRaw); } catch { return reply({ error: "push service configuration is invalid" }, 503); }

  const admin = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  try {
    let query = admin.from("push_campaigns")
      .select("id,title,body,image_url,target,audience,audience_user_id,status,scheduled_at");
    if (body.due === true) {
      query = query.eq("status", "scheduled").lte("scheduled_at", new Date().toISOString())
        .order("scheduled_at", { ascending: true }).limit(20);
    } else if (typeof body.campaign_id === "string") {
      query = query.eq("id", body.campaign_id).in("status", ["draft", "scheduled"]);
    } else {
      return reply({ error: "campaign_id or due required" }, 400);
    }
    const { data: campaigns, error } = await query;
    if (error) throw error;
    if (!campaigns || campaigns.length === 0) return reply({ error: "no campaign to send (already sent, or not found)" }, 404);

    const results = [];
    for (const c of campaigns as Campaign[]) results.push(await sendCampaign(admin, sa, c, kindIn));
    return reply({ results }, 200);
  } catch (err) {
    console.error("send-push failed", err instanceof Error ? err.message : "unknown error");
    return reply({ error: "sending failed; see function logs" }, 500);
  }
});
