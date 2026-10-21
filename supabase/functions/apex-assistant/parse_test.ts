// deno test supabase/functions/apex-assistant/parse_test.ts
import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { calendarLines, cnInt, localToInstant, looksLikeCommand, parseAmount, toProposal, toReceipt } from "./parse.ts";

Deno.test("Chinese integers", () => {
  assertEquals(cnInt("二百三十四"), 234);
  assertEquals(cnInt("十五"), 15);
  assertEquals(cnInt("一千二"), 1200);
  assertEquals(cnInt("两千零五十"), 2050);
  assertEquals(cnInt("两万三"), 23000);
  assertEquals(cnInt("一万二千三百"), 12300);
  assertEquals(cnInt("三百零八"), 308);
  assertEquals(cnInt("abc"), null);
});

Deno.test("amounts as said", () => {
  assertEquals(parseAmount("二百三十四块五"), 234.5);
  assertEquals(parseAmount("三块五毛二"), 3.52);
  assertEquals(parseAmount("两千零五十元"), 2050);
  assertEquals(parseAmount("一百二十三点四五"), 123.45);
  assertEquals(parseAmount("十块零五分"), 10.05);
  assertEquals(parseAmount("$234.50"), 234.5);
  assertEquals(parseAmount("234块5"), 234.5);
  assertEquals(parseAmount("1,200 dollars"), 1200);
  assertEquals(parseAmount("八十八块钱"), 88);
  assertEquals(parseAmount("not a number"), null);
});

Deno.test("commands vs questions", () => {
  assertEquals(looksLikeCommand("明天早上9点提醒我给供应商打电话"), true);
  assertEquals(looksLikeCommand("今天付了电费二百三十四块五"), true);
  assertEquals(looksLikeCommand("Remind me to order milk on Monday"), true);
  assertEquals(looksLikeCommand("今天生意怎么样？"), false);
});

Deno.test("Toronto wall clock -> instant (EDT and EST)", () => {
  assertEquals(localToInstant("2026-10-11T09:00", "America/Toronto")?.toISOString(), "2026-10-11T13:00:00.000Z");
  assertEquals(localToInstant("2026-12-01T09:00", "America/Toronto")?.toISOString(), "2026-12-01T14:00:00.000Z");
});

Deno.test("expense proposal: amount re-read from the words, mismatch flagged", () => {
  const now = new Date("2026-10-11T02:00:00Z"); // 22:00 on the 10th in Toronto
  const p = toProposal({ kind: "expense", vendor: "Hydro Ottawa", category: "UTILITIES", amount: 243.5, amount_text: "二百三十四块五", date: "2026-10-10" }, "America/Toronto", now);
  assertEquals(p?.kind, "expense");
  if (p?.kind === "expense") {
    assertEquals(p.amount, 234.5);
    assertEquals(p.check, ["amount"]);
    assertEquals(p.spentOn, "2026-10-10");
  }
});

Deno.test("reminder proposal: past time flagged, future kept", () => {
  const now = new Date("2026-10-11T02:00:00Z");
  const ok = toProposal({ kind: "reminder", text: "给供应商打电话", due: "2026-10-11T09:00" }, "America/Toronto", now);
  assertEquals(ok?.kind === "reminder" && ok.dueAt, "2026-10-11T13:00:00.000Z");
  const past = toProposal({ kind: "reminder", text: "x", due: "2026-10-09T09:00" }, "America/Toronto", now);
  assertEquals(past?.kind === "reminder" && past.dueAt, null);
});

Deno.test("calendar: on Saturday 2026-10-10 last week's Friday is 10-02", () => {
  const lines = calendarLines("America/Toronto", new Date("2026-10-11T02:00:00Z"));
  assertEquals(lines.split("\n")[0].includes("Fri 周五 2026-10-02"), true);
  assertEquals(lines.split("\n")[1].includes("Fri 周五 2026-10-09"), true);
  assertEquals(lines.split("\n")[2].includes("Mon 周一 2026-10-12"), true);
});

Deno.test("receipt: numbers must be printed on it, dates real and recent", () => {
  const text = "FRESHCO\n10/16/2026\nSUB TOTAL\n40.00\nH.S.T.\n5.20\nAMOUNT\n45.20\nVISA 45.20\nHST REG 123456789 RT0001";
  const r = toReceipt('Sure! {"vendor":"FreshCo","date":"2026-10-16","total":"45.20","tax":5.2,"hst_number":"123456789RT0001"}', text, "2026-10-20");
  assertEquals(r, { vendor: "FreshCo", date: "2026-10-16", total: 45.2, tax: 5.2, hstNumber: "123456789 RT 0001" });
  // Not on the receipt -> dropped, never trusted.
  const bad = toReceipt('{"vendor":"X","date":"2026-11-30","total":"99.99","tax":"60.00","hst_number":"999999999RT0001"}', text, "2026-10-20");
  assertEquals(bad, { vendor: "X", date: null, total: null, tax: null, hstNumber: null });
  // Comma decimals and thousands.
  assertEquals(toReceipt('{"total":"1,234.56"}', "TOTAL $1,234.56", "2026-10-20").total, 1234.56);
  assertEquals(toReceipt('{"total":"12,34"}', "TOTAL 12,34", "2026-10-20").total, 12.34);
  assertEquals(toReceipt("no json at all", text, "2026-10-20").total, null);
});
