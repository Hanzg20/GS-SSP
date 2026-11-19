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

/** Sends the paid-order email; answers what happened ("SENT", "FAILED: ...", "SKIPPED: ...") for online_pay_mail_result. */
export async function mailPaidOrder(m: PaidMail | null | undefined): Promise<string> {
  if (!m?.email || !m.token) return "SKIPPED: no email";
  if (!Deno.env.get("SMTP_HOST")) { console.warn("[arke-mail] SMTP not configured; not mailing"); return "SKIPPED: SMTP not configured"; }
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

  return await send(m.email, w.subject(m.store, m.code), text, html, m.store, `order ${m.code}`);
}

/** One email over the SMTP account; answers "SENT" or "FAILED: ...". */
async function send(to: string, subject: string, text: string, html: string, store: string, what: string): Promise<string> {
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
    await transport.sendMail({ from: fromHeader(store), to, subject, text, html });
    console.log(`[arke-mail] mailed ${what} of ${store}`);
    return "SENT";
  } catch (e) {
    // Host and port in the log: a wrong SMTP_PORT (456 for 465) once looked like a plain timeout for two days.
    console.error(`[arke-mail] send failed for ${what} via ${Deno.env.get("SMTP_HOST")}:${port}: ${e}`);
    return `FAILED: ${e} (${Deno.env.get("SMTP_HOST")}:${port})`;
  }
}

export interface RefundMail {
  email: string | null;
  lang: string | null;
  token: string;
  code: string;
  store: string;
  total_cents: number;
  refund: string;          // VOIDED | REFUNDED
  auto: boolean;           // nobody accepted it in time
  reason: string | null;   // the store's own words when it rejected
}

const OFF: Record<string, { subject: (s: string, c: string) => string; auto: string; rejected: string; reason: string; voided: string; refunded: string; look: string }> = {
  zh: {
    subject: (s, c) => `${s} · 订单 ${c} 已取消，钱已退回`,
    auto: "很抱歉，店家没能及时确认你的订单，订单已自动取消。", rejected: "很抱歉，店家取消了你的订单。", reason: "店家说明",
    voided: "这笔付款已在你的卡上撤销，不会扣款（银行可能会暂时显示一笔待处理金额，几天内消失）。",
    refunded: "钱已退回你的卡，一般 3–10 个工作日到账，视发卡行而定。", look: "订单详情：",
  },
  en: {
    subject: (s, c) => `${s} · Order ${c} cancelled, money back`,
    auto: "Sorry, the store could not confirm your order in time, so it was cancelled.", rejected: "Sorry, the store cancelled your order.", reason: "The store says",
    voided: "The payment was cancelled on your card, so you are not charged (your bank may show a pending amount for a few days).",
    refunded: "The money was refunded to your card. It usually shows within 3–10 business days, depending on your bank.", look: "Your order:",
  },
  fr: {
    subject: (s, c) => `${s} · Commande ${c} annulée, remboursée`,
    auto: "Désolé, le commerce n'a pas pu confirmer votre commande à temps ; elle a été annulée.", rejected: "Désolé, le commerce a annulé votre commande.", reason: "Le commerce précise",
    voided: "Le paiement a été annulé sur votre carte : vous n'êtes pas débité (votre banque peut afficher un montant en attente quelques jours).",
    refunded: "Le montant a été remboursé sur votre carte, généralement sous 3 à 10 jours ouvrables selon votre banque.", look: "Votre commande :",
  },
  ar: {
    subject: (s, c) => `${s} · أُلغي الطلب ${c} وأُعيد المبلغ`,
    auto: "عذرًا، لم يتمكن المتجر من تأكيد طلبك في الوقت المناسب، لذلك أُلغي.", rejected: "عذرًا، ألغى المتجر طلبك.", reason: "ملاحظة المتجر",
    voided: "أُلغيت الدفعة على بطاقتك ولن يُخصم منك شيء (قد يظهر مبلغ معلّق لبضعة أيام).",
    refunded: "أُعيد المبلغ إلى بطاقتك، ويظهر عادةً خلال 3–10 أيام عمل حسب البنك.", look: "تفاصيل طلبك:",
  },
};

/** The "your order is cancelled and your money is back" email; answers like mailPaidOrder. */
export async function mailRefundedOrder(m: RefundMail | null | undefined): Promise<string> {
  if (!m?.email || !m.token) return "SKIPPED: no email";
  if (!Deno.env.get("SMTP_HOST")) { console.warn("[arke-mail] SMTP not configured; not mailing"); return "SKIPPED: SMTP not configured"; }
  const w = OFF[m.lang ?? ""] ?? OFF.en;
  const link = `${SITE}/?o=${m.token}`;
  const total = `$${(m.total_cents / 100).toFixed(2)}`;
  const why = m.auto ? w.auto : w.rejected;
  const money = m.refund === "VOIDED" ? w.voided : w.refunded;
  const reason = !m.auto && m.reason ? `${w.reason}: ${m.reason}` : "";
  const rtl = m.lang === "ar";
  const text = [why, ...(reason ? [reason] : []), "", `${m.code} · ${total}`, money, "", w.look, link].join("\n");
  const html = `<div dir="${rtl ? "rtl" : "ltr"}" style="font-family:Inter,Arial,sans-serif;max-width:480px;margin:0 auto;padding:24px;color:#0a0a0b">
  <p style="font-size:18px;font-weight:700;margin:0 0 4px">${esc(m.store)}</p>
  <p style="margin:0 0 ${reason ? 6 : 20}px;color:#3f3f46">${esc(why)}</p>
  ${reason ? `<p style="margin:0 0 20px;color:#6b6b74;font-style:italic">${esc(reason)}</p>` : ""}
  <div style="border-radius:18px;background:#f4f4f5;padding:20px;text-align:center">
    <div style="font-size:36px;font-weight:800;letter-spacing:-1px;color:#6b6b74;text-decoration:line-through">${esc(m.code)}</div>
    <div style="font-size:15px;color:#3f3f46;margin-top:4px">${esc(total)}</div>
    <div style="font-size:14px;color:#0369a1;font-weight:700;margin-top:10px">${esc(money)}</div>
  </div>
  <p style="margin:20px 0 10px;color:#3f3f46">${esc(w.look)}</p>
  <p style="margin:0 0 24px"><a href="${esc(link)}" style="display:inline-block;background:#0a0a0b;color:#fff;text-decoration:none;font-weight:700;padding:12px 22px;border-radius:999px">${esc(m.code)} →</a></p>
  <p style="font-size:12px;color:#6b6b74;margin:0">${esc((WORDS[m.lang ?? ""] ?? WORDS.en).foot)}</p>
</div>`;
  return await send(m.email, w.subject(m.store, m.code), text, html, m.store, `refund of order ${m.code}`);
}

/** Mails [m] and keeps the outcome on payment [paymentId] (online.payments.mail_status). */
// deno-lint-ignore no-explicit-any
export async function mailAndRecord(supabase: any, paymentId: string, m: PaidMail | null | undefined): Promise<void> {
  const status = await mailPaidOrder(m);
  const { error } = await supabase.rpc("online_pay_mail_result", { p_id: paymentId, p_status: status });
  if (error) console.error(`[arke-mail] could not record mail result for ${paymentId}: ${error.message}`);
}

/** Runs [p] after the response has gone out (Supabase Edge Runtime), else just lets it run. */
export function inBackground(p: Promise<unknown>): void {
  const done = p.catch((e) => console.error("[arke-mail] background:", e));
  // deno-lint-ignore no-explicit-any
  const rt = (globalThis as any).EdgeRuntime;
  if (rt?.waitUntil) rt.waitUntil(done);
}
