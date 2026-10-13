// Ourea Scan AI guide: answers a customer's questions about the menu in the
// customer's own language, from the store's real dish data, and suggests
// dishes the phone shows as "Add" buttons (the customer taps; a server
// confirms the order). Called from web/ourea-scan without a login: the table's
// QR secret is the credential. docs/migrations/2026-10-13_scan_assistant.sql.
//
// Actions: ask {token, question, history[], browserLang}, transcribe {token, audio, mime}.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { corsHeaders } from "../_shared/cors.ts";
import { checkSuggestions, parseAnswer, systemPrompt, type Dish } from "./guide.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const PER_TABLE_10MIN = Number(Deno.env.get("SCAN_AI_PER_TABLE_10MIN") ?? 15);
const PER_STORE_DAY = Number(Deno.env.get("SCAN_AI_PER_STORE_DAY") ?? 400);
const MAX_QUESTION = 400;
const HISTORY_TURNS = 6;
const MAX_AUDIO_BYTES = 1_500_000;

type Turn = { role: "user" | "assistant"; content: string };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

class BusyError extends Error {}

/** Same providers as the store's Beaver (apex-assistant); SCAN_* overrides let the two use different keys/models. */
function provider(): { name: string; call: (system: string, turns: Turn[]) => Promise<string> } | null {
  const anthropicKey = Deno.env.get("ANTHROPIC_API_KEY");
  const groqKey = Deno.env.get("SCAN_GROQ_API_KEY") ?? Deno.env.get("GROQ_API_KEY");
  const wanted = Deno.env.get("SCAN_PROVIDER") ?? Deno.env.get("APEX_PROVIDER") ?? (anthropicKey ? "anthropic" : groqKey ? "groq" : "");
  if (wanted === "anthropic" && anthropicKey) {
    const model = Deno.env.get("SCAN_ANTHROPIC_MODEL") ?? "claude-haiku-5-5";
    return {
      name: `anthropic/${model}`,
      call: async (system, turns) => {
        const res = await fetch("https://api.anthropic.com/v1/messages", {
          method: "POST",
          headers: { "x-api-key": anthropicKey, "anthropic-version": "2023-06-01", "content-type": "application/json" },
          body: JSON.stringify({ model, max_tokens: 700, system, messages: turns }),
        });
        if (res.status === 429) throw new BusyError();
        const data = await res.json();
        const text = (data?.content ?? []).filter((b: { type: string }) => b.type === "text").map((b: { text: string }) => b.text).join("");
        if (!res.ok || !text) throw new Error(`anthropic ${res.status}`);
        return text;
      },
    };
  }
  if (wanted === "groq" && groqKey) {
    const model = Deno.env.get("SCAN_GROQ_MODEL") ?? Deno.env.get("APEX_GROQ_MODEL") ?? "openai/gpt-oss-120b";
    return {
      name: `groq/${model}`,
      call: async (system, turns) => {
        const send = () => fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: { Authorization: `Bearer ${groqKey}`, "Content-Type": "application/json" },
          body: JSON.stringify({ model, max_tokens: 900, response_format: { type: "json_object" }, messages: [{ role: "system", content: system }, ...turns] }),
        });
        let res = await send();
        if (res.status === 429) {
          const wait = Number(res.headers.get("retry-after") ?? "") || 6;
          if (wait <= 8) { await new Promise((ok) => setTimeout(ok, wait * 1000 + 300)); res = await send(); }
          if (res.status === 429) throw new BusyError();
        }
        const data = await res.json();
        const text: string | undefined = data?.choices?.[0]?.message?.content;
        if (!res.ok || !text) throw new Error(`groq ${res.status}: ${JSON.stringify(data?.error ?? data).slice(0, 200)}`);
        return text;
      },
    };
  }
  return null;
}

/** Speech to text, any language (Whisper detects it): customers speak their own. */
async function transcribe(audioB64: string, mime: string): Promise<string | null> {
  const groqKey = Deno.env.get("SCAN_GROQ_API_KEY") ?? Deno.env.get("GROQ_API_KEY");
  const openaiKey = Deno.env.get("OPENAI_API_KEY");
  if (!groqKey && !openaiKey) return null;
  const bytes = Uint8Array.from(atob(audioB64), (c) => c.charCodeAt(0));
  if (bytes.length > MAX_AUDIO_BYTES) throw new Error("too_long");
  const form = new FormData();
  const ext = mime.includes("webm") ? "webm" : mime.includes("ogg") ? "ogg" : mime.includes("wav") ? "wav" : "m4a";
  form.append("file", new Blob([bytes], { type: mime }), `speech.${ext}`);
  form.append("model", groqKey ? "whisper-large-v3" : "whisper-1");
  form.append("response_format", "json");
  const res = await fetch(groqKey ? "https://api.groq.com/openai/v1/audio/transcriptions" : "https://api.openai.com/v1/audio/transcriptions", {
    method: "POST", headers: { Authorization: `Bearer ${groqKey ?? openaiKey}` }, body: form,
  });
  if (res.status === 429) throw new BusyError();
  const data = await res.json();
  if (!res.ok) throw new Error(`transcription ${res.status}`);
  const text = String(data?.text ?? "").trim();
  return /^(thank you|thanks for watching|you|谢谢(大家)?(观看|收看)?)[.!。！]*$/i.test(text) || !/\p{L}/u.test(text) ? "" : text;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const body = await req.json().catch(() => ({}));
    const token = typeof body.token === "string" ? body.token.slice(0, 64) : "";
    const db = createClient(SUPABASE_URL, SERVICE_KEY);

    const { data: menu } = await db.rpc("scan_menu", { p_token: token });
    if (!menu) return json({ error: "unknown_table" }, 404);

    if (body.action === "transcribe") {
      if (typeof body.audio !== "string" || !body.audio) return json({ error: "no_audio" }, 400);
      const text = await transcribe(body.audio, typeof body.mime === "string" ? body.mime : "audio/webm");
      return text === null ? json({ error: "no_speech_service" }, 503) : json({ text });
    }

    if (body.action !== "ask") return json({ error: "unknown_action" }, 400);
    const question = String(body.question ?? "").trim().slice(0, MAX_QUESTION);
    if (!question) return json({ error: "empty" }, 400);

    const { data: gate } = await db.rpc("scan_chat_allowed", { p_token: token, p_per_10min: PER_TABLE_10MIN, p_store_per_day: PER_STORE_DAY });
    if (!gate?.allowed) return json({ error: gate?.reason ?? "limit" }, 429);

    const llm = provider();
    if (!llm) return json({ error: "no_model" }, 503);

    const dishes: Dish[] = menu.products ?? [];
    const browserLang = typeof body.browserLang === "string" ? body.browserLang.slice(0, 20) : "en";
    const history: Turn[] = (Array.isArray(body.history) ? body.history : [])
      .filter((t: Turn) => (t?.role === "user" || t?.role === "assistant") && typeof t.content === "string")
      .slice(-HISTORY_TURNS)
      .map((t: Turn) => ({ role: t.role, content: t.content.slice(0, 800) }));
    while (history.length && history[0].role !== "user") history.shift();

    // The model now and then returns broken JSON (Groq then answers 400
    // json_validate_failed): one more try before telling the customer.
    const system = systemPrompt(menu.store.name, menu.table.name, dishes, browserLang);
    const turns: Turn[] = [...history, { role: "user", content: question }];
    let answer = null;
    for (let attempt = 0; attempt < 2 && !answer; attempt++) {
      try {
        answer = parseAnswer(await llm.call(system, turns));
        if (!answer) console.warn("[scan-assistant] unparsable answer, attempt", attempt + 1);
      } catch (e) {
        if (e instanceof BusyError || attempt === 1) throw e;
        console.warn("[scan-assistant] model error, retrying:", (e as Error).message);
      }
    }
    if (!answer) throw new Error("unparsable answer");
    const suggestions = checkSuggestions(answer.suggestions, dishes);
    await db.rpc("scan_chat_record", { p_token: token, p_question: question, p_reply: answer.reply, p_language: answer.language, p_model: llm.name });
    return json({ reply: answer.reply, language: answer.language, suggestions });
  } catch (e) {
    console.error("[scan-assistant]", e);
    if (e instanceof BusyError) return json({ error: "busy" }, 503);
    return json({ error: "failed" }, 500);
  }
});
