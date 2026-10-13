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
3. Halal, vegetarian, vegan, no pork/beef: only if the dish's dietary data says so.
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
