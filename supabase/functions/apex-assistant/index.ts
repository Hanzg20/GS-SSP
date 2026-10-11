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

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;

const BRIEF_CACHE_MINUTES = 60;
const HISTORY_TURNS = 10;
const MAX_QUESTION = 500;

type Lang = "zh" | "en";
type Turn = { role: "user" | "assistant"; content: string };

const PERSONA = `你是 GoldSky 门店系统里的"小海狸"——一只勤快能干、脑子转得快的小海狸，在店里陪老板和经理看生意、答疑解惑。海狸是加拿大的国宝，最会精打细算、踏实筑坝，你的本事就是把店里的数字看清楚、说明白。

语言必须统一，不能中英混用：用中文回复时，自称"小海狸"；用英文回复时，自称"Beaver"。

说话像个熟络、能干的店里帮手：亲切、偶尔卖个萌、开个小玩笑没关系，但谈生意时要干脆利落、说重点。回复简短，一般 2-5 句；别说"作为一个AI"，也别用"尊敬的用户"这种客服腔。老板打个招呼、闲聊两句，可以自然接住。

底线绝对不能松：
1. 凡是数字（销售额、笔数、商品销量、退款、时间等），只能来自下面"门店数据"里给出的值，原样引用或做简单比较（如多了/少了多少、百分比），不能编造、不能估算没给出的数。金额已经换算成元（dollars），直接用，保留两位小数。
2. 门店数据里没有的东西（库存、利润、成本、顾客信息、其他门店、更早的历史等），老实说"系统里还没有这个数据"，不要猜。
3. 操作方法、政策只按"操作知识"里写的说；没写的就说不确定，建议联系 GoldSky 支持。
4. 你只能看和说，不能替人操作：退款、改价、加员工等，告诉他在哪里操作。
5. 样本很少时（比如只有几笔交易），要提醒"数据还少，参考意义有限"，不要过度解读。`;

const BRIEF_TASK: Record<Lang, string> = {
  zh: "请给老板写一份今天的经营简报：3-5 条要点，每条一行、以「• 」开头，先说今天到现在的销售情况（和上周同一天同一时段比），再挑最值得注意的：畅销/滞销商品、高峰时段、退款或拒付、终端离线、未完成的送货单。只写有数据支撑的内容；没有值得说的就少写几条。不要开场白和结束语。",
  en: "Write today's brief for the store owner: 3-5 points, one per line starting with \"• \". First, sales so far today versus the same weekday last week at this time; then the most notable of: best/slow sellers, peak hours, refunds or declines, terminals offline, open delivery orders. Only what the data supports; fewer points is fine. No greeting or sign-off.",
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

function systemPrompt(lang: Lang, stats: unknown, facts: string[]): string {
  const langLine = lang === "zh" ? "请用中文回复。" : "Reply in English.";
  return `${PERSONA}\n\n${langLine}\n\n操作知识：\n${facts.map((f) => `- ${f}`).join("\n")}\n\n门店数据（JSON，金额单位为元/dollars，时间为门店当地时间）：\n${JSON.stringify(toDollars(stats))}`;
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
        const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: { "Authorization": `Bearer ${groqKey}`, "Content-Type": "application/json" },
          body: JSON.stringify({ model, messages: [{ role: "system", content: system }, ...turns] }),
        });
        const data = await res.json();
        const text: string | undefined = data?.choices?.[0]?.message?.content;
        if (!res.ok || !text) throw new Error(`groq ${res.status}: ${JSON.stringify(data).slice(0, 300)}`);
        return text;
      },
    };
  }
  return null;
}

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

// ---- handler ---------------------------------------------------------------

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method Not Allowed" }, 405);

  try {
    const body = await req.json().catch(() => ({}));
    const action: string = body.action;
    const lang: Lang = body.language === "en" ? "en" : "zh";
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

    const { data: stats, error: statsError } = await db.rpc("apex_store_stats", { p_org: orgId, p_tz: tz });
    if (statsError) throw new Error(statsError.message);
    if (action === "stats") return json({ stats });

    const provider = pickProvider();
    if (!provider) return json({ skipped: "No model key configured", stats });

    const { data: knowledge } = await db.from("apex_knowledge")
      .select("content").eq("is_active", true).or(`org_id.is.null,org_id.eq.${orgId}`)
      .order("sort_order", { ascending: true });
    const system = systemPrompt(lang, stats, (knowledge ?? []).map((k) => k.content));
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
      const text = (await provider.call(system, [{ role: "user", content: BRIEF_TASK[lang] }])).trim();
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
    return json({ error: (e as Error).message }, 500);
  }
});
