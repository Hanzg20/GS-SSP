// Nuvei online payments (REST API + Simply Connect) for Ourea Scan pay at the
// table -- used by scan-pay (open / confirm) and scan-pay-dmn (Nuvei's server
// notification). docs/migrations/2026-10-28_scan_pay.sql.
//
// Checked 2026-10-28:
// - Hosts: ppp-test.nuvei.com (int) and secure.safecharge.com (prod) both
//   answer POST /ppp/api/v1/openOrder.do with Nuvei's own JSON error
//   ("Invalid merchant site id") -- the real API, not a guessed host.
// - openOrder checksum, per docs.nuvei.com "Quick Start to Simply Connect":
//   sha256(merchantId + merchantSiteId + clientRequestId + amount + currency +
//   timeStamp + merchantSecretKey), no separators.
// - DMN advanceResponseChecksum, per the Payment Page quick start:
//   sha256(secret + totalAmount + currency + responseTimeStamp +
//   PPP_TransactionID + Status + productId); Nuvei's worked example
//   (Secret123... -> 4cf35738...) reproduces with sha256sum.
// - getPaymentStatus: body {sessionToken}; call it once, after the payment
//   has finished -- Nuvei may block an IP that polls it.
// Not yet exercised with real sandbox credentials: field casing in the DMN
// is read case-insensitively for that reason.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

export type NuveiEnv = "int" | "prod";

export interface NuveiAccount {
  id: string;
  merchantId: string;
  merchantSiteId: string;
  secret: string;
  env: NuveiEnv;
}

const API_BASE: Record<NuveiEnv, string> = {
  int: "https://ppp-test.nuvei.com/ppp/api/v1",
  prod: "https://secure.safecharge.com/ppp/api/v1",
};

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Cents as Nuvei's decimal amount string: 1234 -> "12.34". */
export function toAmount(cents: number): string {
  return (cents / 100).toFixed(2);
}

/** Nuvei's decimal amount back to cents; null when it isn't one. */
export function toCents(amount: string | number | null | undefined): number | null {
  const n = Number(amount);
  return amount === null || amount === undefined || amount === "" || !Number.isFinite(n) ? null : Math.round(n * 100);
}

/** YYYYMMDDHHmmss, UTC. */
export function timeStamp(d = new Date()): string {
  return d.toISOString().replace(/[-:T]/g, "").slice(0, 14);
}

/** The store's Nuvei account (service_role reads the secret; the CMP never sees it). */
// deno-lint-ignore no-explicit-any
export async function loadAccount(supabase: SupabaseClient<any, "public", any>, accountId: string): Promise<NuveiAccount | null> {
  const { data, error } = await supabase
    .from("merchant_acquirer_accounts")
    .select("id, acquirer, status, env, external_merchant_id, merchant_site_id, shared_secret")
    .eq("id", accountId)
    .maybeSingle();
  if (error || !data) return null;
  if (data.acquirer !== "nuvei" || data.status !== "ACTIVE") return null;
  if (!data.external_merchant_id || !data.merchant_site_id || !data.shared_secret) return null;
  return {
    id: data.id,
    merchantId: data.external_merchant_id,
    merchantSiteId: data.merchant_site_id,
    secret: data.shared_secret,
    env: data.env === "prod" ? "prod" : "int",
  };
}

export interface OpenOrderResult {
  sessionToken: string;
  orderId: string;
}

/**
 * A Nuvei order for [cents] CAD, our attempt id as clientUniqueId / clientRequestId.
 * [email]: the customer's; Nuvei refuses a card payment without one ("Mandatory
 * fields are missing", sandbox 2026-10-28). Passed through, never stored by us.
 */
export async function openOrder(account: NuveiAccount, attemptId: string, cents: number, email: string, notificationUrl: string): Promise<OpenOrderResult> {
  const amount = toAmount(cents);
  const currency = "CAD";
  const ts = timeStamp();
  const checksum = await sha256Hex(account.merchantId + account.merchantSiteId + attemptId + amount + currency + ts + account.secret);
  const res = await fetch(`${API_BASE[account.env]}/openOrder.do`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      merchantId: account.merchantId,
      merchantSiteId: account.merchantSiteId,
      clientUniqueId: attemptId,
      clientRequestId: attemptId,
      currency,
      amount,
      transactionType: "Sale",
      billingAddress: { country: "CA", email },
      urlDetails: { notificationUrl },
      timeStamp: ts,
      checksum,
    }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.status !== "SUCCESS" || !data.sessionToken) {
    throw new Error(`openOrder ${res.status}: ${data.errCode ?? ""} ${data.reason ?? ""}`.trim());
  }
  return { sessionToken: data.sessionToken, orderId: String(data.orderId ?? "") };
}

/** Good enough to hand Nuvei: one @, a dot in the domain, no spaces. */
export function isEmail(s: string): boolean {
  return s.length <= 100 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s);
}

export type PaymentOutcome =
  | { kind: "approved"; transactionId: string; paidCents: number | null; authCode: string; card: string }
  | { kind: "declined"; reason: string }
  | { kind: "unknown"; reason: string };

/** The result of a finished Simply Connect session, straight from Nuvei. */
export async function getPaymentStatus(account: NuveiAccount, sessionToken: string, attemptId: string): Promise<PaymentOutcome> {
  const res = await fetch(`${API_BASE[account.env]}/getPaymentStatus.do`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionToken }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.status !== "SUCCESS") {
    return { kind: "unknown", reason: `getPaymentStatus ${res.status}: ${data.errCode ?? ""} ${data.reason ?? ""}`.trim() };
  }
  // The session must be the one we opened for this attempt.
  if (data.clientUniqueId && data.clientUniqueId !== attemptId) {
    return { kind: "unknown", reason: `clientUniqueId ${data.clientUniqueId} is not ${attemptId}` };
  }
  const status = String(data.transactionStatus ?? "").toUpperCase();
  if (status === "APPROVED") {
    if (data.currency && data.currency !== "CAD") return { kind: "unknown", reason: `currency ${data.currency}` };
    const card = data.paymentOption?.card;
    return {
      kind: "approved",
      transactionId: String(data.transactionId ?? ""),
      paidCents: toCents(data.amount),
      authCode: String(data.authCode ?? ""),
      card: card ? [card.brand ?? card.cardType, card.last4Digits].filter(Boolean).join(" ") : "",
    };
  }
  if (status === "DECLINED" || status === "ERROR") {
    return { kind: "declined", reason: `${status} ${data.gwErrorReason ?? data.reason ?? ""}`.trim() };
  }
  return { kind: "unknown", reason: `transactionStatus ${status || "missing"}` };
}

/** DMN parameters, names looked up regardless of case. */
export class DmnParams {
  private byLower = new Map<string, string>();
  constructor(params: URLSearchParams) {
    for (const [k, v] of params) this.byLower.set(k.toLowerCase(), v);
  }
  get(name: string): string {
    return this.byLower.get(name.toLowerCase()) ?? "";
  }
}

/** Nuvei signed this DMN with [secret]. */
export async function verifyDmn(p: DmnParams, secret: string): Promise<boolean> {
  const sent = p.get("advanceResponseChecksum").toLowerCase();
  if (!sent) return false;
  const expected = await sha256Hex(
    secret + p.get("totalAmount") + p.get("currency") + p.get("responseTimeStamp") +
      p.get("PPP_TransactionID") + p.get("Status") + p.get("productId"),
  );
  return expected === sent;
}

export type RefundOutcome =
  | { kind: "VOIDED" | "REFUNDED"; transactionId: string }
  | { kind: "failed"; reason: string };

/** The checksum Nuvei's /voidTransaction and /refundTransaction share (optional fields we don't send are left out). */
export function refundChecksumInput(account: NuveiAccount, clientRequestId: string, clientUniqueId: string, amount: string,
                                    currency: string, relatedTransactionId: string, ts: string): string {
  return account.merchantId + account.merchantSiteId + clientRequestId + clientUniqueId + amount + currency +
    relatedTransactionId + ts + account.secret;
}

async function financialOp(account: NuveiAccount, op: "voidTransaction" | "refundTransaction", attemptId: string,
                           relatedTransactionId: string, cents: number, currency: string): Promise<RefundOutcome> {
  const clientRequestId = crypto.randomUUID();
  const clientUniqueId = `${attemptId}-${op === "voidTransaction" ? "void" : "refund"}`;
  const amount = toAmount(cents);
  const ts = timeStamp();
  const checksum = await sha256Hex(refundChecksumInput(account, clientRequestId, clientUniqueId, amount, currency, relatedTransactionId, ts));
  const res = await fetch(`${API_BASE[account.env]}/${op}.do`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      merchantId: account.merchantId,
      merchantSiteId: account.merchantSiteId,
      clientRequestId,
      clientUniqueId,
      amount,
      currency,
      relatedTransactionId,
      timeStamp: ts,
      checksum,
    }),
  });
  const data = await res.json().catch(() => ({}));
  if (res.ok && data.status === "SUCCESS" && String(data.transactionStatus).toUpperCase() === "APPROVED") {
    return { kind: op === "voidTransaction" ? "VOIDED" : "REFUNDED", transactionId: String(data.transactionId ?? "") };
  }
  return {
    kind: "failed",
    reason: `${op} ${res.status}: ${data.transactionStatus ?? data.status ?? ""} ${data.gwErrorReason ?? data.reason ?? ""} ${data.gwErrorCode ?? data.errCode ?? ""}`.trim(),
  };
}

/**
 * Gives a whole online payment back: a void while Nuvei hasn't settled it (no
 * money moved yet), else a refund of the settled sale -- as the Ourea does for
 * a card sale. [cents] is what was charged, tip included.
 */
export async function voidOrRefund(account: NuveiAccount, attemptId: string, transactionId: string, cents: number,
                                   currency: string): Promise<RefundOutcome> {
  const voided = await financialOp(account, "voidTransaction", attemptId, transactionId, cents, currency);
  if (voided.kind !== "failed") return voided;
  const refunded = await financialOp(account, "refundTransaction", attemptId, transactionId, cents, currency);
  if (refunded.kind !== "failed") return refunded;
  return { kind: "failed", reason: `${voided.reason}; ${refunded.reason}` };
}
