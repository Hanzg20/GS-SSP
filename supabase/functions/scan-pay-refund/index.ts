// Refund an Ourea Scan online payment from the Ourea: the whole payment, after a
// manager's PIN on the terminal (as for a card sale). Voided at Nuvei while not
// settled, refunded once it is. docs/migrations/2026-10-28_scan_pay_refund.sql.
//
// verify_jwt on: only a signed-in terminal. Its store comes from device_auth_map
// (never from the body), and only that store's online payments can be refunded.
//
// Body {ecr_ref_num, approver_id, approver_name} -> {status: VOIDED | REFUNDED} or {error, reason}.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { corsHeaders } from "../_shared/cors.ts";
import { loadAccount, voidOrRefund } from "../_shared/nuvei-checkout.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

/** The caller's user id; the platform already checked the JWT's signature (verify_jwt). */
function jwtSub(authHeader: string | null): string | null {
  const payload = authHeader?.startsWith("Bearer ") ? authHeader.slice(7).split(".")[1] : undefined;
  if (!payload) return null;
  try {
    const claims = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    return typeof claims.sub === "string" ? claims.sub : null;
  } catch {
    return null;
  }
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
  const ref = typeof body.ecr_ref_num === "string" ? body.ecr_ref_num : "";
  if (!ref.startsWith("NUVEI-")) return json({ error: "bad_request" }, 400);
  const approverId = typeof body.approver_id === "string" && body.approver_id ? body.approver_id : null;
  const approverName = typeof body.approver_name === "string" ? body.approver_name.slice(0, 80) : "";

  const uid = jwtSub(req.headers.get("Authorization"));
  if (!uid) return json({ error: "unauthorized" }, 401);

  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
  const { data: device } = await supabase
    .from("device_auth_map")
    .select("device_sn")
    .eq("auth_user_id", uid)
    .not("org_id", "is", null)
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  if (!device?.device_sn) return json({ error: "not_a_store_terminal" }, 403);

  const { data: begun, error: beginError } = await supabase.rpc("dine_online_refund_begin", { p_device_sn: device.device_sn, p_ecr_ref_num: ref });
  if (beginError) {
    console.error("[scan-pay-refund] begin failed:", beginError);
    return json({ error: "failed" }, 500);
  }
  if (!begun?.success) return json({ error: begun?.message ?? "failed" }, 409);

  const abort = () => supabase.rpc("dine_online_refund_abort", { p_ecr_ref_num: ref });
  const account = await loadAccount(supabase, begun.acquirer_account_id);
  if (!account) {
    await abort();
    return json({ error: "online_pay_off" }, 409);
  }

  let outcome;
  try {
    outcome = await voidOrRefund(account, begun.id, begun.transaction_id, begun.amount_cents, begun.currency);
  } catch (e) {
    // Unknown whether Nuvei acted: keep the claim (2 min) so a quick retry can't refund twice.
    console.error(`[scan-pay-refund] Nuvei call failed for ${ref}:`, e);
    return json({ error: "gateway_error" }, 502);
  }
  if (outcome.kind === "failed") {
    console.warn(`[scan-pay-refund] ${ref} not refunded: ${outcome.reason}`);
    await abort();
    return json({ error: "declined", reason: outcome.reason }, 409);
  }

  const { data: done, error: doneError } = await supabase.rpc("dine_online_refund_done", {
    p_ecr_ref_num: ref, p_status: outcome.kind, p_refund_transaction_id: outcome.transactionId,
    p_staff_id: approverId, p_staff_name: approverName,
  });
  if (doneError || !done?.success) {
    // The money went back but isn't recorded: loud, for a person to fix (Nuvei Cpanel has it).
    console.error(`[scan-pay-refund] ${ref} ${outcome.kind} at Nuvei (${outcome.transactionId}) but not recorded:`, doneError ?? done);
  }
  return json({ status: outcome.kind, refund_transaction_id: outcome.transactionId });
});
