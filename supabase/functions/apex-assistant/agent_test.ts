import { assertEquals, assertThrows } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { calc, checkNumbers, toDollars, alternate } from "./agent.ts";

Deno.test("calc does + - * / and parentheses, nothing else", () => {
  assertEquals(calc("(1234.5-987)/987*100"), (1234.5 - 987) / 987 * 100);
  assertEquals(calc("$1,200 + 34.5"), 1234.5);
  assertEquals(calc("-3*(2+1)"), -9);
  assertThrows(() => calc("2**8"));
  assertThrows(() => calc("process.exit()"));
  assertThrows(() => calc("1/0"));
});

Deno.test("cents become dollars, nested", () => {
  assertEquals(toDollars({ total_cents: 2888, refunds: { count: 1, cents: 798 }, groups: [{ total_cents: 367 }] }),
    { total_dollars: 28.88, refunds: { count: 1, dollars: 7.98 }, groups: [{ total_dollars: 3.67 }] });
});

Deno.test("numbers in the answer must come from the data", () => {
  const known = [JSON.stringify({ total_dollars: 28.88, sales: 5, difference_percent: 12.5, from: "2026-11-01" })];
  assertEquals(checkNumbers("今天卖了 $28.88，共 5 笔，比上周多 12.5%。", known), []);
  assertEquals(checkNumbers("Sales were about $29 (2026-11-01).", known), []);          // a known figure rounded
  assertEquals(checkNumbers("今天卖了 $31.20，比上周多 15%。", known), [31.2, 15]);      // made up
  assertEquals(checkNumbers("3 个人上班，10 点最忙", known), []);                         // small counts and hours pass
});

Deno.test("history alternates and ends before the new question", () => {
  const out = alternate([{ role: "assistant", content: "hi" }, { role: "user", content: "a" }, { role: "user", content: "b" }, { role: "assistant", content: "c" }, { role: "user", content: "d" }]);
  assertEquals(out.map((m) => m.role), ["user", "assistant"]);
  assertEquals(out[0].content, "a\nb");
});

import { TOOLS, toolsFor, type Card } from "./agent.ts";
const tool = (n: string) => TOOLS.find((t) => t.name === n)!;
// A fake database: apex_tool_refs knows one product and one to-do of this store.
const fakeDb = { rpc: (fn: string, p: Record<string, unknown>) => Promise.resolve(fn === "apex_tool_refs"
  ? { data: (p.p_ids as string[]).filter((id) => id === "11111111-1111-1111-1111-111111111111")
      .map((id) => ({ id, text: p.p_kind === "product" ? "王老吉" : "订货", stock: 130 })), error: null }
  : { data: null, error: null }) };
const ctx = () => ({ db: fakeDb, orgId: "o", tz: "America/Toronto", lang: "zh", cards: [] as Card[] });
const P = "11111111-1111-1111-1111-111111111111", OTHER = "22222222-2222-2222-2222-222222222222";

Deno.test("an expense card re-reads the amount from the words", async () => {
  const c = ctx();
  await tool("propose_expense").run({ category: "UTILITIES", amount: 234.5, amount_text: "234 块 5", date: "2026-11-01", vendor: "Hydro" }, c);
  assertEquals(c.cards.length, 1);
  assertEquals(c.cards[0].kind, "expense");
  assertEquals(c.cards[0].amount, 234.5);
});

Deno.test("stock cards only for this store's products, adjustments need a reason", async () => {
  const c = ctx();
  const bad = await tool("propose_stock_change").run({ product_id: OTHER, change: "RECEIVE", qty: 12 }, c) as { error?: string };
  assertEquals(typeof bad.error, "string");
  await tool("propose_stock_change").run({ product_id: P, change: "ADJUST", qty: -2 }, c);
  assertEquals(c.cards.length, 1);
  assertEquals(c.cards[0].check, ["note"]);
  const neg = await tool("propose_stock_change").run({ product_id: P, change: "RECEIVE", qty: -5 }, c) as { error?: string };
  assertEquals(typeof neg.error, "string");
});

Deno.test("to-do cards only for real open to-dos; at most five cards", async () => {
  const c = ctx();
  const none = await tool("propose_todo_done").run({ todo_ids: [OTHER] }, c) as { error?: string };
  assertEquals(typeof none.error, "string");
  for (let i = 0; i < 6; i++) await tool("propose_note").run({ content: "n" + i }, c);
  assertEquals(c.cards.length, 5);
});

Deno.test("Cael gets no card tools", () => {
  assertEquals(toolsFor(false).some((t) => t.name.startsWith("propose_")), false);
  assertEquals(toolsFor(true).some((t) => t.name === "propose_note"), true);
});

Deno.test("store comparison only for several stores; a month must be YYYY-MM", async () => {
  assertEquals(toolsFor(false, false).some((t) => t.name === "compare_stores"), false);
  assertEquals(toolsFor(false, true).some((t) => t.name === "compare_stores"), true);
  const bad = await tool("month_report").run({ month: "last month" }, ctx()) as { error?: string };
  assertEquals(typeof bad.error, "string");
});
