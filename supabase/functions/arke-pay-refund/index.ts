// Give back the payment of an Arke online order the store rejected, from the
// Ourea / Iris that rejected it (online_store_step REJECT first). Voided at Nuvei
// while not settled, refunded once it is. docs/migrations/2026-11-16_arke_online_orders.sql.
//
// verify_jwt on: a signed-in terminal (its store from device_auth_map), or a Cael user
// with orders.manage in the order's store (cmp_online_refund_org, asked as that user) --
// the store never comes from the body, and only that store's rejected orders can be refunded.
//
// Body {order_id} -> {status: VOIDED | REFUNDED} or {error, reason}.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { corsHeaders } from "../_shared/cors.ts";
import { loadAccount, voidOrRefund } from "../_shared/nuvei-checkout.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

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
  const orderId = typeof body.order_id === "string" ? body.order_id : "";
  if (!UUID.test(orderId)) return json({ error: "bad_request" }, 400);

  const uid = jwtSub(req.headers.get("Authorization"));
  if (!uid) return json({ error: "unauthorized" }, 401);

  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
  const { data: device } = await supabase
    .from("device_auth_map")
    .select("device_sn, org_id")
    .eq("auth_user_id", uid)
    .not("org_id", "is", null)
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  let orgId: string | null = device?.org_id ?? null;
  if (!orgId) {
    // Not a terminal: a Cael user (the Online Ordering order desk), checked with their own sign-in.
    const asUser = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: req.headers.get("Authorization")! } } });
    const { data: org } = await asUser.rpc("cmp_online_refund_org", { p_order_id: orderId });
    orgId = typeof org === "string" ? org : null;
  }
  if (!orgId) return json({ error: "not_a_store_terminal" }, 403);

  const { data: begun, error: beginError } = await supabase.rpc("online_refund_begin", { p_org: orgId, p_order_id: orderId });
  if (beginError) {
    console.error("[arke-pay-refund] begin failed:", beginError);
    return json({ error: "failed" }, 500);
  }
  if (!begun?.success) return json({ error: begun?.message ?? "failed" }, 409);

  const abort = (reason: string) => supabase.rpc("online_refund_abort", { p_id: begun.id, p_reason: reason });
  const account = await loadAccount(supabase, begun.acquirer_account_id);
  if (!account) {
    await abort("account_incomplete");
    return json({ error: "online_pay_off" }, 409);
  }

  let outcome;
  try {
    outcome = await voidOrRefund(account, begun.id, begun.transaction_id, begun.cents, "CAD");
  } catch (e) {
    // Unknown whether Nuvei acted: the payment stays REFUNDING, so a retry can't refund twice;
    // a person checks Nuvei's Cpanel.
    console.error(`[arke-pay-refund] Nuvei call failed for order ${orderId}:`, e);
    return json({ error: "gateway_error" }, 502);
  }
  if (outcome.kind === "failed") {
    console.warn(`[arke-pay-refund] order ${orderId} not refunded: ${outcome.reason}`);
    await abort(outcome.reason);
    return json({ error: "declined", reason: outcome.reason }, 409);
  }

  const { error: doneError } = await supabase.rpc("online_refund_done", {
    p_id: begun.id, p_status: outcome.kind, p_refund_tx_id: outcome.transactionId,
  });
  if (doneError) {
    // The money went back but isn't recorded: loud, for a person to fix (Nuvei Cpanel has it).
    console.error(`[arke-pay-refund] order ${orderId} ${outcome.kind} at Nuvei (${outcome.transactionId}) but not recorded:`, doneError);
  }
  return json({ status: outcome.kind, refund_transaction_id: outcome.transactionId });
});
