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

/**
 * Edge Functions verify the caller's Supabase JWT signature before invoking
 * (default verify_jwt = true), so we know this is *some* authenticated
 * session -- but not that it's the specific device_sn the request body
 * claims. Without this, any authenticated (anonymous-auth) session could
 * open a paid QR session against an arbitrary device_sn's identity. The
 * platform doesn't hand the decoded claims to the function, so this decodes
 * the JWT payload itself -- no signature re-verification needed, that
 * already happened before this code ran.
 */
function getJwtSub(authHeader: string | null): string | null {
  if (!authHeader?.startsWith("Bearer ")) return null;
  const token = authHeader.slice(7);
  const payloadSegment = token.split(".")[1];
  if (!payloadSegment) return null;
  try {
    const base64 = payloadSegment.replace(/-/g, "+").replace(/_/g, "/");
    const payload = JSON.parse(atob(base64));
    return typeof payload.sub === "string" ? payload.sub : null;
  } catch {
    return null;
  }
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

  const callerUid = getJwtSub(req.headers.get("Authorization"));
  if (!callerUid) {
    return new Response(JSON.stringify({ error: "unauthorized" }), { status: 401 });
  }

  // service_role bypasses RLS -- deliberately, this function is the one
  // trusted writer allowed to create a session on the device's behalf (and
  // needs to read device_auth_map here regardless of the caller's own RLS
  // grants, to do the check right below).
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  // Cross-check the claimed device_sn against the session that actually
  // authenticated this request -- same identity mapping
  // sync_device_identity() populates. Checked before ever calling the
  // gateway, so a spoofed request can't even provoke a real Checkout
  // Session creation.
  const { data: authMapRow, error: authMapError } = await supabase
    .from("device_auth_map")
    .select("device_sn")
    .eq("auth_user_id", callerUid)
    .maybeSingle();

  if (authMapError) {
    console.error("[create-qr-session] device_auth_map lookup failed:", authMapError);
    return new Response(JSON.stringify({ error: "db_error" }), { status: 500 });
  }
  if (!authMapRow || authMapRow.device_sn !== device_sn) {
    console.warn(`[create-qr-session] device_sn mismatch: claimed=${device_sn} authenticated_as=${authMapRow?.device_sn ?? "none"}`);
    return new Response(JSON.stringify({ error: "device_sn_mismatch" }), { status: 403 });
  }

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
