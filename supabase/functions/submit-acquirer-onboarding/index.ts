// Dispatches merchant onboarding/KYC actions to the acquirer adapter for
// the requested acquirer, and keeps merchant_acquirer_accounts in sync.
// Called from gs-ssp-cmp's acquirerService.ts (Organization Management ->
// Acquirers tab). Two actions, matching the real Create -> Document ->
// Submit flow (document upload is a separate function, see
// ../upload-acquirer-document/index.ts, since it needs multipart handling):
//   - "create": first step, creates the application at the acquirer and
//     records its externalRef. Row status stays DRAFT -- the application
//     exists at the acquirer but hasn't been sent to underwriting yet.
//   - "submit": final step, sends the already-created application (plus
//     whatever documents were uploaded in between) to underwriting.
//     Requires a prior "create" to have populated external_merchant_id.
//
// verify_jwt stays ON for this function (default) -- unlike payment-webhook,
// the caller here is an authenticated CMP admin, not an external gateway, so
// there IS a Supabase JWT to check. RLS on merchant_acquirer_accounts still
// applies to every read/write below since this uses the caller's own JWT
// via the Authorization header, not a service-role client -- an org member
// without sys-admin/org-admin rights gets rejected by "Sys admins can
// manage acquirer accounts" the same way a direct table write would be.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getOnboardingAdapter, type Acquirer, type MerchantApplicationData } from "../_shared/onboarding.ts";

interface CreateRequestBody {
  action: "create";
  org_id: string;
  acquirer: Acquirer;
  applicationData: Record<string, unknown>;
}

interface SubmitRequestBody {
  action: "submit";
  org_id: string;
  acquirer: Acquirer;
}

type RequestBody = CreateRequestBody | SubmitRequestBody;

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

  const { action, org_id, acquirer } = body;
  if (!org_id || !acquirer) {
    return new Response(JSON.stringify({ error: "missing_fields" }), { status: 400 });
  }
  if (acquirer !== "elavon" && acquirer !== "nuvei") {
    return new Response(JSON.stringify({ error: "unknown_acquirer" }), { status: 400 });
  }
  if (action !== "create" && action !== "submit") {
    return new Response(JSON.stringify({ error: "unknown_action" }), { status: 400 });
  }

  const authHeader = req.headers.get("Authorization");
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader ?? "" } } },
  );

  const adapter = getOnboardingAdapter(acquirer);

  if (action === "create") {
    if (!body.applicationData) {
      return new Response(JSON.stringify({ error: "missing_fields" }), { status: 400 });
    }
    const applicationPayload: MerchantApplicationData = { orgId: org_id, ...body.applicationData } as MerchantApplicationData;

    try {
      const result = await adapter.createApplication(applicationPayload);

      // Upsert AFTER a successful create (not before, unlike the old
      // combined flow) -- there's no DRAFT-before-attempt row worth
      // recording since "create" IS the first attempt; a thrown error
      // here means no application exists at the acquirer at all yet.
      const { error } = await supabase
        .from("merchant_acquirer_accounts")
        .upsert(
          {
            org_id,
            acquirer,
            external_merchant_id: result.externalRef,
            status: "DRAFT",
            raw_response: result.raw,
          },
          { onConflict: "org_id,acquirer" },
        );
      if (error) {
        console.error("[submit-acquirer-onboarding] create upsert failed:", error);
        return new Response(JSON.stringify({ error: "db_error", detail: error.message }), { status: 500 });
      }

      return new Response(JSON.stringify({ externalRef: result.externalRef }), { status: 200 });
    } catch (e) {
      console.error(`[submit-acquirer-onboarding] ${acquirer} createApplication threw:`, e);
      return new Response(
        JSON.stringify({ error: "adapter_error", detail: e instanceof Error ? e.message : String(e) }),
        { status: 502 },
      );
    }
  }

  // action === "submit" -- reads through the _safe view, not the base
  // table: SELECT on merchant_acquirer_accounts itself is REVOKEd from
  // `authenticated` entirely (see docs/supabase_full_schema.sql's
  // shared_secret shielding), and this function runs as the caller's own
  // JWT, not service_role, so it's subject to that same restriction.
  const { data: account, error: fetchError } = await supabase
    .from("merchant_acquirer_accounts_safe")
    .select("external_merchant_id")
    .eq("org_id", org_id)
    .eq("acquirer", acquirer)
    .maybeSingle();

  if (fetchError) {
    console.error("[submit-acquirer-onboarding] account lookup failed:", fetchError);
    return new Response(JSON.stringify({ error: "db_error", detail: fetchError.message }), { status: 500 });
  }
  const externalRef = account?.external_merchant_id;
  if (!externalRef) {
    return new Response(JSON.stringify({ error: "not_created_yet" }), { status: 409 });
  }

  try {
    const result = await adapter.submitApplication(externalRef);

    const { error } = await supabase
      .from("merchant_acquirer_accounts")
      .update({ status: result.status, submitted_at: new Date().toISOString(), raw_response: result.raw })
      .eq("org_id", org_id)
      .eq("acquirer", acquirer);

    if (error) {
      console.error("[submit-acquirer-onboarding] submit update failed:", error);
      return new Response(JSON.stringify({ error: "db_error", detail: error.message }), { status: 500 });
    }

    return new Response(JSON.stringify({ status: result.status }), { status: 200 });
  } catch (e) {
    console.error(`[submit-acquirer-onboarding] ${acquirer} submitApplication threw:`, e);
    return new Response(
      JSON.stringify({ error: "adapter_error", detail: e instanceof Error ? e.message : String(e) }),
      { status: 502 },
    );
  }
});
