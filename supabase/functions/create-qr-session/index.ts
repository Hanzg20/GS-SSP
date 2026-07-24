// Called by the IM30 app instead of it INSERTing into qr_payment_sessions
// directly. Owns the one thing the device must never do itself: hold a
// payment gateway's secret key. Everything else (row shape, RLS-scoped
// polling) is unchanged from the client-INSERT version this replaces.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getGateway } from "../_shared/gateway.ts";

interface CreateSessionBody {
  tx_id: string;
  device_sn: string;
  amount_cents: number;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  let body: CreateSessionBody;
  try {
    body = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: "invalid_json" }), { status: 400 });
  }

  const { tx_id, device_sn, amount_cents } = body;
  if (!tx_id || !device_sn || !amount_cents || amount_cents <= 0) {
    return new Response(JSON.stringify({ error: "missing_or_invalid_fields" }), { status: 400 });
  }

  // Edge Functions verify the caller's Supabase JWT before invoking (default
  // verify_jwt = true), so we know this is *some* authenticated device -- but
  // not yet that it's authenticated as `device_sn` specifically. Cross-
  // checking against device_auth_map (same pattern as sync_device_identity)
  // is a reasonable hardening step before production, left out here to keep
  // this skeleton focused on the gateway-agnostic flow.

  const gateway = getGateway();
  let codeUrl: string;
  try {
    const intent = await gateway.createPaymentIntent({
      txId: tx_id,
      amountCents: amount_cents,
      currency: "CAD",
    });
    codeUrl = intent.codeUrl;
  } catch (e) {
    console.error(`[create-qr-session] gateway ${gateway.name} createPaymentIntent failed:`, e);
    return new Response(JSON.stringify({ error: "gateway_error" }), { status: 502 });
  }

  // service_role bypasses RLS -- deliberately, this function is the one
  // trusted writer allowed to create a session on the device's behalf.
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  const { error } = await supabase.from("qr_payment_sessions").insert({
    tx_id,
    device_sn,
    amount_cents,
    status: "PENDING",
  });

  if (error) {
    console.error("[create-qr-session] insert failed:", error);
    return new Response(JSON.stringify({ error: "db_error" }), { status: 500 });
  }

  return new Response(JSON.stringify({ code_url: codeUrl }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
});
