// FinTrack voucher verification. Public endpoint (verify_jwt = false) used by verify.html and QR links.
// Input: POST { "trx": "FA611E53", "checksum": "BC9587FE60" }.
// Output:
//   { result: "ok",        details: {...} }   TRX and checksum both match an active record
//   { result: "void",      details: {...} }   matches a record that was cancelled (deleted transaction)
//   { result: "not_found" }                   anything else (wrong checksum, unknown TRX, bad format):
//                                             never says which part was wrong.
// The service-role key is read only here, on the server. It is never sent to a browser or the app.
import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type, apikey, authorization, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json; charset=utf-8" },
  });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json({ result: "not_found" });
  }
  const trx = String(body?.trx ?? "").trim().replace(/^#/, "").toUpperCase();
  const checksum = String(body?.checksum ?? "").trim().toUpperCase();
  // Format check only. TRX is 8 hex (transactions) or STMT<YYYYMM>[-n] (summaries); checksum is 10 hex.
  const trxOk = /^([0-9A-F]{8}|STMT\d{6}(-\d+)?)$/.test(trx);
  const chkOk = /^[0-9A-F]{10}$/.test(checksum);
  if (!trxOk || !chkOk) return json({ result: "not_found" });

  const url = Deno.env.get("SUPABASE_URL")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const admin = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });

  const { data, error } = await admin
    .from("voucher_registry")
    .select("trx_id, checksum, type, amount, date, category, method, profile_name, status, batch_id, period")
    .eq("trx_id", trx)
    .eq("checksum", checksum)
    .limit(1);
  if (error) return json({ error: "সার্ভার সমস্যা, পরে আবার চেষ্টা করুন" }, 500);
  const row = data?.[0];
  if (!row) return json({ result: "not_found" });

  const details = {
    trx: row.trx_id,
    type: row.type,
    amount: row.amount,
    date: row.date,
    category: row.category,
    method: row.method,
    profile: row.profile_name,
    batch: row.batch_id,
    period: row.period,
  };
  return json({ result: row.status === "void" ? "void" : "ok", details });
});
