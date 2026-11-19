// Gives back the money of an Arke order that is REJECTED: voided at Nuvei while not settled, refunded once it is.
// One path for the desk's refund (arke-pay-refund) and the automatic one (arke-sweep):
// online_refund_begin (PAID -> REFUNDING, only one caller wins) -> Nuvei -> online_refund_done / online_refund_abort.
// docs/migrations/2026-11-16_arke_online_orders.sql, 2026-11-19_arke_accept_deadline.sql.

import { loadAccount, voidOrRefund } from "./nuvei-checkout.ts";

export type RefundResult =
  | { ok: true; status: string; transactionId: string }
  | { ok: false; error: string; reason?: string; httpStatus: number };

// deno-lint-ignore no-explicit-any
export async function refundRejectedOrder(supabase: any, orgId: string, orderId: string, tag: string): Promise<RefundResult> {
  const { data: begun, error: beginError } = await supabase.rpc("online_refund_begin", { p_org: orgId, p_order_id: orderId });
  if (beginError) {
    console.error(`[${tag}] begin failed for order ${orderId}:`, beginError);
    return { ok: false, error: "failed", httpStatus: 500 };
  }
  if (!begun?.success) return { ok: false, error: begun?.message ?? "failed", httpStatus: 409 };

  const abort = (reason: string) => supabase.rpc("online_refund_abort", { p_id: begun.id, p_reason: reason });
  const account = await loadAccount(supabase, begun.acquirer_account_id);
  if (!account) {
    await abort("account_incomplete");
    return { ok: false, error: "online_pay_off", httpStatus: 409 };
  }

  let outcome;
  try {
    outcome = await voidOrRefund(account, begun.id, begun.transaction_id, begun.cents, "CAD");
  } catch (e) {
    // Unknown whether Nuvei acted: the payment stays REFUNDING, so a retry can't refund twice;
    // a person checks Nuvei's Cpanel.
    console.error(`[${tag}] Nuvei call failed for order ${orderId}:`, e);
    return { ok: false, error: "gateway_error", httpStatus: 502 };
  }
  if (outcome.kind === "failed") {
    console.warn(`[${tag}] order ${orderId} not refunded: ${outcome.reason}`);
    await abort(outcome.reason);
    return { ok: false, error: "declined", reason: outcome.reason, httpStatus: 409 };
  }

  const { error: doneError } = await supabase.rpc("online_refund_done", {
    p_id: begun.id, p_status: outcome.kind, p_refund_tx_id: outcome.transactionId,
  });
  if (doneError) {
    // The money went back but isn't recorded: loud, for a person to fix (Nuvei Cpanel has it).
    console.error(`[${tag}] order ${orderId} ${outcome.kind} at Nuvei (${outcome.transactionId}) but not recorded:`, doneError);
  }
  return { ok: true, status: outcome.kind, transactionId: outcome.transactionId };
}
