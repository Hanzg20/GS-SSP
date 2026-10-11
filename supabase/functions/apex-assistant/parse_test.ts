// deno test supabase/functions/apex-assistant/parse_test.ts
import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { calendarLines, cnInt, localToInstant, looksLikeCommand, parseAmount, toProposal } from "./parse.ts";

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
