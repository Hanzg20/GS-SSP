// Dispatches a merchant onboarding/KYC application to the acquirer adapter
// for the requested acquirer, and upserts the result into
// merchant_acquirer_accounts. Called from gs-ssp-cmp's acquirerService.ts
// (Organization Management -> Acquirers tab).
//
// verify_jwt stays ON for this function (default) -- unlike payment-webhook,
// the caller here is an authenticated CMP admin, not an external gateway, so
// there IS a Supabase JWT to check. RLS on merchant_acquirer_accounts still
// applies to the upsert below since this uses the caller's own JWT via the
// Authorization header, not a service-role client -- an org member without
// sys-admin/org-admin rights gets rejected by "Sys admins can manage
// acquirer accounts" the same way a direct table write would be.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getOnboardingAdapter, type Acquirer, type MerchantApplicationData } from "../_shared/onboarding.ts";

interface RequestBody {
  org_id: string;
  acquirer: Acquirer;
  applicationData: Record<string, unknown>;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  let body: RequestBody;
  try {
    body = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: "invalid_json" }), { status: 400 });
  }

  const { org_id, acquirer, applicationData } = body;
  if (!org_id || !acquirer || !applicationData) {
    return new Response(JSON.stringify({ error: "missing_fields" }), { status: 400 });
  }
  if (acquirer !== "elavon" && acquirer !== "nuvei") {
    return new Response(JSON.stringify({ error: "unknown_acquirer" }), { status: 400 });
  }

  const authHeader = req.headers.get("Authorization");
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader ?? "" } } },
  );

  // DRAFT row first, before calling the acquirer, so a submission that
  // throws (expected right now -- both adapters are still placeholders) at
  // least leaves a record of the attempt rather than silently doing nothing.
  const { error: draftError } = await supabase
    .from("merchant_acquirer_accounts")
    .upsert(
      { org_id, acquirer, status: "SUBMITTED", submitted_at: new Date().toISOString() },
      { onConflict: "org_id,acquirer" },
    );
  if (draftError) {
    console.error("[submit-acquirer-onboarding] draft upsert failed:", draftError);
    return new Response(JSON.stringify({ error: "db_error", detail: draftError.message }), { status: 500 });
  }

  const adapter = getOnboardingAdapter(acquirer);
  const applicationPayload: MerchantApplicationData = { orgId: org_id, ...applicationData } as MerchantApplicationData;

  try {
    const result = await adapter.submitMerchantApplication(applicationPayload);

    const { error: updateError } = await supabase
      .from("merchant_acquirer_accounts")
      .update({
        external_merchant_id: result.externalRef,
        status: result.status,
        raw_response: result.raw,
      })
      .eq("org_id", org_id)
      .eq("acquirer", acquirer);

    if (updateError) {
      console.error("[submit-acquirer-onboarding] result update failed:", updateError);
      return new Response(JSON.stringify({ error: "db_error", detail: updateError.message }), { status: 500 });
    }

    return new Response(JSON.stringify({ status: result.status, externalRef: result.externalRef }), { status: 200 });
  } catch (e) {
    // Expected right now for both acquirers -- see the placeholder adapters'
    // "not implemented" errors. Left as SUBMITTED (not rolled back to
    // DRAFT) so the attempt is visible in the CMP UI rather than
    // disappearing back to an unsubmitted-looking state.
    console.error(`[submit-acquirer-onboarding] ${acquirer} adapter threw:`, e);
    return new Response(
      JSON.stringify({ error: "adapter_error", detail: e instanceof Error ? e.message : String(e) }),
      { status: 502 },
    );
  }
});
