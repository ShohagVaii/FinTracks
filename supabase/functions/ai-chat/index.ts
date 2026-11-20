// FinTrack AI chat — Supabase Edge Function (verify_jwt = true).
// The Gemini key lives only in the GEMINI_API_KEY secret.
// Models are tried in order; a retired/unavailable model (404/400/429/503) falls through to the next.
const MODELS = ['gemini-3.8-flash', 'gemini-3.5-flash-lite'];

const SYSTEM =
  'তুমি FinTrack-এর আর্থিক সহকারী। বাংলায় সংক্ষেপে ও পরিষ্কারভাবে উত্তর দাও। ' +
  'শুধু নিচের আর্থিক সারসংক্ষেপের ভিত্তিতে উত্তর দাও। তথ্য না থাকলে সেটা স্পষ্ট করে বলো। ' +
  'পাসওয়ার্ড, PIN বা ব্যক্তিগত শনাক্তকারী তথ্য চাইবে না।';

function json(o: unknown, status = 200) {
  return new Response(JSON.stringify(o), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

async function callGemini(key: string, model: string, prompt: string): Promise<Response> {
  return fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: SYSTEM }] },
      contents: [{ role: 'user', parts: [{ text: prompt }] }],
      generationConfig: { maxOutputTokens: 700, temperature: 0.4 },
    }),
  });
}

Deno.serve(async (req: Request) => {
  if (req.method !== 'POST') return json({ error: 'method not allowed' }, 405);
  const key = Deno.env.get('GEMINI_API_KEY');
  if (!key) return json({ error: 'AI is not configured' }, 500);

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json({ error: 'invalid json' }, 400);
  }
  const question = String(body?.question ?? '').slice(0, 1000).trim();
  const context = String(body?.context ?? '').slice(0, 4000);
  if (!question) return json({ error: 'question required' }, 400);

  const prompt = `আর্থিক সারসংক্ষেপ:\n${context}\n\nপ্রশ্ন: ${question}`;

  let lastStatus = 0;
  for (const model of MODELS) {
    const res = await callGemini(key, model, prompt);
    if (res.ok) {
      const data: any = await res.json();
      const text = (data?.candidates?.[0]?.content?.parts ?? []).map((p: any) => p.text ?? '').join('');
      return json({ answer: text || 'উত্তর পাওয়া যায়নি', model });
    }
    lastStatus = res.status;
    // Only fall through when the model itself is the problem; other errors stop here.
    if (![400, 404, 429, 503].includes(res.status)) break;
  }
  return json({ error: 'AI service error', status: lastStatus }, 502);
});
