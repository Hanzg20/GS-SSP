// Uber Eats step 3 (docs/uber_eats_design.md): the store moves an Uber order on Ourea / Iris.
// Uber first, then us -- an order is never shown accepted / rejected here unless Uber said yes:
//   ACCEPT  (NEW)                  -> POST /v1/eats/orders/{id}/accept_pos_order
//   REJECT  (NEW)                  -> POST /v1/eats/orders/{id}/deny_pos_order   (Uber cancels and refunds the eater)
//   REJECT  (ACCEPTED / READY)     -> POST /v1/eats/orders/{id}/cancel
//   READY, PICKED_UP, OUT, DELIVERED, BACK -> ours only (the courier / eater follow Uber's own app)
// then online_uber_step_apply (docs/migrations/2026-11-20_uber_eats_actions.sql) -- the same steps as Arke's.
// Uber's money: nothing to refund on our side, ever.
//
// verify_jwt on: a signed-in terminal; its store comes from device_auth_map, never from the body.
// Body {order_id, step, minutes?, reason?, staff_name?, device_sn?} -> {success, message?, order?}
// (the shape of online_store_step, so Ourea reads both the same way).

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { corsHeaders } from "../_shared/cors.ts";
import { UBER_API, uberToken } from "../_shared/uber.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STEPS = new Set(["ACCEPT", "REJECT", "READY", "PICKED_UP", "OUT", "DELIVERED", "BACK"]);

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

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

/** Ourea's reject reasons (RejectDialog, English) as Uber's codes: deny (new order) and cancel (accepted). */
function denyCode(reason: string): string {
  const r = reason.toLowerCase();
  if (r.includes("sold out")) return "ITEM_AVAILABILITY";
  if (r.includes("busy")) return "CAPACITY";
  if (r.includes("closing") || r.includes("closed")) return "STORE_CLOSED";
  return "OTHER";
}
function cancelCode(reason: string): string {
  const r = reason.toLowerCase();
  if (r.includes("sold out")) return "OUT_OF_ITEMS";
  if (r.includes("busy")) return "RESTAURANT_TOO_BUSY";
  if (r.includes("closing") || r.includes("closed")) return "KITCHEN_CLOSED";
  return "OTHER";
}

/** One POST to Uber; the docs show both /v1/eats/orders and /v1/delivery/orders, so a 404 on the first tries the second. */
async function uberPost(token: string, orderId: string, action: string, body: unknown): Promise<{ ok: boolean; http: number; text: string; path: string }> {
  let last = { ok: false, http: 0, text: "", path: "" };
  for (const base of ["/v1/eats/orders", "/v1/delivery/orders"]) {
    const path = `${base}/${encodeURIComponent(orderId)}/${action}`;
    try {
      const r = await fetch(`${UBER_API}${path}`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(10000),
      });
      last = { ok: r.ok, http: r.status, text: (await r.text()).slice(0, 500), path };
    } catch (e) {
      last = { ok: false, http: 0, text: String(e).slice(0, 200), path };
    }
    if (last.http !== 404) break;
  }
  return last;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ success: false, message: "method_not_allowed" }, 405);

  let b: Record<string, unknown>;
  try { b = await req.json(); } catch { return json({ success: false, message: "bad_request" }, 400); }
  const id = typeof b.order_id === "string" ? b.order_id : "";
  const step = typeof b.step === "string" ? b.step : "";
  if (!UUID.test(id) || !STEPS.has(step)) return json({ success: false, message: "bad_request" }, 400);
  const minutes = typeof b.minutes === "number" ? Math.round(b.minutes) : null;
  const reason = typeof b.reason === "string" ? b.reason.trim().slice(0, 200) : "";
  const staff = typeof b.staff_name === "string" ? b.staff_name.slice(0, 40) : null;
  const sn = typeof b.device_sn === "string" ? b.device_sn.slice(0, 64) : null;

  const uid = jwtSub(req.headers.get("Authorization"));
  if (!uid) return json({ success: false, message: "unauthorized" }, 401);
  const db = createClient(SUPABASE_URL, SERVICE_KEY);
  const { data: device } = await db.from("device_auth_map").select("device_sn, org_id")
    .eq("auth_user_id", uid).not("org_id", "is", null).order("created_at", { ascending: false }).limit(1).maybeSingle();
  const org = device?.org_id as string | undefined;
  if (!org) return json({ success: false, message: "not_a_store_terminal" }, 403);

  const { data: o } = await db.rpc("online_uber_step_get", { p_org: org, p_id: id });
  if (!o?.external_id) return json({ success: false, message: "order_not_found" });

  // Uber first, for the steps Uber has to know.
  let call: { action: string; body: unknown } | null = null;
  if (step === "ACCEPT" && o.status === "NEW") call = { action: "accept_pos_order", body: { reason: "accepted" } };
  else if (step === "REJECT" && !reason) return json({ success: false, message: "reason_required" });
  else if (step === "REJECT" && o.status === "NEW") call = { action: "deny_pos_order", body: { reason: { explanation: reason, code: denyCode(reason) } } };
  else if (step === "REJECT" && (o.status === "ACCEPTED" || o.status === "READY")) call = { action: "cancel", body: { reason: cancelCode(reason), details: reason } };

  if (call) {
    const token = await uberToken(db);
    if (!token) return json({ success: false, message: "uber_no_token" });
    const r = await uberPost(token, o.external_id, call.action, call.body);
    console.log(`[ubereats-action] ${step} ${o.code ?? o.external_id} ${r.path}: ${r.http} ${r.text}`);
    if (!r.ok) return json({ success: false, message: `uber_${r.http || "unreachable"}${r.text ? `: ${r.text.slice(0, 160)}` : ""}` });
  }

  const { data: res, error } = await db.rpc("online_uber_step_apply", {
    p_org: org, p_id: id, p_step: step, p_minutes: minutes, p_reason: reason || null, p_staff_name: staff, p_device_sn: sn,
  });
  if (error) {
    console.error(`[ubereats-action] ${step} ${id}: Uber said yes but we could not record it: ${error.message}`);
    return json({ success: false, message: "uber_done_not_recorded" }, 500);
  }
  return json(res);
});
