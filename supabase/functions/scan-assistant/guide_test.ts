import { assertEquals, assert } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { checkSuggestions, menuLines, parseAnswer, type Dish } from "./guide.ts";

const noodle: Dish = {
  id: "n1", name: "牛肉面 Beef Noodle Soup", price_cents: 1299, allergens: ["wheat", "soy"], dietary: ["halal"], spice_level: 2,
  modifier_groups: [
    { id: "spice", name: "Spice", required: true, max_select: 1, options: [{ id: "s0", name: "Not spicy" }, { id: "s1", name: "Mild" }] },
    { id: "add", name: "Add-ons", options: [{ id: "egg", name: "Egg", price_cents: 150 }] },
  ],
};
const tofu: Dish = { id: "t1", name: "豆腐 Firm Tofu", price_cents: 249 };
const gone: Dish = { id: "g1", name: "Gone", price_cents: 100, sold_out: true, allergens: [] };

Deno.test("allergens: recorded, none, and not recorded are different", () => {
  const lines = menuLines([noodle, tofu, gone]);
  assert(lines.includes("allergens: wheat, soy"));
  assert(lines.includes("豆腐 Firm Tofu | $2.49 | allergens: NOT RECORDED"));
  assert(lines.includes("SOLD OUT | allergens: none of the priority allergens"));
  assert(lines.includes(`options "Spice" (required, pick one): s0=Not spicy; s1=Mild`));
});

Deno.test("answer JSON is pulled out of wrapped text", () => {
  const a = parseAnswer('Sure!\n{"reply":"Bonjour","language":"fr","suggestions":[]}');
  assertEquals(a?.reply, "Bonjour");
  assertEquals(a?.language, "fr");
  assertEquals(parseAnswer("no json here"), null);
  assertEquals(parseAnswer('{"reply":""}'), null);
});

Deno.test("suggestions: unknown, sold out and duplicates dropped; bad options -> options sheet", () => {
  const s = checkSuggestions([
    { product_id: "n1", quantity: 2, option_ids: ["s1", "egg"], why: "mild" },
    { product_id: "n1", quantity: 1 },
    { product_id: "zzz", quantity: 1 },
    { product_id: "g1", quantity: 1 },
    { product_id: "t1", quantity: 99, option_ids: ["hack"] },
  ], [noodle, tofu, gone]);
  assertEquals(s.length, 2);
  assertEquals(s[0], { product_id: "n1", quantity: 2, option_ids: ["s1", "egg"], why: "mild", needs_options: false });
  assertEquals(s[1].quantity, 10);
  assertEquals(s[1].option_ids, []);
  const missingRequired = checkSuggestions([{ product_id: "n1", quantity: 1, option_ids: ["egg"] }], [noodle]);
  assertEquals(missingRequired[0].needs_options, true);
  assertEquals(missingRequired[0].option_ids, []);
});
