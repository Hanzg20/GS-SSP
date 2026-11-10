import { assertEquals, assert } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { checkOrder, checkPlan, checkSuggestions, factsHash, menuLines, mixedScript, parseExplain, parseIntent, parseAnswer, parseOrder, readPlanInput, readVerdict, type Dish } from "./guide.ts";

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
  assert(lines.includes("allergens: wheat (gluten), soy"));
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

Deno.test("order: same dish twice with different notes, required option left -> needs_options", () => {
  const raw = parseOrder('{"reply":"好的","language":"zh","lines":[{"product_id":"n1","quantity":1,"option_ids":["s1"],"note":"不要葱"},{"product_id":"n1","quantity":1,"option_ids":[]},{"product_id":"t1","quantity":"2"}],"choices":[],"missing":[]}')!;
  const o = checkOrder(raw, [noodle, tofu, gone]);
  assertEquals(o.lines.length, 3);
  assertEquals(o.lines[0], { product_id: "n1", quantity: 1, option_ids: ["s1"], note: "不要葱" });
  assertEquals(o.lines[1].needs_options, true);
  assertEquals(o.lines[2], { product_id: "t1", quantity: 2, option_ids: [] });
});

Deno.test("order: invented and sold-out dishes never become lines; foreign options dropped; quantity capped", () => {
  const o = checkOrder({ lines: [{ product_id: "zzz" }, { product_id: "g1" }, { product_id: "t1", quantity: 99, option_ids: ["s1"] }], choices: [], missing: ["龙虾"] }, [noodle, tofu, gone]);
  assertEquals(o.lines, [{ product_id: "t1", quantity: 10, option_ids: [] }]);
  assertEquals(o.missing, ["龙虾", "Gone"]);
});

Deno.test("order: choices keep only orderable dishes; one left -> becomes a line", () => {
  const o = checkOrder({ lines: [], choices: [{ heard: "面", product_ids: ["n1", "t1", "g1", "x"] }, { heard: "汤", product_ids: ["t1", "g1"] }], missing: [] }, [noodle, tofu, gone]);
  assertEquals(o.choices, [{ heard: "面", product_ids: ["n1", "t1"] }]);
  assertEquals(o.lines, [{ product_id: "t1", quantity: 1, option_ids: [] }]);
});

Deno.test("plan: allergy hits and hot dishes dropped on the server; unknown allergens flagged", () => {
  const hot: Dish = { id: "h1", name: "Mala", price_cents: 1500, spice_level: 4, allergens: [] };
  const raw = [{ product_id: "n1", quantity: 2, option_ids: ["s0"] }, { product_id: "t1", quantity: 1 }, { product_id: "h1", quantity: 1 }];
  const s = checkPlan(raw, [noodle, tofu, hot], { size: 2, spice: "none" }, ["wheat"]);
  assertEquals(s.map((x) => x.product_id), ["t1"]);
  assertEquals(s[0].allergens_unknown, true);
  assertEquals(checkPlan(raw, [noodle, tofu, hot], { size: 2, spice: "any" }, []).map((x) => x.product_id), ["n1", "t1", "h1"]);
});

Deno.test("plan input: party, allergies and lines cleaned; verdict read", () => {
  const i = readPlanInput({ party: { size: "4", spice: "hot" }, avoid: ["peanut", "DROP TABLE"], cart: [{ product_id: "n1", quantity: 2 }, { product_id: "zz" }] }, [noodle, tofu]);
  assertEquals(i.party, { size: 4, spice: null });
  assertEquals(i.avoid, ["peanut"]);
  assertEquals(i.cart, [{ product_id: "n1", quantity: 2 }]);
  assertEquals(readVerdict('{"reply":"x","verdict" : "less"}'), "less");
  assertEquals(readVerdict('{"reply":"x"}'), null);
});

Deno.test("plan: a top-up never suggests more portions than allowed", () => {
  const raw = [{ product_id: "t1", quantity: 2 }, { product_id: "n1", quantity: 2, option_ids: ["s0"] }, { product_id: "g1", quantity: 1 }];
  const s = checkPlan(raw, [noodle, tofu, gone], { size: 4, spice: null }, [], 3);
  assertEquals(s.map((x) => [x.product_id, x.quantity]), [["t1", 2], ["n1", 1]]);
});

Deno.test("explain: a change to the dish facts changes the hash; answer parsed", async () => {
  const a = await factsHash(noodle);
  assertEquals(a, await factsHash({ ...noodle, price_cents: 1 }));
  assert(a !== await factsHash({ ...noodle, allergens: ["wheat"] }));
  assertEquals(parseExplain('{"name":"Nouilles","note":"Un bol."}'), { name: "Nouilles", note: "Un bol." });
  assertEquals(parseExplain('{"name":"x"}'), null);
});

Deno.test("chat: the sorting answer is read; anything else is not trusted", () => {
  assertEquals(parseIntent('{"intent":"both"}'), "both");
  assertEquals(parseIntent('{"intent" : "order"}'), "order");
  assertEquals(parseIntent('{"intent":"weird"}'), null);
});

Deno.test("mixed languages: caught, but quoting a dish name is fine", () => {
  const menu = [noodle, tofu];
  assert(mixedScript("هل البقلاوة فيها مكسرات؟", "نعم، تحتوي على مكسرات. 请告诉服务员", menu));
  assert(!mixedScript("Is the beef noodle spicy?", "牛肉面 Beef Noodle Soup is mild.", menu));
  assert(mixedScript("Is it spicy?", "It is 微辣.", menu));
  assert(!mixedScript("有素食吗？", "有，豆腐 Firm Tofu 是素的。", menu));
  assert(!mixedScript("ok", "好的", menu));
});
