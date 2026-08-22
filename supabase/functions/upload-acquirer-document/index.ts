// Uploads one supporting document (multipart) against an already-created
// acquirer application. Separate function from submit-acquirer-onboarding
// specifically because multipart request bodies need different handling
// than the JSON bodies the other two actions there use.
//
// verify_jwt stays ON (default) -- same trust model as
// submit-acquirer-onboarding: caller is an authenticated CMP admin, RLS on
// merchant_acquirer_accounts_safe (read) governs who can look up the
// application to upload against.
//
// Untested against a real acquirer -- see nuvei-onboarding.ts's
// uploadDocument() header note; no working Nuvei credentials exist yet.
//
// CORS: this function is called from a browser (gs-ssp-cmp), unlike this
// repo's other functions -- see ../_shared/cors.ts for why that matters.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getOnboardingAdapter, type Acquirer } from "../_shared/onboarding.ts";
import { handleCorsPreflight, jsonResponse } from "../_shared/cors.ts";

const MAX_FILE_BYTES = 10 * 1024 * 1024; // 10MB, arbitrary sanity cap -- Nuvei's own limit is unconfirmed

Deno.serve(async (req: Request) => {
  const preflight = handleCorsPreflight(req);
  if (preflight) return preflight;

  if (req.method !== "POST") {
    return jsonResponse({ error: "method_not_allowed" }, 405);
  }

  let form: FormData;
  try {
    form = await req.formData();
  } catch {
    return jsonResponse({ error: "invalid_multipart" }, 400);
  }

  const org_id = form.get("org_id");
  const acquirer = form.get("acquirer");
  const documentType = form.get("documentType");
  const file = form.get("file");

  if (
    typeof org_id !== "string" || typeof acquirer !== "string" ||
    typeof documentType !== "string" || !(file instanceof File)
  ) {
    return jsonResponse({ error: "missing_fields" }, 400);
  }
  if (acquirer !== "elavon" && acquirer !== "nuvei") {
    return jsonResponse({ error: "unknown_acquirer" }, 400);
  }
  if (file.size > MAX_FILE_BYTES) {
    return jsonResponse({ error: "file_too_large" }, 413);
  }

  const adapter = getOnboardingAdapter(acquirer as Acquirer);
  if (!adapter.uploadDocument) {
    return jsonResponse({ error: "unsupported_by_acquirer" }, 400);
  }

  const authHeader = req.headers.get("Authorization");
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader ?? "" } } },
  );

  const { data: account, error: fetchError } = await supabase
    .from("merchant_acquirer_accounts_safe")
    .select("external_merchant_id")
    .eq("org_id", org_id)
    .eq("acquirer", acquirer)
    .maybeSingle();

  if (fetchError) {
    console.error("[upload-acquirer-document] account lookup failed:", fetchError);
    return jsonResponse({ error: "db_error", detail: fetchError.message }, 500);
  }
  const externalRef = account?.external_merchant_id;
  if (!externalRef) {
    return jsonResponse({ error: "not_created_yet" }, 409);
  }

  try {
    const bytes = new Uint8Array(await file.arrayBuffer());
    await adapter.uploadDocument(
      externalRef,
      { bytes, filename: file.name, contentType: file.type || "application/octet-stream" },
      documentType,
    );
    return jsonResponse({ ok: true }, 200);
  } catch (e) {
    console.error(`[upload-acquirer-document] ${acquirer} uploadDocument threw:`, e);
    return jsonResponse({ error: "adapter_error", detail: e instanceof Error ? e.message : String(e) }, 502);
  }
});
