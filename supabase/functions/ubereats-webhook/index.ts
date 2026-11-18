// Uber Eats webhooks (docs/uber_eats_design.md, step 2): new orders into online.orders for Ourea,
// Uber's cancels as CANCELLED. Called by Uber only -- verify_jwt off; the X-Uber-Signature
// (HMAC-SHA256 of the raw body) is the credential, and an unsigned / wrongly signed call is refused.
//
// Uber retries (10 s, 30 s, 60 s ... up to 7 times) whenever we don't answer 2xx, so:
//  * every event is kept once (online.uber_events, by event_id) and an order once (by Uber's id);
//  * if Uber's order API can't be reached we answer 500, the event stays "fetch_failed", and
//    Uber's retry takes it in -- never a 200 for an order we didn't keep;
//  * everything else answers 200 with an empty body, as Uber asks.
// An event for an Uber store no sys admin linked (Cael) is logged, not taken in.

import { serviceClient, signatureOk, toOurOrder, uberToken, UBER_API } from "../_shared/uber.ts";

const ok = () => new Response(null, { status: 200 });

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("method_not_allowed", { status: 405 });
  const raw = await req.text();
  if (!(await signatureOk(raw, req.headers.get("x-uber-signature")))) {
    console.warn("[ubereats-webhook] bad or missing signature");
    return new Response("unauthorized", { status: 401 });
  }
  // deno-lint-ignore no-explicit-any
  let ev: any;
  try { ev = JSON.parse(raw); } catch { return new Response("bad_json", { status: 400 }); }
  const type = String(ev?.event_type ?? "");
  const eventId = String(ev?.event_id ?? "");
  const orderId = String(ev?.meta?.resource_id ?? "");
  const storeId = String(ev?.meta?.user_id ?? ev?.store_id ?? "").toLowerCase();
  if (!type || !eventId) return new Response("bad_event", { status: 400 });

  const db = serviceClient();
  const { data: inn, error } = await db.rpc("online_uber_event_in", {
    p_event_id: eventId, p_type: type, p_resource_id: orderId || null, p_store_id: storeId || null, p_body: ev,
  });
  if (error) { console.error("[ubereats-webhook] event_in:", error.message); return new Response("db", { status: 500 }); }
  if (!inn?.todo) return ok();              // a retry of something handled, or a store we don't know
  const done = (result: string) => db.rpc("online_uber_event_done", { p_event_id: eventId, p_result: result });

  if (type === "orders.notification" || type === "orders.scheduled.notification") {
    // Uber says where the order is; only ever its own API host (the signature already vouches for the body).
    let href = typeof ev.resource_href === "string" ? ev.resource_href : "";
    if (!href.startsWith(`${UBER_API}/`)) href = `${UBER_API}/v1/eats/order/${encodeURIComponent(orderId)}`;
    const token = await uberToken(db);
    if (!token) { await done("fetch_failed:no_token"); return new Response("no_token", { status: 500 }); }
    let res: Response;
    try {
      res = await fetch(href, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(8000) });
    } catch (e) {
      await done("fetch_failed:network"); console.error("[ubereats-webhook] order fetch:", e);
      return new Response("fetch_failed", { status: 500 });
    }
    const text = await res.text();
    if (!res.ok) {
      await done(`fetch_failed:${res.status}`); console.error(`[ubereats-webhook] order ${orderId}: ${res.status} ${text.slice(0, 300)}`);
      return new Response("fetch_failed", { status: res.status >= 500 || res.status === 401 || res.status === 429 ? 500 : 200 });
    }
    // deno-lint-ignore no-explicit-any
    let body: any = null;
    try { body = JSON.parse(text); } catch { /* below */ }
    const ours = toOurOrder(body);
    if (!ours) { await done("unreadable_order"); console.error(`[ubereats-webhook] order ${orderId} unreadable`); return ok(); }
    const { data: r, error: e2 } = await db.rpc("online_uber_order_in", { p_event_id: eventId, p_order: ours, p_raw: body });
    if (e2) { await done("fetch_failed:db"); console.error("[ubereats-webhook] order_in:", e2.message); return new Response("db", { status: 500 }); }
    console.log(`[ubereats-webhook] order ${ours.display_id ?? orderId}: ${r?.success ? (r.duplicate ? "duplicate" : "taken") : r?.message}`);
    return ok();
  }

  if (type === "orders.cancel" || type === "orders.failure") {
    const reason = ev?.meta?.reason ?? ev?.reason ?? (type === "orders.failure" ? "Uber: order failed" : "Uber: cancelled");
    const { error: e3 } = await db.rpc("online_uber_order_cancel", { p_event_id: eventId, p_order_id: orderId, p_reason: String(reason) });
    if (e3) { console.error("[ubereats-webhook] cancel:", e3.message); return new Response("db", { status: 500 }); }
    return ok();
  }

  await done("ignored");
  return ok();
});
