// Cael (sys admins): a store's Nuvei online payment account for Ourea Scan pay
// at the table. docs/migrations/2026-10-28_cmp_nuvei_online.sql.
//
// Actions:
//   save    {org_id, merchant_id, site_id, secret?, env}  -- tests the credentials
//           with Nuvei (openOrder: an order is opened, nothing is charged), then
//           saves them and turns online payment on (ACTIVE). A failed test saves
//           nothing. secret blank = keep the one already saved.
//   disable {org_id}  -- online payment off (ACTIVE -> APPROVED); credentials kept.
// The secret is never returned.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { corsHeaders } from "../_shared/cors.ts";
import { type NuveiAccount, openOrder } from "../_shared/nuvei-checkout.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

const clean = (v: unknown, max: number) => (typeof v === "string" ? v.trim().slice(0, max) : "");

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  // Sys admins only: asked as the caller, not as service_role.
  const asCaller = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } } });
  const { data: isAdmin } = await asCaller.rpc("is_sys_admin");
  if (isAdmin !== true) return json({ error: "forbidden" }, 403);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad_request" }, 400);
  }
  const orgId = clean(body.org_id, 64);
  if (!orgId) return json({ error: "bad_request" }, 400);

  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
  const { data: existing, error: readError } = await supabase
    .from("merchant_acquirer_accounts")
    .select("id, status, shared_secret")
    .eq("org_id", orgId)
    .eq("acquirer", "nuvei")
    .maybeSingle();
  if (readError) {
    console.error("[nuvei-online-setup] read failed:", readError);
    return json({ error: "db_error" }, 500);
  }

  if (body.action === "disable") {
    if (existing?.status === "ACTIVE") {
      const { error } = await supabase.from("merchant_acquirer_accounts").update({ status: "APPROVED" }).eq("id", existing.id);
      if (error) return json({ error: "db_error" }, 500);
    }
    return json({ ok: true, status: existing?.status === "ACTIVE" ? "APPROVED" : existing?.status ?? null });
  }
  if (body.action !== "save") return json({ error: "bad_request" }, 400);

  const merchantId = clean(body.merchant_id, 40);
  const siteId = clean(body.site_id, 40);
  const env = body.env === "prod" ? "prod" : "int";
  const secret = clean(body.secret, 200) || existing?.shared_secret || "";
  if (!/^\d+$/.test(merchantId) || !/^\d+$/.test(siteId)) return json({ error: "bad_ids" }, 400);
  if (!secret) return json({ error: "secret_required" }, 400);

  // A $1.00 order that is never paid: Nuvei only accepts it with the right ids and secret.
  const account: NuveiAccount = { id: existing?.id ?? "", merchantId, merchantSiteId: siteId, secret, env };
  try {
    await openOrder(account, `setup-${crypto.randomUUID()}`, 100, "setup@goldsky.invalid", `${SUPABASE_URL}/functions/v1/scan-pay-dmn`);
  } catch (e) {
    console.warn(`[nuvei-online-setup] test failed for org ${orgId}: ${e}`);
    return json({ error: "nuvei_refused", reason: String(e).replace(/^Error: /, "") }, 422);
  }

  const row = {
    org_id: orgId, acquirer: "nuvei", env, status: "ACTIVE",
    external_merchant_id: merchantId, merchant_site_id: siteId, shared_secret: secret,
  };
  const { error } = existing
    ? await supabase.from("merchant_acquirer_accounts").update(row).eq("id", existing.id)
    : await supabase.from("merchant_acquirer_accounts").insert({ ...row, approved_at: new Date().toISOString() });
  if (error) {
    console.error("[nuvei-online-setup] save failed:", error);
    return json({ error: "db_error" }, 500);
  }
  console.log(`[nuvei-online-setup] org ${orgId}: Nuvei online payment ACTIVE (${env}, site ${siteId})`);
  return json({ ok: true, status: "ACTIVE", env });
});
