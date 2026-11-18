// Uber Eats Marketplace: can we get an access token? (docs/uber_eats_design.md, step 1)
// Called by us only (verify_jwt on; the service role key), never by a customer or a terminal.
// Answers whether Uber gave a token, its scopes and lifetime -- never the token itself.
// With { probe: "/v1/eats/..." } it also makes one read-only GET with that token and answers Uber's reply.
// A token with both scopes is cached (online.uber_tokens) for ubereats-webhook.

import { freshToken, serviceClient, UBER_API, UBER_ENV, UBER_SCOPE } from "../_shared/uber.ts";

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("method_not_allowed", { status: 405 });
  let scope = UBER_SCOPE;
  let probe: string | null = null;
  try {
    const b = await req.json();
    if (typeof b?.scope === "string" && b.scope.length < 200) scope = b.scope;
    // Read-only GETs on the Eats API only, to find the right paths (design step 1).
    if (typeof b?.probe === "string" && /^\/v[12]\/(eats|delivery)\/[A-Za-z0-9_\-\/?=&.]{0,200}$/.test(b.probe)) probe = b.probe;
  } catch { /* defaults */ }

  const t = await freshToken(serviceClient(), scope);
  if (!t.token) return Response.json({ ok: false, env: UBER_ENV, http: t.http, error: t.error, error_description: t.error_description });
  if (probe) {
    const r = await fetch(`${UBER_API}${probe}`, { headers: { Authorization: `Bearer ${t.token}` } });
    return Response.json({ ok: true, env: UBER_ENV, scope: t.scope, probe, http: r.status, body: (await r.text()).slice(0, 4000) });
  }
  return Response.json({ ok: true, env: UBER_ENV, scope: t.scope, expires_in: t.expires_in, token_type: "Bearer" });
});
