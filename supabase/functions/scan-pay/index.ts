// Ourea Scan pay at the table: the customer pays the table's bill (all or a
// share, plus a tip) on their phone through the store's Nuvei account.
// Called from web/ourea-scan without a login: the table's QR secret is the
// credential. Amounts, tax and limits are the database's
// (docs/migrations/2026-10-28_scan_pay.sql); card data only ever goes to Nuvei
// (Simply Connect on the phone).
//
// Actions:
//   open    {token, amount_cents, tip_cents, email} -> the Nuvei session the phone renders
//           (email: the customer's, required by Nuvei; passed through, not stored)
//   confirm {token, id}  -> PAID / FAILED / PENDING, as Nuvei and our records say
//   cancel  {token, id}  -> the customer closed the payment without paying
// A payment is PAID only after Nuvei confirmed it to the server (here through
// getPaymentStatus, or scan-pay-dmn) -- never on the phone's word.

import { createClient, type SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { corsHeaders } from "../_shared/cors.ts";
import { getPaymentStatus, isEmail, loadAccount, openOrder, toAmount } from "../_shared/nuvei-checkout.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

// deno-lint-ignore no-explicit-any
type Supabase = SupabaseClient<any, "public", any>;

// deno-lint-ignore no-explicit-any
async function rpc(supabase: Supabase, fn: string, args: Record<string, unknown>): Promise<any> {
  const { data, error } = await supabase.rpc(fn, args);
  if (error) throw new Error(`${fn}: ${error.message}`);
  return data;
}

async function open(supabase: Supabase, token: string, amountCents: number, tipCents: number, email: string): Promise<Response> {
  if (!Number.isInteger(amountCents) || !Number.isInteger(tipCents)) return json({ error: "bad_request" }, 400);
  if (!isEmail(email)) return json({ error: "bad_email" }, 400);
  const opened = await rpc(supabase, "dine_online_pay_open", { p_token: token, p_amount_cents: amountCents, p_tip_cents: tipCents });
  if (!opened?.success) return json({ error: opened?.message ?? "failed", balance_cents: opened?.balance_cents }, 409);

  const account = await loadAccount(supabase, opened.acquirer_account_id);
  if (!account) {
    await rpc(supabase, "dine_online_pay_fail", { p_id: opened.id, p_reason: "account_incomplete" });
    console.error(`[scan-pay] Nuvei account ${opened.acquirer_account_id} is not usable (missing ids / secret)`);
    return json({ error: "online_pay_off" }, 409);
  }

  const total = opened.amount_cents + opened.tip_cents;
  try {
    const order = await openOrder(account, opened.id, total, email, `${SUPABASE_URL}/functions/v1/scan-pay-dmn`);
    await rpc(supabase, "dine_online_pay_attach", { p_id: opened.id, p_session_token: order.sessionToken, p_order_id: order.orderId });
    return json({
      id: opened.id,
      session_token: order.sessionToken,
      merchant_id: account.merchantId,
      merchant_site_id: account.merchantSiteId,
      env: account.env,
      amount: toAmount(total),
      amount_cents: opened.amount_cents,
      tax_cents: opened.tax_cents,
      tip_cents: opened.tip_cents,
      currency: "CAD",
      country: "CA",
      email,
    });
  } catch (e) {
    console.error(`[scan-pay] openOrder failed for ${opened.id}:`, e);
    await rpc(supabase, "dine_online_pay_fail", { p_id: opened.id, p_reason: String(e).slice(0, 200) });
    return json({ error: "gateway_error" }, 502);
  }
}

async function confirm(supabase: Supabase, token: string, id: string): Promise<Response> {
  const p = await rpc(supabase, "dine_online_pay_get", { p_id: id, p_token: token, p_claim_status_check: true });
  if (!p) return json({ error: "not_found" }, 404);
  if (p.status === "PAID") return json({ status: "PAID", balance_cents: p.balance_cents });
  // FAILED stays settle-able (a late DMN for money that was taken is still recorded),
  // but the phone is told what we know now.
  if (p.status === "FAILED") return json({ status: "FAILED" });
  if (!p.claimed) return json({ status: "PENDING" });

  const account = await loadAccount(supabase, p.acquirer_account_id);
  if (!account) return json({ status: "PENDING" });
  const outcome = await getPaymentStatus(account, p.session_token, id);
  if (outcome.kind === "approved") {
    const settled = await rpc(supabase, "dine_online_pay_settle", {
      p_id: id, p_transaction_id: outcome.transactionId, p_paid_cents: outcome.paidCents,
      p_auth_code: outcome.authCode, p_card: outcome.card,
    });
    if (settled?.success) return json({ status: "PAID", balance_cents: settled.balance_cents });
    // Charged, but not what we asked for: a person sorts it out, the phone says ask staff.
    console.error(`[scan-pay] approved but not settled ${id}: ${settled?.message}`);
    return json({ status: "REVIEW" });
  }
  if (outcome.kind === "declined") {
    await rpc(supabase, "dine_online_pay_fail", { p_id: id, p_reason: outcome.reason });
    return json({ status: "FAILED" });
  }
  console.warn(`[scan-pay] status of ${id} not known yet: ${outcome.reason}`);
  return json({ status: "PENDING" });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad_request" }, 400);
  }
  const token = typeof body.token === "string" ? body.token : "";
  if (!token || token.length > 64) return json({ error: "bad_request" }, 400);
  const id = typeof body.id === "string" ? body.id : "";

  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
  try {
    switch (body.action) {
      case "open":
        return await open(supabase, token, Number(body.amount_cents), Number(body.tip_cents ?? 0),
          typeof body.email === "string" ? body.email.trim() : "");
      case "confirm":
        return id ? await confirm(supabase, token, id) : json({ error: "bad_request" }, 400);
      case "cancel": {
        if (!id) return json({ error: "bad_request" }, 400);
        const p = await rpc(supabase, "dine_online_pay_get", { p_id: id, p_token: token, p_claim_status_check: false });
        if (p?.status === "OPEN") await rpc(supabase, "dine_online_pay_fail", { p_id: id, p_reason: "cancelled_by_customer" });
        return json({ ok: true });
      }
      default:
        return json({ error: "bad_request" }, 400);
    }
  } catch (e) {
    console.error("[scan-pay]", e);
    return json({ error: "failed" }, 500);
  }
});
