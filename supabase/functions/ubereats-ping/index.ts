// Uber Eats Marketplace: can we get an access token? (docs/uber_eats_design.md, step 1)
// Called by us only (verify_jwt on; the service role key), never by a customer or a terminal.
// Answers whether Uber gave a token, its scopes and lifetime -- never the token itself.
// With { probe: "/v1/eats/..." } it also makes one read-only GET with that token and answers Uber's reply.
//
// Env: UBER_CLIENT_ID, UBER_CLIENT_SECRET (Supabase secrets, set by the owner), and
// UBER_ENV = "sandbox" (default) | "production". The token and API domains must match:
// sandbox-login.uber.com + test-api.uber.com, or auth.uber.com + api.uber.com.

const ENV = (Deno.env.get("UBER_ENV") ?? "sandbox").toLowerCase();
const AUTH = ENV === "production" ? "https://auth.uber.com" : "https://sandbox-login.uber.com";
const API = ENV === "production" ? "https://api.uber.com" : "https://test-api.uber.com";

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("method_not_allowed", { status: 405 });
  const id = Deno.env.get("UBER_CLIENT_ID"), secret = Deno.env.get("UBER_CLIENT_SECRET");
  if (!id || !secret) return Response.json({ ok: false, error: "UBER_CLIENT_ID / UBER_CLIENT_SECRET not set" });
  let scope = "eats.order eats.store";
  let probe: string | null = null;
  try {
    const b = await req.json();
    if (typeof b?.scope === "string" && b.scope.length < 200) scope = b.scope;
    // Read-only GETs on the Eats API only, to find the right paths (design step 1).
    if (typeof b?.probe === "string" && /^\/v[12]\/(eats|delivery)\/[A-Za-z0-9_\-\/?=&.]{0,200}$/.test(b.probe)) probe = b.probe;
  } catch { /* defaults */ }

  const res = await fetch(`${AUTH}/oauth/v2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: id, client_secret: secret, grant_type: "client_credentials", scope }),
  });
  const text = await res.text();
  let data: Record<string, unknown> = {};
  try { data = JSON.parse(text); } catch { /* not JSON */ }
  if (!res.ok || typeof data.access_token !== "string") {
    return Response.json({ ok: false, env: ENV, http: res.status, error: data.error ?? text.slice(0, 300), error_description: data.error_description });
  }
  if (probe) {
    const r = await fetch(`${API}${probe}`, { headers: { Authorization: `Bearer ${data.access_token}` } });
    return Response.json({ ok: true, env: ENV, scope: data.scope, probe, http: r.status, body: (await r.text()).slice(0, 4000) });
  }
  return Response.json({ ok: true, env: ENV, scope: data.scope, expires_in: data.expires_in, token_type: data.token_type });
});
