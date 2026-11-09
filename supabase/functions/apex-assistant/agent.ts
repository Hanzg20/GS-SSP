// 小海狸 2.0 (docs/apex_agent_design.md): the model answers by calling read tools (P1)
// and proposes what to do through propose_* tools (P2) -- like Muse: work out what is
// needed, look it up, then act. A proposal is only a card for the terminal: nothing is
// saved until the manager confirms it there (through the same repositories as by hand).
//
// The tools are SQL functions (docs/migrations/2026-11-02_apex_tools.sql) run with the
// service role for the caller's store -- the store id never comes from the model. Money
// comes back in cents and is turned into dollars here, so the model never converts or
// estimates; it adds or compares through `calc`. After the answer, every number in it
// is checked against what the tools returned (checkNumbers); one rewrite is asked for
// when something doesn't match.
//
// Provider-neutral: the same tool list drives Groq (OpenAI-style tool calls, the free
// tier while developing) and Claude (Anthropic SDK, at go-live).

import Anthropic from "npm:@anthropic-ai/sdk@1.3.0";
import { toProposal } from "./parse.ts";
// deno-lint-ignore no-explicit-any
type Db = any;

export type Turn = { role: "user" | "assistant"; content: string };
export type Source = { tool: string; label: string };
export type AgentResult = { reply: string; sources: Source[]; checked: boolean; calls: { tool: string; args: unknown }[]; proposals: Card[] };
// deno-lint-ignore no-explicit-any
export type Card = Record<string, any> & { kind: string };
const MAX_CARDS = 5;

const MAX_ROUNDS = 6;

// ---- tools -------------------------------------------------------------------

// stores: the caller's stores, for compare_stores (Cael, someone with more than one).
type Ctx = { db: Db; orgId: string; tz: string; lang: string; cards?: Card[]; stores?: { id: string; name: string }[] };
type ToolDef = {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
  run: (args: Record<string, unknown>, ctx: Ctx) => Promise<unknown>;
  label: (args: Record<string, unknown>, zh: boolean) => string;
};

const DATE = { type: "string", description: "YYYY-MM-DD, the store's local date" };

function str(v: unknown): string | null {
  return typeof v === "string" && v.trim() ? v.trim() : null;
}
function int(v: unknown): number | null {
  const n = typeof v === "number" ? v : typeof v === "string" ? Number(v) : NaN;
  return Number.isFinite(n) ? Math.round(n) : null;
}
function cents(v: unknown): number | null {
  const n = typeof v === "number" ? v : typeof v === "string" ? Number(v.replace(/[$,\s]/g, "")) : NaN;
  return Number.isFinite(n) ? Math.round(n * 100) : null;
}
async function rpc(ctx: Ctx, fn: string, params: Record<string, unknown>): Promise<unknown> {
  const { data, error } = await ctx.db.rpc(fn, params);
  if (error) throw new Error(`${fn}: ${error.message}`);
  return data;
}
const range = (a: Record<string, unknown>) => `${str(a.from) ?? "?"}${str(a.to) && a.to !== a.from ? " – " + str(a.to) : ""}`;

export const TOOLS: ToolDef[] = [
  {
    name: "sales",
    description: "Sales of the store over a range of local days (inclusive): count, total, tax, tips, before-tax amount, average basket, refunds in the range; optionally grouped by day, hour, weekday, staff or payment method. Partial refunds are already taken off.",
    parameters: {
      type: "object",
      properties: { from: DATE, to: DATE, group_by: { type: "string", enum: ["none", "day", "hour", "weekday", "staff", "method"] } },
      required: ["from", "to"],
    },
    run: (a, c) => rpc(c, "apex_tool_sales", { p_org: c.orgId, p_from: str(a.from), p_to: str(a.to) ?? str(a.from), p_group: str(a.group_by) ?? "none", p_tz: c.tz }),
    label: (a, zh) => (zh ? "销售 " : "Sales ") + range(a) + (str(a.group_by) && a.group_by !== "none" ? (zh ? "（分组）" : " (grouped)") : ""),
  },
  {
    name: "compare_sales",
    description: "Compare the sales of two ranges of local days (e.g. this week vs last week): both totals, the difference and the percentage, already worked out.",
    parameters: {
      type: "object",
      properties: { a_from: DATE, a_to: DATE, b_from: DATE, b_to: DATE },
      required: ["a_from", "a_to", "b_from", "b_to"],
    },
    run: async (a, c) => {
      const [x, y] = await Promise.all([
        rpc(c, "apex_tool_sales", { p_org: c.orgId, p_from: str(a.a_from), p_to: str(a.a_to), p_group: "none", p_tz: c.tz }),
        rpc(c, "apex_tool_sales", { p_org: c.orgId, p_from: str(a.b_from), p_to: str(a.b_to), p_group: "none", p_tz: c.tz }),
      ]) as [Record<string, number>, Record<string, number>];
      const diff = (x.total_cents ?? 0) - (y.total_cents ?? 0);
      const pct = y.total_cents ? Math.round((diff / y.total_cents) * 1000) / 10 : null;
      return { a: x, b: y, difference_cents: diff, difference_percent: pct, sales_count_difference: (x.sales ?? 0) - (y.sales ?? 0) };
    },
    label: (a, zh) => (zh ? "对比 " : "Compare ") + `${str(a.a_from)}–${str(a.a_to)} / ${str(a.b_from)}–${str(a.b_to)}`,
  },
  {
    name: "products",
    description: "What sold over a range of local days: best sellers, slow sellers (active products with the fewest sold, unsold first, with stock), or totals by category. `query` narrows to products whose name contains it (or a barcode).",
    parameters: {
      type: "object",
      properties: { from: DATE, to: DATE, sort: { type: "string", enum: ["best", "slow", "category"] }, query: { type: "string" }, limit: { type: "integer" } },
      required: ["from", "to"],
    },
    run: (a, c) => rpc(c, "apex_tool_products", { p_org: c.orgId, p_from: str(a.from), p_to: str(a.to) ?? str(a.from), p_sort: str(a.sort) ?? "best", p_query: str(a.query), p_limit: int(a.limit) ?? 10, p_tz: c.tz }),
    label: (a, zh) => (zh ? "商品销量 " : "Product sales ") + range(a),
  },
  {
    name: "find_sales",
    description: "Find individual sales on one local day, optionally between two times (HH:MM), by amount in dollars (with or without the tip), staff name, payment method (CASH / CREDIT_CARD / DEBIT_CARD / QR_CODE) or status (PAID / REFUNDED / VOIDED / PART_REFUNDED / DECLINED / CANCELLED). Returns their refs for sale_detail.",
    parameters: {
      type: "object",
      properties: {
        day: DATE, time_from: { type: "string" }, time_to: { type: "string" }, amount: { type: "number", description: "dollars" },
        staff: { type: "string" }, method: { type: "string" }, status: { type: "string" },
      },
      required: ["day"],
    },
    run: (a, c) => rpc(c, "apex_tool_find_transactions", {
      p_org: c.orgId, p_day: str(a.day), p_time_from: str(a.time_from), p_time_to: str(a.time_to), p_amount_cents: cents(a.amount),
      p_staff: str(a.staff), p_method: str(a.method), p_status: str(a.status), p_limit: 20, p_tz: c.tz,
    }),
    label: (a, zh) => (zh ? "查交易 " : "Sales on ") + (str(a.day) ?? ""),
  },
  {
    name: "sale_detail",
    description: "One sale in full by its ref: lines and items given back, discounts, refunds (who approved), loyalty member and points, delivery order.",
    parameters: { type: "object", properties: { ref: { type: "string" } }, required: ["ref"] },
    run: (a, c) => rpc(c, "apex_tool_transaction", { p_org: c.orgId, p_ref: str(a.ref) ?? "", p_tz: c.tz }),
    label: (a, zh) => (zh ? "交易 " : "Sale ") + (str(a.ref) ?? ""),
  },
  {
    name: "stock",
    description: "Stock of tracked products: on hand, low-stock level, sold in the last 7 days and days left at that pace. `query` = name or barcode; `low_only` = only products at or below their low level.",
    parameters: { type: "object", properties: { query: { type: "string" }, low_only: { type: "boolean" } } },
    run: (a, c) => rpc(c, "apex_tool_stock", { p_org: c.orgId, p_query: str(a.query), p_low_only: a.low_only === true }),
    label: (a, zh) => (zh ? "库存" : "Stock") + (str(a.query) ? " " + str(a.query) : ""),
  },
  {
    name: "members",
    description: "Loyalty members: one by phone (any part) or name with points, visits and recent points moves; without a query, the top members and the store's totals.",
    parameters: { type: "object", properties: { query: { type: "string" } } },
    run: (a, c) => rpc(c, "apex_tool_members", { p_org: c.orgId, p_query: str(a.query), p_tz: c.tz }),
    label: (a, zh) => (zh ? "会员" : "Members") + (str(a.query) ? " " + str(a.query) : ""),
  },
  {
    name: "staff_hours_tips",
    description: "Hours worked (minutes) and tip shares per person over a range of local days (at most 63 days), under the store's tip rule; who is still on the clock.",
    parameters: { type: "object", properties: { from: DATE, to: DATE }, required: ["from", "to"] },
    run: (a, c) => rpc(c, "apex_tool_staff", { p_org: c.orgId, p_from: str(a.from), p_to: str(a.to) ?? str(a.from) }),
    label: (a, zh) => (zh ? "工时小费 " : "Hours & tips ") + range(a),
  },
  {
    name: "money",
    description: "Money in and out over a range of local days: sales before tax and tips, recorded expenses by category and the largest ones, and the rough profit (sales before tax and tips minus recorded expenses). `category` = one of INVENTORY, RENT, UTILITIES, PAYROLL, REPAIRS, MARKETING, FEES, OTHER.",
    parameters: { type: "object", properties: { from: DATE, to: DATE, category: { type: "string" } }, required: ["from", "to"] },
    run: async (a, c) => {
      const out = await rpc(c, "apex_tool_money", { p_org: c.orgId, p_from: str(a.from), p_to: str(a.to) ?? str(a.from), p_category: str(a.category) }) as Record<string, unknown>;
      // An expense card from this same answer isn't saved yet; the model added it to the
      // month's total anyway (Q2, 11-02: "Utilities: $383" before the card was confirmed).
      const pending = (c.cards ?? []).filter((p) => p.kind === "expense");
      if (pending.length) {
        out.not_included = pending.map((p) => ({ category: p.category, amount: p.amount, status: "card waiting for confirmation, NOT saved, NOT in these totals" }));
      }
      return out;
    },
    label: (a, zh) => (zh ? "收支 " : "Money ") + range(a),
  },
  {
    name: "deliveries",
    description: "Delivery orders: open ones (waiting, or out with a driver and since when), delivered in the last 7 days, or all of the last 14 days.",
    parameters: { type: "object", properties: { status: { type: "string", enum: ["open", "delivered", "all"] } } },
    run: (a, c) => rpc(c, "apex_tool_deliveries", { p_org: c.orgId, p_status: str(a.status) ?? "open", p_tz: c.tz }),
    label: (_a, zh) => (zh ? "送货单" : "Delivery orders"),
  },
  {
    name: "journal",
    description: "The store journal now: open to-dos (who for, due, overdue), today's checklists (done / not done items), latest notes.",
    parameters: { type: "object", properties: {} },
    run: (_a, c) => rpc(c, "apex_tool_journal", { p_org: c.orgId, p_tz: c.tz }),
    label: (_a, zh) => (zh ? "手账" : "Journal"),
  },
  {
    name: "guest_questions",
    description: "What dine-in customers asked Beaver (the AI on the table QR page) over a range of local days: how many questions, voice orders and table plans, languages, topics (allergy, vegetarian, halal, gluten_free, spicy, kids) with how many dishes are labelled for that diet, dishes talked about vs sold (and whether they have a photo), things asked for that are NOT on the menu, and the latest questions. Use it for \"what are customers asking / looking for\", menu gaps, and ideas for the menu (add a dish, label diets, add a photo). Point out concrete actions.",
    parameters: { type: "object", properties: { from: DATE, to: DATE }, required: ["from", "to"] },
    run: (a, c) => rpc(c, "apex_tool_guest_asks", { p_org: c.orgId, p_from: str(a.from), p_to: str(a.to) ?? str(a.from), p_tz: c.tz }),
    label: (a, zh) => (zh ? "顾客在问什么 " : "What customers ask ") + range(a),
  },
  {
    name: "month_report",
    description: "A month closed out (month-end report): sales by payment method and by day, tax collected and tips, refunds, expenses by category and the largest, rough profit, stock in / out by reason and what was received, best sellers and categories, hours and tips per person, loyalty points, delivery orders, offline card sales. `complete` = false while the month is still going. Write it as a short report with headings.",
    parameters: { type: "object", properties: { month: { type: "string", description: "YYYY-MM" } }, required: ["month"] },
    run: (a, c) => {
      const m = /^(\d{4})-(\d{2})$/.exec(str(a.month) ?? "");
      if (!m) return Promise.resolve({ error: "month must be YYYY-MM" });
      return rpc(c, "apex_tool_month", { p_org: c.orgId, p_month: `${m[1]}-${m[2]}-01`, p_tz: c.tz });
    },
    label: (a, zh) => (zh ? "月报 " : "Month report ") + (str(a.month) ?? ""),
  },
  {
    name: "compare_stores",
    description: "Sales of each of the caller's stores over a range of local days, side by side (best first). Only for someone with several stores.",
    parameters: { type: "object", properties: { from: DATE, to: DATE }, required: ["from", "to"] },
    run: async (a, c) => {
      const stores = (c.stores ?? []).slice(0, 20);
      const rows = await Promise.all(stores.map(async (s) => {
        const r = await rpc(c, "apex_tool_sales", { p_org: s.id, p_from: str(a.from), p_to: str(a.to) ?? str(a.from), p_group: "none", p_tz: c.tz }) as Record<string, unknown>;
        return { store: s.name, sales: r.sales, total_cents: r.total_cents, before_tax_and_tips_cents: r.before_tax_and_tips_cents, avg_basket_cents: r.avg_basket_cents, refunds: r.refunds };
      }));
      return { from: str(a.from), to: str(a.to), stores: rows.sort((x, y) => Number(y.total_cents) - Number(x.total_cents)) };
    },
    label: (a, zh) => (zh ? "各店对比 " : "Stores ") + range(a),
  },
  // ---- proposals: cards the manager confirms on the terminal (never saved here) ----
  {
    name: "propose_expense",
    description: "Propose recording an expense the store paid (shows a card; saved only when the manager confirms). amount in dollars; amount_text = the amount exactly as they said it; date = the day it was paid (today if not said).",
    parameters: {
      type: "object",
      properties: {
        vendor: { type: "string" }, category: { type: "string", enum: ["INVENTORY", "RENT", "UTILITIES", "PAYROLL", "REPAIRS", "MARKETING", "FEES", "OTHER"] },
        amount: { type: "number" }, amount_text: { type: "string" }, date: DATE, date_text: { type: "string" }, note: { type: "string" },
      },
      required: ["category", "amount", "amount_text", "date"],
    },
    run: (a, c) => card(c, toProposal({ ...a, kind: "expense" }, c.tz)),
    label: () => "",
  },
  {
    name: "propose_reminder",
    description: "Propose a to-do with a reminder time (card; saved when confirmed). due = YYYY-MM-DDTHH:mm store local time; when_text = the time words as said.",
    parameters: { type: "object", properties: { text: { type: "string" }, due: { type: "string" }, when_text: { type: "string" } }, required: ["text", "due"] },
    run: (a, c) => card(c, toProposal({ ...a, kind: "reminder" }, c.tz)),
    label: () => "",
  },
  {
    name: "propose_delivery_order",
    description: "Propose a delivery order (card; created when confirmed): customer, phone, address, note, items with quantity (and price in dollars if said).",
    parameters: {
      type: "object",
      properties: {
        customer_name: { type: "string" }, customer_phone: { type: "string" }, address: { type: "string" }, note: { type: "string" },
        items: { type: "array", items: { type: "object", properties: { name: { type: "string" }, quantity: { type: "integer" }, price: { type: "number" } }, required: ["name"] } },
      },
      required: ["customer_name", "address", "items"],
    },
    run: (a, c) => card(c, toProposal({ ...a, kind: "delivery_order" }, c.tz)),
    label: () => "",
  },
  {
    name: "propose_todo_done",
    description: "Propose marking open to-dos done (card). todo_ids come from the journal tool -- look them up first.",
    parameters: { type: "object", properties: { todo_ids: { type: "array", items: { type: "string" } } }, required: ["todo_ids"] },
    run: async (a, c) => {
      const items = await refs(c, "todo", a.todo_ids);
      return items.length ? card(c, { kind: "todo_done", items }) : { error: "no such open to-do in this store -- call journal for the ids" };
    },
    label: () => "",
  },
  {
    name: "propose_checklist_tick",
    description: "Propose ticking checklist steps as done today (card). step_ids come from the journal tool (checklists_today[].steps[].id).",
    parameters: { type: "object", properties: { step_ids: { type: "array", items: { type: "string" } } }, required: ["step_ids"] },
    run: async (a, c) => {
      const items = await refs(c, "step", a.step_ids);
      return items.length ? card(c, { kind: "checklist_tick", items }) : { error: "no such checklist step in this store -- call journal for the ids" };
    },
    label: () => "",
  },
  {
    name: "propose_note",
    description: "Propose saving a note in the store journal (card).",
    parameters: { type: "object", properties: { title: { type: "string" }, content: { type: "string" } }, required: ["content"] },
    run: (a, c) => {
      const content = str(a.content);
      return Promise.resolve(content ? card(c, { kind: "note", title: (str(a.title) ?? "").slice(0, 60), content: content.slice(0, 2000) }) : { error: "empty note" });
    },
    label: () => "",
  },
  {
    name: "propose_stock_change",
    description: "Propose a stock change (card): RECEIVE = goods arrived (+qty), COUNT = counted, stock is now qty, ADJUST = +/- qty with a reason (note required: damaged, expired...). product_id comes from the stock tool -- look it up first.",
    parameters: {
      type: "object",
      properties: { product_id: { type: "string" }, change: { type: "string", enum: ["RECEIVE", "COUNT", "ADJUST"] }, qty: { type: "integer" }, note: { type: "string" } },
      required: ["product_id", "change", "qty"],
    },
    run: async (a, c) => {
      const [p] = await refs(c, "product", [a.product_id]);
      const change = ["RECEIVE", "COUNT", "ADJUST"].includes(String(a.change)) ? String(a.change) : null;
      const qty = int(a.qty);
      if (!p) return { error: "no such product in this store -- call stock for the product_id" };
      if (!change || qty === null || (change !== "ADJUST" && qty < 0) || (change === "RECEIVE" && qty === 0) || Math.abs(qty) > 100000) return { error: "bad change or quantity" };
      const note = (str(a.note) ?? "").slice(0, 200);
      return card(c, { kind: "stock_change", product_id: p.id, name: p.text, stock_now: p.stock ?? null, change, qty, note, check: change === "ADJUST" && !note ? ["note"] : [] });
    },
    label: () => "",
  },
  {
    name: "propose_remember",
    description: "Propose remembering a lasting fact about this store for later answers (e.g. \"closed on Mondays\", \"rent is due on the 1st\") (card). Not for one-off tasks (use propose_reminder).",
    parameters: { type: "object", properties: { fact: { type: "string" } }, required: ["fact"] },
    run: (a, c) => {
      const fact = str(a.fact);
      return Promise.resolve(fact ? card(c, { kind: "remember", text: fact.slice(0, 300) }) : { error: "empty fact" });
    },
    label: () => "",
  },
  {
    name: "calc",
    description: "Arithmetic on numbers you already have (+ - * / and parentheses), e.g. \"(1234.5-987)/987*100\". Use it for every sum, difference or percentage you give; never work numbers out in your head.",
    parameters: { type: "object", properties: { expression: { type: "string" } }, required: ["expression"] },
    run: (a) => {
      const expr = String(a.expression ?? "");
      const value = calc(expr);
      return Promise.resolve({ expression: expr, result: Math.round(value * 10000) / 10000 });
    },
    label: () => "",
  },
];

/** A card for the terminal; what the model is told back. */
function card(c: Ctx, p: Card | null): Promise<unknown> {
  if (!p) return Promise.resolve({ error: "that can't be put on a card -- ask the manager for what is missing" });
  c.cards ??= [];
  if (c.cards.length >= MAX_CARDS) return Promise.resolve({ error: `at most ${MAX_CARDS} cards per answer` });
  c.cards.push(p);
  return Promise.resolve({ card_shown: true, saved: false, note: "the manager confirms it on the terminal; it is not saved yet", card: p });
}

/** Ids from the model, kept only if they are this store's (apex_tool_refs). */
async function refs(c: Ctx, kind: string, ids: unknown): Promise<{ id: string; text: string; checklist?: string; stock?: number }[]> {
  const list = (Array.isArray(ids) ? ids : [ids]).map(String).filter((s) => /^[0-9a-f-]{36}$/i.test(s)).slice(0, 20);
  if (!list.length) return [];
  return (await rpc(c, "apex_tool_refs", { p_org: c.orgId, p_kind: kind, p_ids: list })) as { id: string; text: string }[];
}

/** The tools for a caller: no cards from Cael (nothing there to confirm them on); store comparison only with several stores. */
export function toolsFor(cards: boolean, manyStores = false): ToolDef[] {
  return TOOLS.filter((t) => (cards || !t.name.startsWith("propose_")) && (manyStores || t.name !== "compare_stores"));
}

// ---- calc --------------------------------------------------------------------

/** + - * / ( ) on decimal numbers; anything else throws. */
export function calc(expression: string): number {
  const s = expression.replace(/[,$\s]/g, "").replace(/×/g, "*").replace(/÷/g, "/");
  let i = 0;
  const peek = () => s[i];
  function num(): number {
    if (peek() === "(") { i++; const v = sum(); if (s[i++] !== ")") throw new Error("missing )"); return v; }
    if (peek() === "-") { i++; return -num(); }
    const m = /^\d+(\.\d+)?/.exec(s.slice(i));
    if (!m) throw new Error(`bad expression at "${s.slice(i, i + 8)}"`);
    i += m[0].length;
    return Number(m[0]);
  }
  function product(): number {
    let v = num();
    while (peek() === "*" || peek() === "/") {
      const op = s[i++];
      const r = num();
      if (op === "/" && r === 0) throw new Error("division by zero");
      v = op === "*" ? v * r : v / r;
    }
    return v;
  }
  function sum(): number {
    let v = product();
    while (peek() === "+" || peek() === "-") { const op = s[i++]; const r = product(); v = op === "+" ? v + r : v - r; }
    return v;
  }
  const v = sum();
  if (i !== s.length) throw new Error(`unexpected "${s.slice(i, i + 8)}"`);
  return v;
}

// ---- money and the number check ----------------------------------------------

/** *_cents -> *_dollars (2 decimals), so the model never does that arithmetic. */
export function toDollars(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(toDollars);
  if (v && typeof v === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, x] of Object.entries(v as Record<string, unknown>)) {
      if ((k.endsWith("_cents") || k === "cents") && typeof x === "number") out[k === "cents" ? "dollars" : k.replace(/_cents$/, "_dollars")] = Math.round(x) / 100;
      else out[k] = toDollars(x);
    }
    return out;
  }
  return v;
}

const NUM = /\d[\d,]*(?:\.\d+)?/g;
function numbersIn(text: string): number[] {
  return (text.match(NUM) ?? []).map((n) => Number(n.replace(/,/g, ""))).filter((n) => Number.isFinite(n));
}

/**
 * Numbers in [reply] that none of [known] supports. Small whole numbers (counts, days,
 * hours: up to 31) pass unless written as money or a percentage; a whole number passes
 * when it is a known figure rounded; amounts may drop trailing zeros (28.8 for 28.80).
 */
export function checkNumbers(reply: string, known: string[]): number[] {
  const pool = known.flatMap(numbersIn);
  const bad: number[] = [];
  const re = /(\$\s?)?(\d[\d,]*(?:\.\d+)?)(\s?%)?/g;
  for (const m of reply.matchAll(re)) {
    const x = Number(m[2].replace(/,/g, ""));
    if (!Number.isFinite(x)) continue;
    const money = !!m[1], pct = !!m[3];
    if (!money && !pct && Number.isInteger(x) && x <= 31) continue;
    const ok = pool.some((a) => Math.abs(a - x) < 0.006 || (Number.isInteger(x) && Math.abs(Math.round(a) - x) === 0) ||
      (!Number.isInteger(x) && Math.abs(Math.round(a * 10) / 10 - x) < 0.006));
    if (!ok) bad.push(x);
  }
  return bad;
}

// ---- the prompt ----------------------------------------------------------------

export function agentSystem(persona: string, rules: string, facts: string[], langLine: string, nowStamp: string, calendar: string, storeName: string): string {
  return `${persona}

门店：${storeName}。现在（门店当地时间）：${nowStamp}。
日历：
${calendar}

操作知识：
${facts.map((f) => `- ${f}`).join("\n")}

怎么回答：
- 先想清楚要哪些数据，再调用工具去查；可以一次调多个工具。数据都在工具里，不要凭记忆或猜测回答。
- 日期按门店当地日期换算成 YYYY-MM-DD（"上周"用上面的日历）。没说时间段的销售问题，默认今天。
- 金额工具已换算成元（*_dollars），原样引用；要加减、算差额或百分比，一定调用 calc，不要心算。
- 只回答查到的内容；工具里没有的（成本、毛利率、顾客个人信息、其他门店），老实说系统里还没有这个数据。
- 回答简短（一般 2-6 句），先给结论再给关键数字；列多项时用短列表。不要出现工具名或英文字段名。
- 要办的事（记账、设提醒、完成待办、勾清单、记笔记、进货/盘点/调整库存、建送货单、记住门店的长期习惯）用对应的 propose_* 工具生成确认卡片。卡片要经理在下面点确认才会保存：回答里说"请在下面确认"，绝不说"已经记好了/已完成"。
- 完成待办、勾清单前先调用 journal 拿到 id；改库存前先调用 stock 拿到 product_id；对不上就问清楚，不要乱选。
- 一句话里可以既有问题又有要办的事（比如"记一下电费 234.5，再看看本月水电一共多少"）：都做，查到的照实说（还没确认的卡片不算在已记录的数里）。查到的数据里 not_included 是还没确认的卡片：已记录的合计照原样报，不要把它加进去，也不要说它"已记"；可以另外说"确认后会是多少"（用 calc 算）。
- "本月"是日历上本月 1 号到今天，"上个月"是上个日历月；不要跨月。

${rules}

${langLine}`;
}

// ---- the loop --------------------------------------------------------------------

type Provider = "groq" | "anthropic";

export async function runAgent(opts: {
  provider: Provider; model: string; key: string;
  system: string; history: Turn[]; question: string; ctx: Ctx; tools: ToolDef[];
}): Promise<AgentResult> {
  const zh = opts.ctx.lang === "zh";
  const sources: Source[] = [];
  const calls: { tool: string; args: unknown }[] = [];
  const known: string[] = [opts.question, opts.system.slice(0, 600)]; // the date lines and the question's own numbers
  opts.ctx.cards = [];

  async function exec(name: string, rawArgs: unknown): Promise<string> {
    const tool = opts.tools.find((t) => t.name === name);
    let args: Record<string, unknown> = {};
    try { args = typeof rawArgs === "string" ? JSON.parse(rawArgs || "{}") : (rawArgs as Record<string, unknown>) ?? {}; } catch { /* bad JSON -> {} */ }
    calls.push({ tool: name, args });
    if (!tool) return JSON.stringify({ error: `unknown tool ${name}` });
    try {
      const out = JSON.stringify(toDollars(await tool.run(args, opts.ctx)));
      known.push(out);
      const label = tool.label(args, zh);
      if (label && !sources.some((s) => s.label === label)) sources.push({ tool: name, label });
      return out;
    } catch (e) {
      return JSON.stringify({ error: (e as Error).message });
    }
  }

  const ask = opts.provider === "anthropic" ? anthropicLoop : groqLoop;
  let reply = (await ask(opts, exec, null)).trim();
  let bad = checkNumbers(reply, known);
  let checked = bad.length === 0;
  if (!checked) {
    console.warn("[apex-agent] unsupported numbers", bad, "in", reply);
    const fix = zh
      ? `你的回答里这些数字在查到的数据里找不到：${bad.join("、")}。请重写：只用工具返回的数字，需要计算就调用 calc。`
      : `These numbers in your answer aren't in the data you looked up: ${bad.join(", ")}. Rewrite it using only numbers the tools returned; call calc for any arithmetic.`;
    const firstCards = [...(opts.ctx.cards ?? [])];
    const second = (await ask(opts, exec, { draft: reply, fix })).trim();
    opts.ctx.cards = firstCards; // the rewrite is about wording; the cards stay as first proposed
    const bad2 = checkNumbers(second, known);
    reply = second || reply;
    checked = bad2.length === 0;
    if (!checked) reply += zh ? "\n\n（有个别数字没能和系统数据核对上，请以报表为准。）" : "\n\n(Some figures couldn't be checked against the system -- go by the reports.)";
  }
  return { reply, sources: sources.slice(0, 5), checked, calls, proposals: opts.ctx.cards ?? [] };
}

type Exec = (name: string, args: unknown) => Promise<string>;
type Retry = { draft: string; fix: string } | null;

// Groq: OpenAI-style chat completions with tools (gpt-oss).
async function groqLoop(o: Parameters<typeof runAgent>[0], exec: Exec, retry: Retry): Promise<string> {
  // deno-lint-ignore no-explicit-any
  const messages: any[] = [{ role: "system", content: o.system }, ...o.history, { role: "user", content: o.question }];
  if (retry) messages.push({ role: "assistant", content: retry.draft }, { role: "user", content: retry.fix });
  const tools = o.tools.map((t) => ({ type: "function", function: { name: t.name, description: t.description, parameters: t.parameters } }));
  for (let round = 0; round <= MAX_ROUNDS; round++) {
    const last = round === MAX_ROUNDS;
    const res = await groqSend(o.key, { model: o.model, messages, reasoning_effort: "low", ...(last ? {} : { tools, tool_choice: "auto" }) });
    const msg = res?.choices?.[0]?.message;
    if (!msg) throw new Error(`groq: ${JSON.stringify(res).slice(0, 300)}`);
    const toolCalls = msg.tool_calls ?? [];
    if (!toolCalls.length || last) return msg.content ?? "";
    messages.push({ role: "assistant", content: msg.content ?? "", tool_calls: toolCalls });
    const results = await Promise.all(toolCalls.map((tc: { id: string; function: { name: string; arguments: string } }) => exec(tc.function.name, tc.function.arguments)));
    toolCalls.forEach((tc: { id: string }, i: number) => messages.push({ role: "tool", tool_call_id: tc.id, content: results[i] }));
  }
  return "";
}

export class BusyError extends Error {}

// deno-lint-ignore no-explicit-any
async function groqSend(key: string, body: Record<string, unknown>): Promise<any> {
  const send = () => fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST", headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  let res = await send();
  // Free tier: 8000 tokens a minute for the whole account. Wait out a short limit once.
  if (res.status === 429) {
    const wait = Number(res.headers.get("retry-after") ?? "") || 6;
    // A round is ~5k tokens, so the second round of nearly every question waits ~20 s
    // on the free tier (log 11-02: "Used 5718, Requested 4857, try again in 19.3s").
    // The terminal waits 60 s.
    if (wait <= 25) { await new Promise((ok) => setTimeout(ok, wait * 1000 + 300)); res = await send(); }
    if (res.status === 429) {
      // Which limit (tokens a minute / a day) -- the terminal only says "busy".
      console.warn("[apex-agent] groq 429", res.headers.get("retry-after"), (await res.text()).slice(0, 300));
      throw new BusyError();
    }
  }
  const data = await res.json();
  if (!res.ok) throw new Error(`groq ${res.status}: ${JSON.stringify(data).slice(0, 300)}`);
  return data;
}

// Claude: Messages API with client tools, the reply's content blocks kept as they came.
async function anthropicLoop(o: Parameters<typeof runAgent>[0], exec: Exec, retry: Retry): Promise<string> {
  const client = new Anthropic({ apiKey: o.key });
  const messages: Anthropic.MessageParam[] = [...alternate(o.history), { role: "user", content: o.question }];
  if (retry) messages.push({ role: "assistant", content: retry.draft }, { role: "user", content: retry.fix });
  const tools: Anthropic.Tool[] = o.tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.parameters as Anthropic.Tool.InputSchema }));
  for (let round = 0; round <= MAX_ROUNDS; round++) {
    const last = round === MAX_ROUNDS;
    const res = await client.messages.create({
      model: o.model, max_tokens: 16000,
      system: [{ type: "text", text: o.system, cache_control: { type: "ephemeral" } }],
      tools, tool_choice: last ? { type: "none" } : { type: "auto" },
      // @ts-ignore output_config (effort) is newer than some SDK typings
      output_config: { effort: "low" },
      messages,
    });
    const text = res.content.filter((b): b is Anthropic.TextBlock => b.type === "text").map((b) => b.text).join("");
    const uses = res.content.filter((b): b is Anthropic.ToolUseBlock => b.type === "tool_use");
    if (res.stop_reason !== "tool_use" || !uses.length || last) return text;
    messages.push({ role: "assistant", content: res.content });
    const results = await Promise.all(uses.map((u) => exec(u.name, u.input)));
    messages.push({ role: "user", content: uses.map((u, i) => ({ type: "tool_result" as const, tool_use_id: u.id, content: results[i] })) });
  }
  return "";
}

/** Anthropic needs strictly alternating turns starting with the user. */
export function alternate(turns: Turn[]): Anthropic.MessageParam[] {
  const out: Turn[] = [];
  for (const t of turns) {
    if (out.length === 0 && t.role !== "user") continue;
    const last = out[out.length - 1];
    if (last && last.role === t.role) last.content += "\n" + t.content;
    else out.push({ ...t });
  }
  // The question that follows is a user turn: drop a trailing user turn's duplicate role.
  if (out.length && out[out.length - 1].role === "user") out.pop();
  return out;
}
