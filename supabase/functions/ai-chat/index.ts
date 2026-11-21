// FinTrack AI chat — Supabase Edge Function.
// verify_jwt=true only rejects requests without any JWT, and the public anon key is itself a JWT,
// so this function also checks that the bearer token belongs to a signed-in user (GoTrue /auth/v1/user).
// The Gemini key lives only in the GEMINI_API_KEY secret.
const MODELS = ['gemini-3.8-flash', 'gemini-3.5-flash-lite'];

const SYSTEM_BN =
  'তুমি FinTrack-এর আর্থিক সহকারী। বাংলায় সংক্ষেপে ও পরিষ্কারভাবে উত্তর দাও। ' +
  'শুধু নিচের আর্থিক সারসংক্ষেপের ভিত্তিতে উত্তর দাও। তথ্য না থাকলে সেটা স্পষ্ট করে বলো। ' +
  'পাসওয়ার্ড, PIN বা ব্যক্তিগত শনাক্তকারী তথ্য চাইবে না।';

// English replies, used when the app is in English mode. Same safety rules as the Bengali prompt.
const SYSTEM_EN =
  'You are the financial assistant of FinTrack. Answer briefly and clearly in English, using only the financial summary provided. ' +
  'If the summary does not contain the answer, say so. Never ask for a password, PIN or personal identifying information.';

function json(o: unknown, status = 200) {
  return new Response(JSON.stringify(o), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/** Returns the user id when the bearer token is a real, signed-in session. Anon keys fail here. */
async function signedInUserId(req: Request): Promise<string | null> {
  const token = (req.headers.get('authorization') ?? '').replace(/^Bearer\s+/i, '').trim();
  if (!token) return null;
  const base = Deno.env.get('SUPABASE_URL');
  const anon = Deno.env.get('SUPABASE_ANON_KEY') ?? '';
  if (!base) return null;
  const res = await fetch(`${base}/auth/v1/user`, {
    headers: { apikey: anon, authorization: `Bearer ${token}` },
  });
  if (!res.ok) return null;
  const user: any = await res.json();
  return typeof user?.id === 'string' ? user.id : null;
}

async function callGemini(key: string, model: string, prompt: string, system: string): Promise<Response> {
  return fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: 'user', parts: [{ text: prompt }] }],
      generationConfig: { maxOutputTokens: 700, temperature: 0.4 },
    }),
  });
}

Deno.serve(async (req: Request) => {
  if (req.method !== 'POST') return json({ error: 'method not allowed' }, 405);

  const userId = await signedInUserId(req);
  if (!userId) return json({ error: 'sign in required' }, 401);

  const key = Deno.env.get('GEMINI_API_KEY');
  if (!key) return json({ error: 'AI is not configured' }, 500);

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json({ error: 'invalid json' }, 400);
  }
  // Older app builds do not send a language; they keep getting Bengali answers.
  const lang = body?.language === 'en' ? 'en' : 'bn';
  const system = lang === 'en' ? SYSTEM_EN : SYSTEM_BN;
  const question = String(body?.question ?? '').slice(0, 1000).trim();
  const context = String(body?.context ?? '').slice(0, 4000);
  if (!question) return json({ error: 'question required' }, 400);

  const prompt = lang === 'en'
    ? `Financial summary:\n${context}\n\nQuestion: ${question}`
    : `আর্থিক সারসংক্ষেপ:\n${context}\n\nপ্রশ্ন: ${question}`;

  const attempts: { model: string; status: number; message: string }[] = [];
  for (const model of MODELS) {
    const res = await callGemini(key, model, prompt, system);
    if (res.ok) {
      const data: any = await res.json();
      const text = (data?.candidates?.[0]?.content?.parts ?? []).map((p: any) => p.text ?? '').join('');
      return json({ answer: text || (lang === 'en' ? 'No answer was returned.' : 'উত্তর পাওয়া যায়নি'), model });
    }
    // Keep the provider's message (it never contains the key) so failures can be diagnosed.
    const message = (await res.text()).slice(0, 300);
    attempts.push({ model, status: res.status, message });
    if (![400, 404, 429, 503].includes(res.status)) break;
  }
  return json({ error: 'AI service error', status: attempts.at(-1)?.status ?? 0, attempts }, 502);
});
