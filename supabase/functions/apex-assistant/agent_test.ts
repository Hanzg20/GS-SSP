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
