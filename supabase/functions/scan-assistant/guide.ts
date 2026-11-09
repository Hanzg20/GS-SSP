// Pure logic of the Ourea Scan AI guide: what the model sees about the menu,
// what it must obey, and how its answer is checked before the phone gets it.

export type Option = { id: string; name: string; price_cents?: number };
export type Group = { id: string; name: string; options: Option[]; max_select?: number | null; required?: boolean };
export type Dish = {
  id: string; name: string; price_cents: number; category?: string | null;
  description?: string | null; ingredients?: string | null;
  allergens?: string[] | null; dietary?: string[] | null; spice_level?: number | null; guide?: string | null;
  modifier_groups?: Group[]; sold_out?: boolean;
};
export type Suggestion = { product_id: string; quantity: number; option_ids: string[]; why?: string; needs_options?: boolean };

const money = (c: number) => `$${(c / 100).toFixed(2)}`;

/** One compact line per dish; allergens absent = NOT RECORDED (never "none"). */
export function menuLines(dishes: Dish[]): string {
  return dishes.map((d) => {
    const parts = [
      `id=${d.id}`, d.name, money(d.price_cents),
      d.category ? `category: ${d.category}` : null,
      d.sold_out ? "SOLD OUT" : null,
      d.description ? `about: ${d.description}` : null,
      d.ingredients ? `ingredients: ${d.ingredients}` : null,
      `allergens: ${Array.isArray(d.allergens) ? (d.allergens.length ? d.allergens.join(", ") : "none of the priority allergens") : "NOT RECORDED"}`,
      d.dietary?.length ? `dietary: ${d.dietary.join(", ")}` : null,
      typeof d.spice_level === "number" ? `spice ${d.spice_level}/5` : null,
      d.guide ? `how to eat: ${d.guide}` : null,
      ...(d.modifier_groups ?? []).map((g) =>
        `options "${g.name}"${g.required ? " (required" : " ("}${g.max_select === 1 ? ", pick one)" : g.max_select ? `, up to ${g.max_select})` : ")"}: ` +
        g.options.map((o) => `${o.id}=${o.name}${o.price_cents ? ` +${money(o.price_cents)}` : ""}`).join("; ")),
    ];
    return "- " + parts.filter(Boolean).join(" | ");
  }).join("\n");
}

export function systemPrompt(store: string, table: string, dishes: Dish[], browserLang: string): string {
  return `You are Beaver (小海狸 in Chinese), the friendly AI menu guide of "${store}", talking to a customer at table ${table} on their phone.

LANGUAGE: reply in the language of the customer's LATEST message (French -> French, Japanese -> Japanese, Arabic -> Arabic, ...). If it is too short to tell, use the phone's language "${browserLang}". Keep each dish name as written on the menu, and explain it in the customer's language.

MENU (the only dishes that exist):
${menuLines(dishes)}

RULES (never break):
1. Talk only about these dishes. Never invent a dish, price, ingredient, allergen or dietary fact. Unknown -> say you don't know and suggest asking a server.
2. Allergies and diets: use only the "allergens" and "dietary" data. "allergens: NOT RECORDED" means unknown, not safe. Whenever an allergy is mentioned, end with a reminder to tell the server, because kitchens can have cross-contact. Never promise a dish is 100% safe.
3. Halal, vegetarian, vegan, no pork/beef, gluten-free: only for dishes whose "dietary" data says so. A dish without that tag is "not labelled" -- even if its name sounds meat-free (tofu, vegetables, soy milk), never call it vegetarian/halal/etc. or recommend it as such; say it isn't labelled and the server can confirm.
4. You cannot place orders. You may suggest dishes; the customer taps "Add" to put them in their cart, and a server confirms the order.
5. Suggest SOLD OUT dishes never. For quantities, think of the party size and courses (starter, mains, drinks).
6. Be warm and brief like a good server: under 100 words, no markdown tables. Off-topic questions: politely bring the talk back to the meal.

ANSWER FORMAT: a JSON object only, no other text:
{"reply": "<your answer in the customer's language>",
 "language": "<BCP 47 code of the language you replied in>",
 "suggestions": [{"product_id": "<menu id>", "quantity": <1-10>, "option_ids": ["<option id>", ...], "why": "<a few words, customer's language>"}]}
"suggestions" is [] unless you are recommending specific dishes. Include option_ids only for options the customer asked for or that are required (choose the mildest/default sensible one and say so in "why").`;
}

/** Pulls the JSON object out of the model's text (some providers wrap it). */
export function parseAnswer(raw: string): { reply: string; language: string; suggestions: unknown[] } | null {
  const m = raw.match(/\{[\s\S]*\}/);
  if (!m) return null;
  try {
    const o = JSON.parse(m[0]);
    if (typeof o?.reply !== "string" || !o.reply.trim()) return null;
    return { reply: o.reply.trim(), language: typeof o.language === "string" ? o.language.slice(0, 20) : "", suggestions: Array.isArray(o.suggestions) ? o.suggestions : [] };
  } catch {
    return null;
  }
}

/**
 * Keeps only suggestions the phone can act on: real, not sold out, quantity
 * 1-10, options that belong to the dish. If the options don't satisfy the
 * dish's rules, they're dropped and the phone opens the options sheet.
 */
export function checkSuggestions(raw: unknown[], dishes: Dish[]): Suggestion[] {
  const byId = new Map(dishes.map((d) => [d.id, d]));
  const out: Suggestion[] = [];
  for (const s of raw.slice(0, 6)) {
    const o = s as Record<string, unknown>;
    const d = byId.get(String(o?.product_id ?? ""));
    if (!d || d.sold_out || out.some((x) => x.product_id === d.id)) continue;
    const quantity = Math.min(10, Math.max(1, Math.round(Number(o.quantity) || 1)));
    const groups = d.modifier_groups ?? [];
    const valid = new Set(groups.flatMap((g) => g.options.map((x) => x.id)));
    let optionIds = (Array.isArray(o.option_ids) ? o.option_ids.map(String) : []).filter((id) => valid.has(id));
    const fits = groups.every((g) => {
      const n = g.options.filter((x) => optionIds.includes(x.id)).length;
      return !(g.required && n === 0) && !(g.max_select && n > g.max_select);
    });
    if (!fits) optionIds = [];
    out.push({
      product_id: d.id, quantity, option_ids: optionIds,
      why: typeof o.why === "string" ? o.why.slice(0, 140) : undefined,
      needs_options: !fits,
    });
  }
  return out;
}

// ---------------------------------------------------------------- one-sentence ordering

/** A line Beaver heard: becomes a cart line only when the customer confirms it on the phone. */
export type OrderLine = { product_id: string; quantity: number; option_ids: string[]; note?: string; needs_options?: boolean };
/** A phrase that fits more than one dish ("a cold dish"): the phone asks which. */
export type OrderChoice = { heard: string; product_ids: string[] };

export function orderPrompt(store: string, table: string, dishes: Dish[], browserLang: string): string {
  return `You are Beaver (小海狸), the order taker of "${store}", table ${table}. The customer said or typed what they want in ONE message, in any language. Turn it into order lines.

MENU (the only dishes that exist):
${menuLines(dishes)}

RULES (never break):
1. Use only menu ids. Never invent a dish or option. A dish that isn't on the menu or is SOLD OUT goes in "missing" in the customer's words.
2. A phrase that matches exactly one dish (by name in any language, or a category with one dish) -> a line. A phrase that fits several dishes ("a cold dish", "dumplings", "a drink") -> "choices" with those ids (max 4); never pick for them.
3. Quantities as said ("two bowls", "两碗", "a couple" = 2); unsaid = 1. Max 10 per line.
4. Options: only option ids the customer asked for ("mild", "微辣", "add egg"). Never choose a required option yourself; leave it out and the phone asks.
5. Notes: kitchen wishes that aren't options ("no green onion", "不要葱", "sauce on the side") go in "note", short, in the customer's words. If only some of the portions differ, split them and keep the total the customer said: "two noodles, one without onion" = 2 bowls in all -> {quantity 1, note "no onion"} + {quantity 1, no note}. NEVER {quantity 2} + {quantity 1}: that would be 3 bowls.
6. "reply": one short sentence in the customer's language saying what you got (and asking about choices/missing if any). Language: the customer's message; too short to tell -> "${browserLang}".

ANSWER FORMAT: a JSON object only:
{"reply": "...", "language": "<BCP 47>",
 "lines": [{"product_id": "<id>", "quantity": <1-10>, "option_ids": ["<option id>"], "note": "<or empty>"}],
 "choices": [{"heard": "<customer's words>", "product_ids": ["<id>", "<id>"]}],
 "missing": ["<customer's words>"]}`;
}

/** The model's order answer, or null when it isn't one. */
export function parseOrder(raw: string): { reply: string; language: string; lines: unknown[]; choices: unknown[]; missing: unknown[] } | null {
  const m = raw.match(/\{[\s\S]*\}/);
  if (!m) return null;
  try {
    const o = JSON.parse(m[0]);
    if (typeof o?.reply !== "string" || !o.reply.trim()) return null;
    const arr = (v: unknown) => (Array.isArray(v) ? v : []);
    return { reply: o.reply.trim(), language: typeof o.language === "string" ? o.language.slice(0, 20) : "", lines: arr(o.lines), choices: arr(o.choices), missing: arr(o.missing) };
  } catch {
    return null;
  }
}

/**
 * Only lines the phone can put in the cart: real dish, not sold out, quantity
 * 1-10, options that belong to it. Options that break the dish's rules are
 * dropped and the line says so (the customer picks them). The same dish may
 * come twice (different notes). Choices keep only real, orderable dishes.
 */
export function checkOrder(raw: { lines: unknown[]; choices: unknown[]; missing: unknown[] }, dishes: Dish[]):
  { lines: OrderLine[]; choices: OrderChoice[]; missing: string[] } {
  const byId = new Map(dishes.map((d) => [d.id, d]));
  const lines: OrderLine[] = [];
  const missing: string[] = raw.missing.filter((x) => typeof x === "string" && x.trim()).map((x) => (x as string).trim().slice(0, 60)).slice(0, 5);
  for (const s of raw.lines.slice(0, 12)) {
    const o = s as Record<string, unknown>;
    const d = byId.get(String(o?.product_id ?? ""));
    if (!d) continue;
    if (d.sold_out) { if (!missing.includes(d.name)) missing.push(d.name); continue; }
    const quantity = Math.min(10, Math.max(1, Math.round(Number(o.quantity) || 1)));
    const groups = d.modifier_groups ?? [];
    const valid = new Set(groups.flatMap((g) => g.options.map((x) => x.id)));
    let optionIds = [...new Set((Array.isArray(o.option_ids) ? o.option_ids.map(String) : []).filter((id) => valid.has(id)))];
    const tooMany = groups.some((g) => g.max_select && g.options.filter((x) => optionIds.includes(x.id)).length > g.max_select);
    if (tooMany) optionIds = [];
    const needs = groups.some((g) => g.required && !g.options.some((x) => optionIds.includes(x.id)));
    const note = typeof o.note === "string" ? o.note.trim().slice(0, 100) : "";
    lines.push({ product_id: d.id, quantity, option_ids: optionIds, ...(note ? { note } : {}), ...(needs || tooMany ? { needs_options: true } : {}) });
  }
  const choices: OrderChoice[] = [];
  for (const c of raw.choices.slice(0, 4)) {
    const o = c as Record<string, unknown>;
    const ids = [...new Set((Array.isArray(o?.product_ids) ? o.product_ids.map(String) : []))]
      .filter((id) => byId.has(id) && !byId.get(id)!.sold_out).slice(0, 4);
    if (ids.length === 1) lines.push({ product_id: ids[0], quantity: 1, option_ids: [], ...((byId.get(ids[0])!.modifier_groups ?? []).some((g) => g.required) ? { needs_options: true } : {}) });
    else if (ids.length > 1) choices.push({ heard: String(o.heard ?? "").slice(0, 40), product_ids: ids });
  }
  return { lines, choices, missing };
}

// ---------------------------------------------------------------- the table's menu / enough, not too much

export type Party = { size: number | null; spice: "none" | "mild" | "any" | null };
export type PlanLine = { product_id: string; quantity: number };

const spiceWords = { none: "no spice at all", mild: "mild is fine, nothing hot", any: "any spice is fine" } as const;

function partyLines(party: Party, avoid: string[]): string {
  return [
    party.size ? `Party: ${party.size} ${party.size === 1 ? "person" : "people"}.` : "Party size: not given (assume 2).",
    party.spice ? `Spice: ${spiceWords[party.spice]}.` : null,
    avoid.length ? `Allergies to avoid: ${avoid.join(", ")}. Never suggest a dish whose allergens include these; a dish with allergens NOT RECORDED may be suggested only if you say it isn't labelled and the server must confirm.` : null,
  ].filter(Boolean).join("\n");
}

function listLines(lines: PlanLine[], byId: Map<string, Dish>): string {
  return lines.length ? lines.map((l) => `- ${l.quantity} x ${byId.get(l.product_id)?.name ?? l.product_id}`).join("\n") : "(nothing)";
}

/** One portion rule for both "set us up" and "is this enough?", so Beaver never contradicts itself. */
const PORTION_RULE = `PORTIONS: a bowl of noodles, a rice dish or a plate of dumplings is one MAIN (a plate of 12 dumplings feeds one or two). Starters (cold dishes, small plates) are extra; drinks are not food.
For N people: N mains is plenty; N-1 mains plus a shared starter is also enough for 4 or more. Short = fewer mains than that. Too much = more than N+1 mains, or more than 2 starters for 4 or fewer.`;

const replyLang = (lang: string) => (lang === "zh" ? "Chinese" : `the language of the phone (${lang})`);

/** "Set us up": a first round sized for the party, from the real menu. */
export function startPrompt(store: string, dishes: Dish[], party: Party, avoid: string[], lang: string): string {
  return `You are Beaver (小海狸), the server of "${store}". A table just sat down. Put together a sensible first order for them from the menu.

MENU (the only dishes that exist):
${menuLines(dishes)}

THE TABLE:
${partyLines(party, avoid)}

${PORTION_RULE}

RULES (never break):
1. Only menu ids, never SOLD OUT. Never invent dishes or facts.
2. Enough, not too much (see PORTIONS): for 4 or more people, N-1 mains plus one shared starter; otherwise N mains. Drinks: one kind, quantity = people, only if the menu has drinks. Most tables order more later, so lean towards less.
   Example for 4 people: 1 cold starter + 2 noodles + 1 plate of dumplings + 4 drinks. NOT 4 noodles + 2 plates of dumplings.
3. Spice: respect it. For "no spice" pick the not-spicy option when a dish has a spice choice; never suggest dishes with spice 2/5 or more.
4. Variety: different dishes, not the same one twice.
5. "reply": 1-2 short sentences in ${replyLang(lang)}, warm, saying why this fits them. No prices in the text.

ANSWER FORMAT: a JSON object only:
{"reply": "...", "language": "<BCP 47>",
 "suggestions": [{"product_id": "<id>", "quantity": <1-10>, "option_ids": ["<option id>"], "why": "<a few words>"}]}`;
}

/** "Is this enough?": honest -- says so when it's enough or too much, suggests only when something is missing. */
export function enoughPrompt(store: string, dishes: Dish[], party: Party, avoid: string[], cart: PlanLine[], ordered: PlanLine[], lang: string): string {
  const byId = new Map(dishes.map((d) => [d.id, d]));
  return `You are Beaver (小海狸), the server of "${store}". The table asks: is what we picked enough? Answer like an honest server who wants them to enjoy the meal and not waste food, never to sell more.

MENU (the only dishes that exist):
${menuLines(dishes)}

THE TABLE:
${partyLines(party, avoid)}

ALREADY ORDERED (in the kitchen or on the way):
${listLines(ordered, byId)}

IN THE CART NOW (about to order):
${listLines(cart, byId)}

${PORTION_RULE}

RULES (never break):
1. Count the mains and starters in ordered + cart together and judge them with PORTIONS.
2. "verdict": "enough" (meets PORTIONS), "more" (short by PORTIONS), or "less" (too much by PORTIONS: say which dish to cut and by how many). Borderline = "enough".
3. Suggest dishes ONLY when the verdict is "more", and only what is missing to reach PORTIONS, no more (4 people with 1 main -> 2 more mains, or 2 mains + a starter; never 4). At most 3 lines, real menu ids, not SOLD OUT, respecting spice and allergies. For "enough" and "less", "suggestions" is [].
4. "reply": 1-2 short sentences in ${replyLang(lang)}. Plain and kind. No prices. For "less", the reply MUST name the dish to cut and by how many (e.g. "Two noodles is plenty for two: drop one Braised Beef Noodle.").

ANSWER FORMAT: a JSON object only:
{"reply": "...", "language": "<BCP 47>", "verdict": "enough" | "more" | "less",
 "suggestions": [{"product_id": "<id>", "quantity": <1-10>, "option_ids": ["<option id>"], "why": "<a few words>"}]}`;
}

/** The verdict word from the model's answer, or null. */
export function readVerdict(raw: string): "enough" | "more" | "less" | null {
  const m = raw.match(/"verdict"\s*:\s*"(enough|more|less)"/);
  return m ? (m[1] as "enough" | "more" | "less") : null;
}

/** Plan suggestions: the usual checks, then the table's allergies and spice enforced here, not trusted to the model. */
export function checkPlan(raw: unknown[], dishes: Dish[], party: Party, avoid: string[], maxTotal = 99): (Suggestion & { allergens_unknown?: boolean })[] {
  const byId = new Map(dishes.map((d) => [d.id, d]));
  let room = maxTotal;
  return checkSuggestions(raw, dishes).filter((s) => {
    const d = byId.get(s.product_id)!;
    if (avoid.length && Array.isArray(d.allergens) && d.allergens.some((a) => avoid.includes(a))) return false;
    if (party.spice === "none" && typeof d.spice_level === "number" && d.spice_level >= 2) return false;
    return true;
  }).flatMap((s) => {
    // "A bit short" tops up, it never doubles the order: the portions suggested stay under [maxTotal].
    if (room <= 0) return [];
    const quantity = Math.min(s.quantity, room);
    room -= quantity;
    return [{ ...s, quantity }];
  }).map((s) => {
    const d = byId.get(s.product_id)!;
    return avoid.length && !Array.isArray(d.allergens) ? { ...s, allergens_unknown: true } : s;
  });
}

/** Party, allergies and order lists from the phone, cleaned. */
export function readPlanInput(body: Record<string, unknown>, dishes: Dish[]): { party: Party; avoid: string[]; cart: PlanLine[]; ordered: PlanLine[] } {
  const p = (body.party ?? {}) as Record<string, unknown>;
  const size = Math.round(Number(p.size));
  const spice = p.spice === "none" || p.spice === "mild" || p.spice === "any" ? p.spice : null;
  const ids = new Set(dishes.map((d) => d.id));
  const lines = (v: unknown): PlanLine[] => (Array.isArray(v) ? v : []).slice(0, 40)
    .map((x) => x as Record<string, unknown>)
    .filter((x) => ids.has(String(x?.product_id)))
    .map((x) => ({ product_id: String(x.product_id), quantity: Math.min(50, Math.max(1, Math.round(Number(x.quantity) || 1))) }));
  return {
    party: { size: size >= 1 && size <= 30 ? size : null, spice },
    avoid: (Array.isArray(body.avoid) ? body.avoid : []).map(String).filter((a) => /^[a-z_]{2,20}$/.test(a)).slice(0, 14),
    cart: lines(body.cart), ordered: lines(body.ordered),
  };
}
