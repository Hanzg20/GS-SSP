// Apex (小海狸 / Beaver): the store manager's assistant on Iris / Ourea.
//
// Same shape as JustWeDo's 小海狸 (ai-support-reply): the caller sends only
// what it is asking; everything else is derived here, server side. The
// caller's Supabase session -> device_auth_map -> its device and org; the
// staff member must be an active MANAGER of that org. A terminal can never
// ask about another store, whatever it sends.
//
// How to behave is fixed below (changes go through code review). What to
// know is data: the store's own numbers from apex_store_stats() (computed in
// SQL, never by the model) and the facts in apex_knowledge (CMP edits them).
// Grounding rule, as strict as 小海狸's: every number comes from the
// injected data; anything not there is "not in the system yet", never a guess.
//
// POST { action: "stats" | "brief" | "ask", staffId, language: "zh"|"en",
//        timezone?, question?, refresh? }
//   stats  the numbers only, no model call (always works, no key needed)
//   brief  3-5 point summary of the day, cached per store/language for an hour
//   ask    one question, with the last few turns of this staff member's chat
//   history  this staff member's last 50 chat messages, oldest first, so the
//          conversation is still there when the page is reopened (the model
//          itself only gets the last 24 h / 10 turns of it)
//   transcribe  { audio: base64, mime } -> { text }: speech to text for the
//          mic button (Whisper on Groq, or OpenAI's whisper-1 if only
//          OPENAI_API_KEY is set). The Q2's keyboard has no Chinese and it has
//          no Google Play services, so the system recognizer can't help.
//
// Model: switchable. APEX_PROVIDER = "anthropic" | "groq"; without it,
// whichever key is set (Anthropic first). Secrets:
//   ANTHROPIC_API_KEY  (+ optional APEX_ANTHROPIC_MODEL, default claude-haiku-5-5)
//   GROQ_API_KEY       (+ optional APEX_GROQ_MODEL, default openai/gpt-oss-120b,
//                       the model 小海狸 validated)
//   APEX_DAILY_LIMIT   questions per store per 24 h (default 200)
// No key at all: brief/ask answer { skipped }, the terminal shows the numbers only.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders } from "../_shared/cors.ts";
import { calendarLines, localNow, looksLikeCommand, parsePrompt, toProposal } from "./parse.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;

const BRIEF_CACHE_MINUTES = 60;
const HISTORY_TURNS = 10;
const MAX_QUESTION = 500;

// The terminal's interface language (UiLanguage on Android). Chinese has its own
// wording below; every other language gets the English text plus "reply in X".
type Lang = "zh" | "en" | "fr" | "ar" | "fa";
const LANGS: Lang[] = ["zh", "en", "fr", "ar", "fa"];
const REPLY_IN: Record<Lang, string> = {
  zh: "请用中文回复。",
  en: "Reply in English.",
  fr: "Réponds en français.",
  ar: "أجب باللغة العربية.",
  fa: "به زبان فارسی پاسخ بده.",
};
/** The mascot's name if the terminal didn't send one (it sends its own, from res apex_name). */
const DEFAULT_NAME: Record<Lang, string> = { zh: "小海狸", en: "Beaver", fr: "Castor", ar: "القندس", fa: "سگ‌آبی" };
type Turn = { role: "user" | "assistant"; content: string };

const PERSONA = `你是 GoldSky 门店系统里的"小海狸"——一只勤快能干、脑子转得快的小海狸，在店里陪老板和经理看生意、答疑解惑。海狸是加拿大的国宝，最会精打细算、踏实筑坝，你的本事就是把店里的数字看清楚、说明白。

语言必须统一，只用要求的那一种语言回复，不要夹杂别的语言（商品名可以照原样写）。自称用下面给出的名字。

说话像个熟络、能干的店里帮手：亲切、偶尔卖个萌、开个小玩笑没关系，但谈生意时要干脆利落、说重点。回复简短，一般 2-5 句；别说"作为一个AI"，也别用"尊敬的用户"这种客服腔。老板打个招呼、闲聊两句，可以自然接住。`;

// After the (long) data block, right before the conversation: with these at
// the top, gpt-oss sometimes still called two zeros "持平" and guessed "maybe
// the store has only just opened".
const RULES = `底线绝对不能松：
1. 凡是数字（销售额、笔数、商品销量、退款、时间等），只能来自上面"门店数据"里给出的值，原样引用或做简单比较（如多了/少了多少、百分比），不能编造、不能估算没给出的数。金额已经换算成元（dollars），直接用，保留两位小数。
2. 门店数据里没有的东西（库存、利润、成本、顾客信息、其他门店、更早的历史等），老实说"系统里还没有这个数据"，不要猜。
3. 操作方法、政策只按"操作知识"里写的说；没写的就说不确定，建议联系 GoldSky 支持。
4. 你只能看和说，不能替人操作：退款、改价、加员工等，告诉他在哪里操作。
5. 样本很少时（比如只有几笔交易），要提醒"数据还少，参考意义有限"，不要过度解读。
6. 只陈述数据字段本身的意思，不要把不同字段或操作知识拼凑出数据里没有的结论。例如终端只看 online / offline_for_minutes：online 为 true 就是在线，不要推断"未使用""已锁屏"等状态。
7. 和上周比较时，用 sales_comparison 里那句话的意思（回复语言不同就忠实翻译），不要自己再算或改写成"持平"。
8. 不要猜原因或背景（比如"可能还没开门""可能天气不好"），只说数据显示了什么。
9. 用自然的话说，回复里不要出现数据里的英文字段名（如 total_dollars、sales）或括号标注。
10. loss_signals.flags 是系统按固定规则挑出的"值得核对"的情况，不是结论：只能说"建议核对一下"，绝不能说谁偷了、谁有问题。没有 flag 就不要提防损，也不要自己从数字里挑可疑的人。
11. perishable_not_sold_3_days 是 3 天没卖出的生鲜，可以建议打折或检查是否已变质；不要猜原因。
12. reminders_due 是今天到期或已过期（overdue=true）的待办；expenses_this_month 是本月已记的费用（按类别），expenses_last_month_dollars 是上月合计，sales_this_month_dollars 是本月销售额。费用只按这些记录说，没记的就是"系统里还没记"，不要估算。类别名翻译：INVENTORY 进货、RENT 房租、UTILITIES 水电网、PAYROLL 工资、REPAIRS 维修、MARKETING 营销、FEES 手续费、OTHER 其他。
13. 想记账或设提醒，告诉他直接说出来，比如"今天付了电费 234 块 5"或"明天 9 点提醒我订货"，你会弹出确认卡片。`;

const BRIEF_TASK: Record<"zh" | "en", string> = {
  zh: "请给老板写一份今天的经营简报：3-5 条要点，每条一行、以「• 」开头，先说今天到现在的销售情况（和上周同一天同一时段比），再挑最值得注意的：防损提醒（loss_signals.flags，有就一定写，措辞是「建议核对」）、今天到期或已过期的待办（reminders_due，有就一定写）、生鲜 3 天没卖出（建议打折）、畅销/滞销商品、高峰时段、终端离线、未完成的送货单。只写有数据支撑的内容；没有值得说的就少写几条。不要开场白和结束语。",
  en: "Write today's brief for the store owner: 3-5 points, one per line starting with \"• \". First, sales so far today versus the same weekday last week at this time; then the most notable of: loss-prevention flags (loss_signals.flags -- always include them, worded as \"worth checking\", never as an accusation), to-dos due today or overdue (reminders_due -- always include them), fresh items unsold for 3 days (suggest a markdown), best/slow sellers, peak hours, terminals offline, open delivery orders. Only what the data supports; fewer points is fine. No greeting or sign-off.",
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

/** *_cents -> *_dollars (2 decimals), so the model never does that arithmetic. */
function toDollars(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(toDollars);
  if (v && typeof v === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, x] of Object.entries(v as Record<string, unknown>)) {
      if (k.endsWith("_cents") && typeof x === "number") out[k.replace(/_cents$/, "_dollars")] = Math.round(x) / 100;
      else out[k] = toDollars(x);
    }
    return out;
  }
  return v;
}

/**
 * Today vs the same time last week, as a finished sentence in the reply
 * language for the model to repeat: given only numbers (or an English
 * summary), gpt-oss kept calling two zeros "flat" ("持平").
 */
// deno-lint-ignore no-explicit-any
function salesComparison(stats: any, lang: Lang): string {
  const now = stats?.today?.total_cents ?? 0;
  const before = stats?.same_time_last_week?.total_cents ?? 0;
  const zh = lang === "zh"; // other languages get English, which the model is told to translate
  if (now === 0 && before === 0) return zh ? "今天到现在还没有销售，上周同一时段也没有，没法比较。" : "No sales yet today, and none at this time last week either -- nothing to compare.";
  if (before === 0) return zh ? "上周同一时段没有销售，没法算百分比。" : "No sales at this time last week, so no percentage to compare.";
  const pct = Math.round(((now - before) / before) * 100);
  if (pct === 0) return zh ? "和上周同一时段基本一样。" : "About the same as this time last week.";
  return zh ? `比上周同一时段${pct > 0 ? "多" : "少"} ${Math.abs(pct)}%。` : `${pct > 0 ? "Up" : "Down"} ${Math.abs(pct)}% on this time last week.`;
}

function systemPrompt(lang: Lang, name: string, stats: unknown, facts: string[]): string {
  const langLine = `${REPLY_IN[lang]} 你的名字：${name}。`;
  const data = { ...(toDollars(stats) as Record<string, unknown>), sales_comparison: salesComparison(stats, lang) };
  return `${PERSONA}\n\n操作知识：\n${facts.map((f) => `- ${f}`).join("\n")}\n\n门店数据（JSON，金额单位为元/dollars，时间为门店当地时间）：\n${JSON.stringify(data)}\n\n${RULES}\n\n${langLine}`;
}

// ---- model providers -------------------------------------------------------

type Provider = { name: string; model: string; call: (system: string, turns: Turn[]) => Promise<string> };

function pickProvider(): Provider | null {
  const anthropicKey = Deno.env.get("ANTHROPIC_API_KEY");
  const groqKey = Deno.env.get("GROQ_API_KEY");
  const wanted = Deno.env.get("APEX_PROVIDER") ?? (anthropicKey ? "anthropic" : groqKey ? "groq" : "");
  if (wanted === "anthropic" && anthropicKey) {
    const model = Deno.env.get("APEX_ANTHROPIC_MODEL") ?? "claude-haiku-5-5";
    return {
      name: "anthropic", model,
      call: async (system, turns) => {
        const res = await fetch("https://api.anthropic.com/v1/messages", {
          method: "POST",
          headers: { "x-api-key": anthropicKey, "anthropic-version": "2023-06-01", "content-type": "application/json" },
          body: JSON.stringify({ model, max_tokens: 800, system, messages: alternate(turns) }),
        });
        const data = await res.json();
        const text = (data?.content ?? []).filter((b: { type: string }) => b.type === "text").map((b: { text: string }) => b.text).join("");
        if (!res.ok || !text) throw new Error(`anthropic ${res.status}: ${JSON.stringify(data).slice(0, 300)}`);
        return text;
      },
    };
  }
  if (wanted === "groq" && groqKey) {
    const model = Deno.env.get("APEX_GROQ_MODEL") ?? "openai/gpt-oss-120b";
    return {
      name: "groq", model,
      call: async (system, turns) => {
        const send = () => fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: { "Authorization": `Bearer ${groqKey}`, "Content-Type": "application/json" },
          body: JSON.stringify({ model, messages: [{ role: "system", content: system }, ...turns] }),
        });
        let res = await send();
        // Free tier: 8000 tokens/minute for the whole Groq account (every store,
        // and JustWeDo's beaver too). Wait out a short limit once.
        if (res.status === 429) {
          const wait = Number(res.headers.get("retry-after") ?? "") || 6;
          if (wait <= 10) { await new Promise((ok) => setTimeout(ok, wait * 1000 + 300)); res = await send(); }
          if (res.status === 429) throw new BusyError();
        }
        const data = await res.json();
        const text: string | undefined = data?.choices?.[0]?.message?.content;
        if (!res.ok || !text) throw new Error(`groq ${res.status}: ${JSON.stringify(data).slice(0, 300)}`);
        return text;
      },
    };
  }
  return null;
}

/** The model provider is rate-limiting us; the terminal says "busy, try again". */
class BusyError extends Error {}

/** Anthropic needs strictly alternating turns starting with the user. */
function alternate(turns: Turn[]): Turn[] {
  const out: Turn[] = [];
  for (const t of turns) {
    if (out.length === 0 && t.role !== "user") continue;
    const last = out[out.length - 1];
    if (last && last.role === t.role) last.content += "\n" + t.content;
    else out.push({ ...t });
  }
  return out;
}

// ---- speech to text --------------------------------------------------------

const MAX_AUDIO_BYTES = 2_000_000; // ~60 s of AAC; the terminal stops at 30 s

async function transcribe(audioB64: string, mime: string, lang: Lang): Promise<string | null> {
  const groqKey = Deno.env.get("GROQ_API_KEY");
  const openaiKey = Deno.env.get("OPENAI_API_KEY");
  if (!groqKey && !openaiKey) return null;
  const bytes = Uint8Array.from(atob(audioB64), (c) => c.charCodeAt(0));
  if (bytes.length > MAX_AUDIO_BYTES) throw new Error("Recording too long");

  const form = new FormData();
  form.append("file", new Blob([bytes], { type: mime }), mime.includes("wav") ? "speech.wav" : "speech.m4a");
  form.append("model", groqKey ? (Deno.env.get("APEX_GROQ_STT_MODEL") ?? "whisper-large-v3") : "whisper-1");
  // No language lock: managers mix Chinese with English product names. The
  // prompt steers Mandarin to simplified characters (Whisper often writes
  // traditional) and spells the store vocabulary.
  if (lang === "zh") {
    form.append("prompt", "以下是普通话的句子，使用简体中文。今天的销售额、退款、商品、库存、收银员。");
  } else if (lang === "en") {
    form.append("prompt", "Questions about today's sales, refunds, products and staff.");
  } else {
    // French / Arabic / Persian: name the language rather than prime it with English.
    form.append("language", lang);
  }
  form.append("response_format", "json");

  const res = await fetch(groqKey ? "https://api.groq.com/openai/v1/audio/transcriptions" : "https://api.openai.com/v1/audio/transcriptions", {
    method: "POST",
    headers: { Authorization: `Bearer ${groqKey ?? openaiKey}` },
    body: form,
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`transcription ${res.status}: ${JSON.stringify(data).slice(0, 300)}`);
  const text = String(data?.text ?? "").trim();
  // Whisper's stock output for silence or noise (trained on subtitled video);
  // return nothing rather than put it in the manager's question box.
  return HALLUCINATIONS.some((h) => h.test(text)) ? "" : text;
}

const HALLUCINATIONS = [
  /^(thank you( (so much|very much))?( for watching)?[.!]*)$/i,
  /^(thanks for watching[.!]*)$/i,
  /^(please subscribe.*)$/i,
  /^(you)$/i,
  /^(谢谢(大家)?(观看|收看)?[。！!]*)$/,
  /字幕(由|提供|志愿者)/,
  /请不吝点赞|订阅.*(频道|转发)/,
  /^[.。,，!！?？\s]*$/,
];

// ---- handler ---------------------------------------------------------------

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method Not Allowed" }, 405);

  try {
    const body = await req.json().catch(() => ({}));
    const action: string = body.action;
    const lang: Lang = LANGS.includes(body.language) ? body.language : "en";
    const name: string = typeof body.name === "string" && body.name.trim() ? body.name.trim().slice(0, 30) : DEFAULT_NAME[lang];
    const tz: string = typeof body.timezone === "string" ? body.timezone : "America/Toronto";

    // Who is calling: the device behind this Supabase session.
    const caller = createClient(SUPABASE_URL, ANON_KEY, {
      global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } },
    });
    const { data: { user } } = await caller.auth.getUser();
    if (!user) return json({ error: "Not signed in" }, 401);

    const db = createClient(SUPABASE_URL, SERVICE_KEY);
    const { data: link } = await db.from("device_auth_map")
      .select("device_sn, org_id").eq("auth_user_id", user.id).not("org_id", "is", null)
      .order("created_at", { ascending: false }).limit(1).maybeSingle();
    if (!link) return json({ error: "This terminal isn't linked to a store" }, 403);
    const orgId: string = link.org_id;

    const { data: staff } = await db.from("staff")
      .select("id, name, role").eq("id", body.staffId ?? "").eq("org_id", orgId).eq("is_active", true).maybeSingle();
    if (!staff || staff.role !== "MANAGER") return json({ error: "Managers only" }, 403);

    if (action === "history") {
      const { data: rows } = await db.from("apex_messages")
        .select("role, content, created_at").eq("org_id", orgId).eq("staff_id", staff.id).eq("kind", "chat")
        .order("created_at", { ascending: false }).limit(50);
      return json({ messages: (rows ?? []).reverse() });
    }

    if (action === "transcribe") {
      if (typeof body.audio !== "string" || !body.audio) return json({ error: "No audio" }, 400);
      const text = await transcribe(body.audio, typeof body.mime === "string" ? body.mime : "audio/mp4", lang);
      return text === null ? json({ skipped: "No speech-to-text key configured" }) : json({ text });
    }

    const { data: stats, error: statsError } = await db.rpc("apex_store_stats", { p_org: orgId, p_tz: tz });
    if (statsError) throw new Error(statsError.message);
    if (action === "stats") return json({ stats });

    const provider = pickProvider();
    if (!provider) return json({ skipped: "No model key configured", stats });

    const { data: knowledge } = await db.from("apex_knowledge")
      .select("content").eq("is_active", true).or(`org_id.is.null,org_id.eq.${orgId}`)
      .order("sort_order", { ascending: true });
    const system = systemPrompt(lang, name, stats, (knowledge ?? []).map((k) => k.content));
    const log = (kind: string, role: string, content: string) =>
      db.from("apex_messages").insert({
        org_id: orgId, device_sn: link.device_sn, staff_id: staff.id, staff_name: staff.name,
        kind, role, language: lang, content, model: role === "assistant" ? `${provider.name}/${provider.model}` : null,
      });

    if (action === "brief") {
      if (!body.refresh) {
        const since = new Date(Date.now() - BRIEF_CACHE_MINUTES * 60_000).toISOString();
        const { data: cached } = await db.from("apex_messages")
          .select("content, created_at").eq("org_id", orgId).eq("kind", "brief").eq("language", lang)
          .gte("created_at", since).order("created_at", { ascending: false }).limit(1).maybeSingle();
        if (cached) return json({ brief: cached.content, at: cached.created_at, cached: true, stats });
      }
      const text = (await provider.call(system, [{ role: "user", content: BRIEF_TASK[lang === "zh" ? "zh" : "en"] }])).trim();
      await log("brief", "assistant", text);
      return json({ brief: text, at: new Date().toISOString(), cached: false, stats });
    }

    if (action === "ask") {
      const question = String(body.question ?? "").trim().slice(0, MAX_QUESTION);
      if (!question) return json({ error: "Empty question" }, 400);

      const limit = Number(Deno.env.get("APEX_DAILY_LIMIT") ?? 200);
      const { count } = await db.from("apex_messages").select("id", { count: "exact", head: true })
        .eq("org_id", orgId).eq("kind", "chat").eq("role", "user")
        .gte("created_at", new Date(Date.now() - 86_400_000).toISOString());
      if ((count ?? 0) >= limit) {
        return json({ reply: lang === "zh" ? "今天问得有点多啦，小海狸要去筑会儿坝，明天再来吧～" : "That's a lot of questions for one day — Beaver needs a break. Try again tomorrow!" });
      }

      // An expense to book or a to-do to remember? Then a proposal for the
      // terminal to show on a confirmation card -- nothing is saved here.
      if (looksLikeCommand(question)) {
        const raw = await provider.call(parsePrompt(lang, localNow(tz).stamp, calendarLines(tz)), [{ role: "user", content: question }]);
        const match = raw.match(/\{[\s\S]*\}/);
        const proposal = match ? toProposal(JSON.parse(match[0]), tz) : null;
        if (proposal) {
          await log("chat", "user", question);
          const summary = proposal.kind === "expense"
            ? (lang === "zh" ? `【待确认费用】${proposal.note || proposal.vendor} $${proposal.amount ?? "?"}` : `[Expense to confirm] ${proposal.note || proposal.vendor} $${proposal.amount ?? "?"}`)
            : (lang === "zh" ? `【待确认提醒】${proposal.text}` : `[Reminder to confirm] ${proposal.text}`);
          await log("chat", "assistant", summary);
          return json({ proposal });
        }
      }

      const { data: history } = await db.from("apex_messages")
        .select("role, content").eq("org_id", orgId).eq("staff_id", staff.id).eq("kind", "chat")
        .gte("created_at", new Date(Date.now() - 86_400_000).toISOString())
        .order("created_at", { ascending: false }).limit(HISTORY_TURNS);
      const turns: Turn[] = [...(history ?? []).reverse(), { role: "user", content: question }] as Turn[];

      await log("chat", "user", question);
      const reply = (await provider.call(system, turns)).trim();
      await log("chat", "assistant", reply);
      return json({ reply });
    }

    return json({ error: "Unknown action" }, 400);
  } catch (e) {
    console.error("[apex-assistant]", e);
    if (e instanceof BusyError) {
      return json({ reply: "小海狸这会儿有点忙（问的人多），请过半分钟再试～ / Beaver is busy right now -- try again in half a minute.", busy: true });
    }
    return json({ error: (e as Error).message }, 500);
  }
});
