// FinTrack admin API. Owner only.
// The caller's login is checked against the owner's uid AND email. The service key stays in this function.
// Users' transaction details are never returned: only counts.
import { createClient } from "npm:@supabase/supabase-js@2";

const OWNER_UID = "cf8861d9-580a-4225-8211-fd95c959eb4a";
const OWNER_EMAIL = "shohagvaiiofc@gmail.com";
const REPO = "ShohagVaii/FinTracks";
const ALLOWED_ORIGIN = "https://shohagvaii.github.io";
// Screens a push can open: the tabs, and the overlay names of the app (lower case).
const TARGETS = [
  "dashboard", "transactions", "shared", "tools", "inbox", "settings", "notes", "budgets", "goals",
  "debts", "reports", "subscriptions", "accounts", "loan", "chat", "counter", "zakat", "tax",
  "currency", "age", "credit", "investments",
];

let downloadCache: { at: number; body: unknown } | null = null;

function corsHeaders(): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": ALLOWED_ORIGIN, // the admin page is served only from this origin
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Vary": "Origin",
  };
}

function reply(body: unknown, status: number, origin: string): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders(), "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

async function githubDownloads(): Promise<unknown> {
  if (downloadCache && Date.now() - downloadCache.at < 5 * 60_000) return downloadCache.body;
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=100`, {
    headers: { Accept: "application/vnd.github+json", "User-Agent": "fintrack-admin" },
  });
  if (!res.ok) throw new Error(`GitHub releases ${res.status}`);
  const releases = (await res.json()) as Array<{ tag_name: string; name: string | null; published_at: string | null; assets: Array<{ name: string; download_count: number }> }>;
  const rows = releases.map((r) => ({
    tag: r.tag_name,
    name: r.name ?? r.tag_name,
    published_at: r.published_at,
    downloads: r.assets.reduce((sum, a) => sum + (a.download_count ?? 0), 0),
  }));
  const body = { total: rows.reduce((s, r) => s + r.downloads, 0), releases: rows };
  downloadCache = { at: Date.now(), body };
  return body;
}

function str(v: unknown, max: number): string | null {
  return typeof v === "string" && v.trim().length > 0 && v.length <= max ? v.trim() : null;
}

Deno.serve(async (req) => {
  const origin = req.headers.get("origin") ?? "";
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });
  if (req.method !== "POST") return reply({ error: "Method not allowed" }, 405, origin);

  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return reply({ error: "লগইন করা নেই" }, 401, origin);

  const url = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

  const userClient = createClient(url, anonKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data, error } = await userClient.auth.getUser(token);
  if (error || !data.user) return reply({ error: "সেশন বৈধ নয়, আবার লগইন করুন" }, 401, origin);
  if (data.user.id !== OWNER_UID || (data.user.email ?? "").toLowerCase() !== OWNER_EMAIL) {
    return reply({ error: "এই অ্যাকাউন্টের অ্যাডমিন অ্যাক্সেস নেই" }, 403, origin);
  }

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return reply({ error: "অবৈধ অনুরোধ" }, 400, origin);
  }

  const admin = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  try {
    switch (body.action) {
      case "stats": {
        const { data: stats, error: e } = await admin.rpc("admin_stats");
        if (e) throw e;
        return reply({ stats, downloads: await githubDownloads() }, 200, origin);
      }
      case "users": {
        const search = typeof body.search === "string" ? body.search.slice(0, 80) : "";
        const page = Number.isInteger(body.page) && (body.page as number) >= 0 ? (body.page as number) : 0;
        const { data: rows, error: e } = await admin.rpc("admin_users", { p_search: search, p_limit: 50, p_offset: page * 50 });
        if (e) throw e;
        return reply({ users: rows ?? [], page }, 200, origin);
      }
      case "campaigns": {
        const { data: rows, error: e } = await admin
          .from("push_campaigns")
          .select("id,title,body,image_url,target,audience,audience_user_id,status,scheduled_at,sent_at,delivered_count,failed_count,created_at")
          .order("created_at", { ascending: false })
          .limit(100);
        if (e) throw e;
        return reply({ campaigns: rows ?? [] }, 200, origin);
      }
      case "create_campaign": {
        const title = str(body.title, 120);
        const text = str(body.body, 500);
        if (!title || !text) return reply({ error: "শিরোনাম ও লেখা দিন (শিরোনাম ১২০, লেখা ৫০০ অক্ষরের মধ্যে)" }, 400, origin);
        const imageUrl = body.image_url ? str(body.image_url, 500) : null;
        if (body.image_url && (!imageUrl || !imageUrl.startsWith("https://"))) {
          return reply({ error: "ব্যানার ছবির লিংক https:// দিয়ে শুরু হতে হবে" }, 400, origin);
        }
        const target = str(body.target, 40) ?? "dashboard";
        if (!TARGETS.includes(target)) return reply({ error: "অজানা টার্গেট স্ক্রিন" }, 400, origin);
        const audience = body.audience === "user" ? "user" : "all";
        let audienceUserId: string | null = null;
        if (audience === "user") {
          const email = str(body.audience_email, 200);
          if (!email) return reply({ error: "ইউজারের ইমেইল দিন" }, 400, origin);
          const { data: uid, error: e } = await admin.rpc("admin_find_user_id", { p_email: email });
          if (e) throw e;
          if (!uid) return reply({ error: "এই ইমেইলের কোনো ইউজার নেই" }, 404, origin);
          audienceUserId = uid as string;
        }
        let scheduledAt = new Date().toISOString();
        if (body.scheduled_at) {
          const t = Date.parse(String(body.scheduled_at));
          if (Number.isNaN(t)) return reply({ error: "সময়ের ফরম্যাট ঠিক নেই" }, 400, origin);
          scheduledAt = new Date(t).toISOString();
        }
        const { data: row, error: e } = await admin
          .from("push_campaigns")
          .insert({
            title, body: text, image_url: imageUrl, target, audience,
            audience_user_id: audienceUserId, status: "scheduled", scheduled_at: scheduledAt, created_by: OWNER_UID,
          })
          .select()
          .single();
        if (e) throw e;
        return reply({ campaign: row }, 200, origin);
      }
      default:
        return reply({ error: "অজানা কাজ" }, 400, origin);
    }
  } catch (err) {
    console.error("admin-api", body.action, err);
    return reply({ error: "সার্ভারে সমস্যা হয়েছে" }, 500, origin);
  }
});
