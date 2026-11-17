// Arke: the "your order is paid" email with the order's own link (owner 2026-11-17,
// phase 2 of docs/online_ordering_design.md). Sent once, by whichever of arke-pay
// (confirm) / arke-pay-dmn settled the payment first -- online_pay_settle answers
// `mail` only on that first settle. Over the same SMTP account as alert-notify
// (SMTP_HOST / SMTP_PORT / SMTP_USER / SMTP_PASS / SMTP_FROM). A failed send is
// logged, never fails the payment: the customer's page already shows the order.
// Never awaited on a customer's request: callers hand it to inBackground(), and the
// SMTP connection gives up after a few seconds (a hung server once held arke-pay's
// confirm open, 2026-11-17).

import nodemailer from "npm:nodemailer@6.9.14";

export interface PaidMail {
  email: string | null;
  lang: string | null;
  token: string;
  code: string;
  fulfilment: string;
  store: string;
  total_cents: number;
}

const SITE = Deno.env.get("ARKE_SITE_URL") ?? "https://scan-order.si-sky.com";

const WORDS: Record<string, { subject: (s: string, c: string) => string; hello: string; pickup: string; delivery: string; number: string; total: string; follow: string; foot: string }> = {
  zh: {
    subject: (s, c) => `${s} · 订单 ${c} 已付款`,
    hello: "谢谢你的订单！付款已经成功。", pickup: "到店自取", delivery: "配送到家", number: "订单号", total: "合计",
    follow: "点这里随时查看订单进度：", foot: "这封邮件由 GoldSky Arke 代店家发送。有问题请直接联系店家。",
  },
  en: {
    subject: (s, c) => `${s} · Order ${c} is paid`,
    hello: "Thank you for your order! Your payment went through.", pickup: "Pickup", delivery: "Delivery", number: "Order", total: "Total",
    follow: "Follow your order here:", foot: "Sent for the store by GoldSky Arke. For any question, please contact the store.",
  },
  fr: {
    subject: (s, c) => `${s} · Commande ${c} payée`,
    hello: "Merci pour votre commande ! Le paiement est accepté.", pickup: "À emporter", delivery: "Livraison", number: "Commande", total: "Total",
    follow: "Suivez votre commande ici :", foot: "Envoyé pour le commerce par GoldSky Arke. Pour toute question, contactez le commerce.",
  },
  ar: {
    subject: (s, c) => `${s} · تم دفع الطلب ${c}`,
    hello: "شكرًا لطلبك! تم الدفع بنجاح.", pickup: "استلام من المتجر", delivery: "توصيل", number: "الطلب", total: "الإجمالي",
    follow: "تابع طلبك هنا:", foot: "أُرسلت نيابةً عن المتجر عبر GoldSky Arke. لأي سؤال، تواصل مع المتجر.",
  },
};

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

/** "<store> via GoldSky Arke <address>" -- unless SMTP_FROM already carries its own display name. */
function fromHeader(store: string): string {
  const from = Deno.env.get("SMTP_FROM") ?? Deno.env.get("SMTP_USER") ?? "";
  return from.includes("<") ? from : `"${store.replace(/["<>\\]/g, "")} via GoldSky Arke" <${from}>`;
}

export async function mailPaidOrder(m: PaidMail | null | undefined): Promise<void> {
  if (!m?.email || !m.token) return;
  if (!Deno.env.get("SMTP_HOST")) { console.warn("[arke-mail] SMTP not configured; not mailing"); return; }
  const w = WORDS[m.lang ?? ""] ?? WORDS.en;
  const link = `${SITE}/?o=${m.token}`;
  const total = `$${(m.total_cents / 100).toFixed(2)}`;
  const how = m.fulfilment === "OWN_DELIVERY" ? w.delivery : w.pickup;
  const rtl = m.lang === "ar";
  const text = [w.hello, "", `${w.number}: ${m.code} (${how})`, `${w.total}: ${total}`, "", w.follow, link, "", w.foot].join("\n");
  const html = `<div dir="${rtl ? "rtl" : "ltr"}" style="font-family:Inter,Arial,sans-serif;max-width:480px;margin:0 auto;padding:24px;color:#0a0a0b">
  <p style="font-size:18px;font-weight:700;margin:0 0 4px">${esc(m.store)}</p>
  <p style="margin:0 0 20px;color:#3f3f46">${esc(w.hello)}</p>
  <div style="border-radius:18px;background:#f4f4f5;padding:20px;text-align:center">
    <div style="font-size:13px;color:#6b6b74;font-weight:600">${esc(w.number)} · ${esc(how)}</div>
    <div style="font-size:44px;font-weight:800;letter-spacing:-1px">${esc(m.code)}</div>
    <div style="font-size:15px;color:#3f3f46">${esc(w.total)} ${esc(total)}</div>
  </div>
  <p style="margin:20px 0 10px;color:#3f3f46">${esc(w.follow)}</p>
  <p style="margin:0 0 24px"><a href="${esc(link)}" style="display:inline-block;background:#0a0a0b;color:#fff;text-decoration:none;font-weight:700;padding:12px 22px;border-radius:999px">${esc(m.code)} →</a></p>
  <p style="font-size:12px;color:#6b6b74;margin:0">${esc(w.foot)}</p>
</div>`;

  const port = Number(Deno.env.get("SMTP_PORT") ?? "465");
  const transport = nodemailer.createTransport({
    host: Deno.env.get("SMTP_HOST"),
    port,
    secure: port === 465,
    auth: { user: Deno.env.get("SMTP_USER"), pass: Deno.env.get("SMTP_PASS") },
    connectionTimeout: 8000,
    greetingTimeout: 8000,
    socketTimeout: 15000,
  });
  try {
    await transport.sendMail({
      from: fromHeader(m.store),
      to: m.email,
      subject: w.subject(m.store, m.code),
      text,
      html,
    });
    console.log(`[arke-mail] mailed order ${m.code} of ${m.store}`);
  } catch (e) {
    console.error(`[arke-mail] send failed for order ${m.code}: ${e}`);
  }
}

/** Runs [p] after the response has gone out (Supabase Edge Runtime), else just lets it run. */
export function inBackground(p: Promise<unknown>): void {
  const done = p.catch((e) => console.error("[arke-mail] background:", e));
  // deno-lint-ignore no-explicit-any
  const rt = (globalThis as any).EdgeRuntime;
  if (rt?.waitUntil) rt.waitUntil(done);
}
