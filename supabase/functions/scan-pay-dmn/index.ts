// Nuvei's server notification (DMN) for Ourea Scan pay at the table: records
// a payment Nuvei approved even when the customer's phone closed before
// scan-pay's confirm ran. openOrder points urlDetails.notificationUrl here.
//
// verify_jwt is off (the caller is Nuvei); the advanceResponseChecksum, signed
// with the store's own Nuvei secret, is the only thing that makes a DMN count.
// Answers 200 to anything it has read, so Nuvei stops resending it.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { DmnParams, loadAccount, toCents, verifyDmn } from "../_shared/nuvei-checkout.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.serve(async (req: Request) => {
  // Nuvei sends the parameters as a query string or a form body.
  const params = new URLSearchParams(new URL(req.url).search);
  if (req.method === "POST") {
    for (const [k, v] of new URLSearchParams(await req.text())) params.set(k, v);
  }
  const p = new DmnParams(params);

  const id = p.get("clientUniqueId") || p.get("merchant_unique_id") || p.get("clientRequestId");
  if (!UUID.test(id)) {
    console.warn(`[scan-pay-dmn] not one of ours: ${id || "(no id)"} ppp=${p.get("PPP_TransactionID")}`);
    return new Response("OK", { status: 200 });
  }

  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
  const { data: attempt, error } = await supabase.rpc("dine_online_pay_get", { p_id: id, p_token: null, p_claim_status_check: false });
  if (error) {
    console.error("[scan-pay-dmn] lookup failed:", error);
    return new Response("db_error", { status: 500 });
  }
  if (!attempt) {
    console.warn(`[scan-pay-dmn] unknown attempt ${id}`);
    return new Response("OK", { status: 200 });
  }

  const account = await loadAccount(supabase, attempt.acquirer_account_id);
  if (!account || !(await verifyDmn(p, account.secret))) {
    console.warn(`[scan-pay-dmn] checksum failed for ${id}`);
    return new Response("invalid checksum", { status: 401 });
  }

  const status = p.get("Status").toUpperCase();
  const type = p.get("transactionType").toLowerCase();
  if (type && type !== "sale" && type !== "auth") {
    // A refund / void on the same order: not this function's to record.
    console.log(`[scan-pay-dmn] ${id}: ${type} ${status}, ignored`);
    return new Response("OK", { status: 200 });
  }

  if (status === "APPROVED") {
    if (p.get("currency") && p.get("currency") !== "CAD") {
      console.error(`[scan-pay-dmn] ${id} approved in ${p.get("currency")}`);
      return new Response("OK", { status: 200 });
    }
    const { data: settled, error: settleError } = await supabase.rpc("dine_online_pay_settle", {
      p_id: id,
      p_transaction_id: p.get("TransactionID") || p.get("PPP_TransactionID"),
      p_paid_cents: toCents(p.get("totalAmount")),
      p_auth_code: p.get("AuthCode"),
      p_card: [p.get("cardCompany"), p.get("cardNumber").slice(-4)].filter(Boolean).join(" "),
    });
    if (settleError) {
      console.error("[scan-pay-dmn] settle failed:", settleError);
      return new Response("db_error", { status: 500 });
    }
    if (!settled?.success) console.error(`[scan-pay-dmn] approved but not settled ${id}: ${settled?.message}`);
  } else if (status === "DECLINED" || status === "ERROR") {
    await supabase.rpc("dine_online_pay_fail", { p_id: id, p_reason: `${status} ${p.get("Reason") || p.get("gwErrorReason")}`.trim() });
  } else {
    console.log(`[scan-pay-dmn] ${id}: status ${status || "(none)"}`);
  }
  return new Response("OK", { status: 200 });
});
