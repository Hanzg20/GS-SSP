// Turning what a manager said into an expense or a reminder.
//
// The model reads the sentence and returns the parts (what, how much, when);
// the amount is then re-read here from the words it quoted ("二百三十四块五",
// "$234.50"), and dates are range-checked, so a misheard or mis-converted
// number shows up flagged on the confirmation card instead of being booked.
// Nothing is saved until the manager confirms on the terminal.

export const EXPENSE_CATEGORIES = ["INVENTORY", "RENT", "UTILITIES", "PAYROLL", "REPAIRS", "MARKETING", "FEES", "OTHER"] as const;
export type ExpenseCategory = typeof EXPENSE_CATEGORIES[number];

/** Words that make a message worth checking for an expense or a reminder (questions skip the extra step). */
export function looksLikeCommand(q: string): boolean {
  return /提醒|记得|别忘|待办|记一笔|记个账|记账|入账|花了|付了|支付了|交了|缴了|开支|费用|支出|买了|进货花|送货单|送货|送单|外卖单|建送单|外卖|配送|外送/.test(q) ||
    /\b(remind|reminder|to-?do|don'?t forget|spent|paid|expense|bought|delivery|delivery\s*order)\b/i.test(q);
}

const CN_DIGIT: Record<string, number> = {
  "零": 0, "〇": 0, "一": 1, "壹": 1, "二": 2, "贰": 2, "两": 2, "俩": 2, "三": 3, "叁": 3, "四": 4, "肆": 4,
  "五": 5, "伍": 5, "六": 6, "陆": 6, "七": 7, "柒": 7, "八": 8, "捌": 8, "九": 9, "玖": 9,
};
const CN_UNIT: Record<string, number> = { "十": 10, "拾": 10, "百": 100, "佰": 100, "千": 1000, "仟": 1000 };

/** "二百三十四" -> 234, "一千二" -> 1200 (colloquial), "两万三" -> 23000, "十五" -> 15. null if not a numeral. */
export function cnInt(s: string): number | null {
  if (!s) return null;
  let total = 0, section = 0, num = 0, lastUnit = 0, prevWasUnit = false, seen = false;
  for (const ch of s) {
    if (ch in CN_DIGIT) {
      num = CN_DIGIT[ch]; prevWasUnit = false; seen = true;
    } else if (ch in CN_UNIT) {
      const u = CN_UNIT[ch];
      section += (num === 0 && !seen ? 1 : num === 0 ? (prevWasUnit ? 0 : 1) : num) * u;
      num = 0; lastUnit = u; prevWasUnit = true; seen = true;
    } else if (ch === "万" || ch === "萬") {
      section += num; total += (section || 1) * 10000; section = 0; num = 0; lastUnit = 10000; prevWasUnit = true; seen = true;
    } else {
      return null;
    }
  }
  if (!seen) return null;
  // "一千二" = 1200, "两万三" = 23000: a bare digit right after a unit is one unit down.
  if (num > 0 && prevWasUnit === false && lastUnit >= 100 && s.length >= 2 && (s[s.length - 2] in CN_UNIT || s[s.length - 2] === "万" || s[s.length - 2] === "萬")) {
    section += num * (lastUnit / 10);
  } else {
    section += num;
  }
  return total + section;
}

/**
 * The amount in a phrase, in dollars: "$234.50", "234块5", "二百三十四块五",
 * "三块五毛二", "两千零五十元", "一百二十三点四五". null if there's no amount in it.
 */
export function parseAmount(text: string): number | null {
  if (!text) return null;
  let t = text.replace(/[\s,，]/g, "").replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0));
  t = t.replace(/(加币|加元|美元|美金|dollars?|bucks?|cad|usd|\$)/gi, "");
  if (/\d/.test(t)) {
    const m = t.match(/(\d+(?:\.\d+)?)(?:(块|元)(\d{1,2}))?/);
    if (!m) return null;
    let v = parseFloat(m[1]);
    if (m[3]) v += m[3].length === 1 ? parseInt(m[3]) / 10 : parseInt(m[3]) / 100;
    return round2(v);
  }
  const m = t.match(/^([零〇一壹二贰两俩三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万萬]+)(?:(块|元|点)(.*))?$/);
  if (!m) return null;
  const whole = cnInt(m[1]);
  if (whole === null) return null;
  let v = whole;
  const sep = m[2], rest = (m[3] ?? "").replace(/[钱整]$/, "");
  if (sep === "点" && rest) {
    let place = 0.1;
    for (const ch of rest) {
      if (!(ch in CN_DIGIT)) return null;
      v += CN_DIGIT[ch] * place; place /= 10;
    }
  } else if (rest) {
    const jiao = rest.match(/^([零〇一二两三四五六七八九])(毛|角)?(?:([零〇一二两三四五六七八九])分?)?$/);
    const fenOnly = rest.match(/^零([一二两三四五六七八九])分?$/);
    if (fenOnly) v += CN_DIGIT[fenOnly[1]] / 100;
    else if (jiao) v += CN_DIGIT[jiao[1]] / 10 + (jiao[3] ? CN_DIGIT[jiao[3]] / 100 : 0);
    else return null;
  }
  return round2(v);
}

const round2 = (v: number) => Math.round(v * 100) / 100;

/** Minutes east of UTC for a zone at a given instant (e.g. Toronto in summer: -240). */
function tzOffsetMinutes(tz: string, at: Date): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
  }).formatToParts(at);
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value);
  const asUtc = Date.UTC(get("year"), get("month") - 1, get("day"), get("hour"), get("minute"), get("second"));
  return Math.round((asUtc - at.getTime()) / 60000);
}

/** "2026-10-11T09:00" as wall-clock time in [tz] -> the instant, or null. */
export function localToInstant(local: string, tz: string): Date | null {
  const m = local?.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/);
  if (!m) return null;
  const guess = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);
  let off = tzOffsetMinutes(tz, new Date(guess));
  off = tzOffsetMinutes(tz, new Date(guess - off * 60000)); // settle across a DST change
  return new Date(guess - off * 60000);
}

/** The store's local now, for the model: "2026-10-10 22:41 Saturday". */
export function localNow(tz: string, now = new Date()): { stamp: string; date: string } {
  const f = new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
  const time = new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(now);
  const wd = new Intl.DateTimeFormat("en-US", { timeZone: tz, weekday: "long" }).format(now);
  return { stamp: `${f} ${time} ${wd}`, date: f };
}

/**
 * Last week, this week and next week (Monday first) with their dates, for the
 * prompt: asked on a Saturday, the model took "上周五" (last week's Friday) to
 * be yesterday. Weeks are worked out here, not left to it.
 */
export function calendarLines(tz: string, now = new Date()): string {
  const today = localNow(tz, now).date;
  const base = new Date(today + "T12:00:00Z");
  const dow = (base.getUTCDay() + 6) % 7; // Monday = 0
  const monday = new Date(base.getTime() - dow * 86_400_000);
  const names = ["Mon 周一", "Tue 周二", "Wed 周三", "Thu 周四", "Fri 周五", "Sat 周六", "Sun 周日"];
  const week = (offset: number, label: string) => label + ": " + names.map((n, i) =>
    `${n} ${new Date(monday.getTime() + (offset * 7 + i) * 86_400_000).toISOString().slice(0, 10)}`).join(", ");
  return [week(-1, "Last week 上周"), week(0, "This week 本周"), week(1, "Next week 下周")].join("\n");
}

export type DeliveryItem = {
  name: string;
  quantity: number;
  unitPrice?: number | null;
};

export type Proposal =
  | { kind: "expense"; vendor: string; category: ExpenseCategory; amount: number | null; amountWords: string; spentOn: string; dateWords: string; note: string; check: string[] }
  | { kind: "reminder"; text: string; dueAt: string | null; whenWords: string; check: string[] }
  | { kind: "delivery_order"; customer_name: string; customer_phone: string; address: string; note: string; items: DeliveryItem[]; check: string[] };

/** The model's JSON -> a proposal with every number re-checked, or null if it isn't an expense/reminder/delivery. */
// deno-lint-ignore no-explicit-any
export function toProposal(raw: any, tz: string, now = new Date()): Proposal | null {
  if (!raw || typeof raw !== "object") return null;
  const today = localNow(tz, now).date;
  if (raw.kind === "expense") {
    const check: string[] = [];
    const words = String(raw.amount_text ?? "").slice(0, 60);
    const fromWords = parseAmount(words);
    const fromModel = typeof raw.amount === "number" && isFinite(raw.amount) ? round2(raw.amount) : null;
    let amount = fromWords ?? fromModel;
    if (fromWords === null || (fromModel !== null && fromModel !== fromWords)) check.push("amount");
    if (amount !== null && (amount <= 0 || amount >= 1_000_000)) { amount = null; check.push("amount"); }
    let spentOn = String(raw.date ?? "");
    const d = Date.parse(spentOn + "T12:00:00Z"), t = Date.parse(today + "T12:00:00Z");
    if (!/^\d{4}-\d{2}-\d{2}$/.test(spentOn) || isNaN(d) || d > t + 86_400_000 || d < t - 366 * 86_400_000) {
      spentOn = today; check.push("date");
    }
    const category = (EXPENSE_CATEGORIES as readonly string[]).includes(raw.category) ? raw.category : "OTHER";
    return {
      kind: "expense", vendor: String(raw.vendor ?? "").slice(0, 120), category, amount,
      amountWords: words, spentOn, dateWords: String(raw.date_text ?? "").slice(0, 60),
      note: String(raw.note ?? "").slice(0, 500), check: [...new Set(check)],
    };
  }
  if (raw.kind === "reminder") {
    const text = String(raw.text ?? "").trim().slice(0, 300);
    if (!text) return null;
    const check: string[] = [];
    const at = localToInstant(String(raw.due ?? ""), tz);
    let dueAt: string | null = at ? at.toISOString() : null;
    if (!at || at.getTime() < now.getTime() - 5 * 60000 || at.getTime() > now.getTime() + 366 * 86_400_000) {
      dueAt = null; check.push("due");
    }
    return { kind: "reminder", text, dueAt, whenWords: String(raw.when_text ?? "").slice(0, 60), check };
  }
  if (raw.kind === "delivery_order" || raw.kind === "delivery") {
    const check: string[] = [];
    const customer_name = String(raw.customer_name ?? raw.customer ?? "").trim().slice(0, 100);
    const customer_phone = String(raw.customer_phone ?? raw.phone ?? "").trim().slice(0, 30);
    const address = String(raw.address ?? "").trim().slice(0, 200);
    const note = String(raw.note ?? "").trim().slice(0, 200);
    if (!customer_name) check.push("customer_name");
    if (!address) check.push("address");

    const items: DeliveryItem[] = Array.isArray(raw.items)
      ? raw.items.map((it: any) => ({
          name: String(it?.name ?? "").trim(),
          quantity: Math.max(1, parseInt(it?.quantity ?? 1) || 1),
          unitPrice: typeof it?.unit_price === "number" ? round2(it.unit_price) : typeof it?.price === "number" ? round2(it.price) : null
        })).filter((it: DeliveryItem) => it.name.length > 0)
      : [];

    if (items.length === 0) check.push("items");

    return {
      kind: "delivery_order",
      customer_name,
      customer_phone,
      address,
      note,
      items,
      check
    };
  }
  return null;
}

export function parsePrompt(_lang: string, nowStamp: string, calendar: string): string {
  return `You turn a store manager's sentence into a record. The store's local time now is ${nowStamp}.
Weeks start on Monday. Use this calendar for any weekday words ("上周五" = last week's Friday, "下周一" = next week's Monday, "周三"/"this Wednesday" = this week's):
${calendar}
Reply with ONE JSON object and nothing else:
- An expense they paid: {"kind":"expense","vendor":"<who was paid, or \\"\\">","category":"<one of ${EXPENSE_CATEGORIES.join(", ")}>","amount":<number in dollars>,"amount_text":"<the amount exactly as they said it>","date":"<YYYY-MM-DD the money was spent; today if not said>","date_text":"<the date words exactly as said, or \"\">","note":"<short description in their language>"}
  Categories: INVENTORY = goods for resale / stock purchases; RENT; UTILITIES = hydro, water, gas, internet, phone; PAYROLL = wages; REPAIRS = maintenance, equipment fixes; MARKETING = ads, flyers, promotions; FEES = bank, card processing, licences, accounting; OTHER = anything else.
- A to-do to be reminded of: {"kind":"reminder","text":"<the task, short, in their language, without the time words>","due":"<YYYY-MM-DDTHH:mm local time>","when_text":"<all the date and time words exactly as they said them>"}
  No time of day given: use 09:00. Only a time given: today if still ahead, else tomorrow. "下周X"/"next X" is the coming week's day.
- A delivery order to create: {"kind":"delivery_order","customer_name":"<customer name>","customer_phone":"<phone number or \"\">","address":"<delivery destination/address>","note":"<special note or \"\">","items":[{"name":"<item name>","quantity":<integer quantity>,"price":<optional unit price in dollars>}]}
- Anything else (a question, small talk): {"kind":"none"}
Never invent an amount or a time that wasn't said: use null for amount, "" for due.`;
}

