import type {
  AcquirerOnboarding,
  MerchantApplicationData,
  SubmitApplicationResult,
  ApplicationStatusResult,
  OnboardingStatus,
} from "../onboarding.ts";

// Real implementation against Nuvei's "AppLink Web API" (Canada schema), per
// https://docs.nuvei.com/documentation/partner-tools-docs/partner-onboarding/applink-web-api-canada-schema/
// read 2026-08-21. Country is hardcoded to "CA" throughout (GS-SSP is a
// Canadian company) -- Nuvei's USA schema is a parallel, differently-shaped
// API (/Application/US/...) not implemented here.
//
// CONFIRMED from the docs:
// - JSON over plain HTTP REST, HTTP status codes signal errors.
// - Auth: HTTP Basic. Sandbox has a documented shared test credential,
//   "test" / "Testing123$" -- used below ONLY as the default when
//   NUVEI_APPLINK_USERNAME/PASSWORD secrets are unset, same "safe default
//   until configured" pattern as PAYMENT_GATEWAY defaulting to the stub
//   gateway in ../gateway.ts. Production credentials must come from Nuvei's
//   Relationship Manager -- never hardcode real ones here.
//
// BASE URL IS NOT ACTUALLY CONFIRMED -- IMPORTANT CORRECTION (2026-08-21):
// the docs page's tool-summarized text said "https://api.sandbox.nuvei.com/applink",
// but that hostname does not exist -- `nslookup api.sandbox.nuvei.com`
// returns NXDOMAIN. This was very likely a fabrication introduced by the
// page-summarization pass (a plausible-sounding blend of real fragments),
// not something actually printed on the page -- a caution worth remembering
// generally: don't trust a fetched-and-summarized doc's exact
// URLs/hostnames as verbatim without a second check when they're about to
// go into real integration code. Manual DNS probing found `api.nuvei.com`
// and `applink.nuvei.com` DO resolve and both return real (non-generic-404)
// responses for /Application/CA -- api.nuvei.com/Application/CA gives 401
// (endpoint exists, these sandbox creds rejected there), applink.nuvei.com/Application/CA
// gives 405 with an `Allow: POST` header (route exists) but the same POST
// still came back 405 -- inconsistent enough that neither should be trusted
// as *the* real sandbox endpoint without going through Nuvei's actual
// "Innovation Center Sandbox Tools" portal (referenced in the Quick Start
// Guide) to get the real base URL. NUVEI_APPLINK_BASE_URL has NO default
// below on purpose -- unset it fails loudly (see nuveiRequest below) rather
// than silently calling a guessed, possibly-wrong host.
// - Flow: POST /Application/CA (create, body = the full application
//   payload) -> returns { ApplicationId }, then POST
//   /Application/CA/{id}/Submit (synchronous -- docs explicitly say to use
//   this, NOT /SubmitAsync, which is marked "DO NOT USE").
// - Status values seen in the docs' GET /Application/CA/List `status` filter:
//   New, OutstandingElecSign, CompleteElecSign, Submitted, Canceled,
//   PendingMerchantReviewLink, MerchantCompletedReviewLink,
//   OutstandingUnderwriting. Notably NO literal "Approved"/"Rejected" value
//   appeared in what was fetched -- mapNuveiStatus()'s guess at where
//   approval/rejection actually land is UNVERIFIED, flag before trusting it
//   in production.
//
// NOT CONFIRMED / genuinely unknown, do not guess further without the real
// doc pages or a Relationship Manager conversation:
// - The actual JSON payload field schema for the Merchant Application itself
//   (legal name, address, banking, owners, etc). Nuvei's own docs say this
//   "is unique to the type of Merchant Applications you can process" and is
//   provided by "your Integration Specialist or Relationship Manager", not
//   published generically -- submitMerchantApplication() below deliberately
//   passes `data` through close to as-is (minus our own bookkeeping fields)
//   rather than inventing field names that would silently 400 or silently
//   submit wrong data.
// - Whether Nuvei pushes an async webhook on approval at all, or whether
//   polling (GET /Application/CA/{id} or /Application/CA/List) is the only
//   mechanism -- everything actually documented here is poll-shaped, so
//   getApplicationStatus() below is the primary path.
//   ../../nuvei-onboarding-webhook/index.ts was written speculatively before
//   this doc pass and may not correspond to anything Nuvei actually sends;
//   treat it as unverified, not as confirmation a webhook exists.
// - Whether GET /Application/CA/{id}'s response actually includes a `Status`
//   field (assumed below) -- the fetched docs only confirmed this endpoint
//   "returns the Merchant Application data payload", not its exact shape.

// No hardcoded default -- see the base-URL correction above. Get the real
// value from Nuvei's Innovation Center Sandbox Tools portal (or Relationship
// Manager for production) and `supabase secrets set NUVEI_APPLINK_BASE_URL=...`.
const BASE_URL = Deno.env.get("NUVEI_APPLINK_BASE_URL");
const USERNAME = Deno.env.get("NUVEI_APPLINK_USERNAME") ?? "test";
const PASSWORD = Deno.env.get("NUVEI_APPLINK_PASSWORD") ?? "Testing123$";

function authHeader(): string {
  return `Basic ${btoa(`${USERNAME}:${PASSWORD}`)}`;
}

async function nuveiRequest(path: string, init: RequestInit = {}): Promise<unknown> {
  if (!BASE_URL) {
    throw new Error(
      "NUVEI_APPLINK_BASE_URL is not set -- the real AppLink base URL was never confirmed (see header comment), fails loudly rather than guessing a host",
    );
  }

  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: authHeader(),
      ...init.headers,
    },
  });

  if (!res.ok) {
    // Nuvei uses HTTP status codes for errors, per the docs -- surface the
    // body (usually validation error detail) rather than just the status.
    const body = await res.text();
    throw new Error(`Nuvei AppLink ${init.method ?? "GET"} ${path} failed: ${res.status} ${body}`);
  }
  if (res.status === 204) return null;
  return res.json();
}

function mapNuveiStatus(raw: string | undefined): OnboardingStatus {
  switch (raw) {
    case "New":
    case "OutstandingElecSign":
    case "CompleteElecSign":
      return "SUBMITTED";
    case "Submitted":
    case "OutstandingUnderwriting":
    case "PendingMerchantReviewLink":
    case "MerchantCompletedReviewLink":
      return "PENDING_REVIEW";
    case "Canceled":
      return "REJECTED";
    default:
      // Unknown/unseen status string (including a possible real "Approved")
      // -- don't claim APPROVED/REJECTED without having actually observed
      // the real value first. Log so a real sandbox run surfaces this.
      console.warn(`[nuvei-onboarding] unmapped status "${raw}", defaulting to PENDING_REVIEW`);
      return "PENDING_REVIEW";
  }
}

export const nuveiOnboarding: AcquirerOnboarding = {
  acquirer: "nuvei",

  async submitMerchantApplication(data: MerchantApplicationData): Promise<SubmitApplicationResult> {
    // Strip our own bookkeeping fields before sending -- orgId/acquirer are
    // ours, not part of Nuvei's payload schema (whatever that turns out to
    // be exactly).
    const { orgId: _orgId, ...applicationPayload } = data;

    const created = await nuveiRequest("/Application/CA", {
      method: "POST",
      body: JSON.stringify(applicationPayload),
    }) as { ApplicationId?: string };

    const applicationId = created?.ApplicationId;
    if (!applicationId) {
      throw new Error(`Nuvei AppLink create returned no ApplicationId: ${JSON.stringify(created)}`);
    }

    // Synchronous submit, per docs -- NOT /SubmitAsync (marked "DO NOT USE").
    const submitResult = await nuveiRequest(`/Application/CA/${applicationId}/Submit`, {
      method: "POST",
    });

    return {
      externalRef: applicationId,
      status: "SUBMITTED",
      raw: { created, submitResult },
    };
  },

  async getApplicationStatus(externalRef: string): Promise<ApplicationStatusResult> {
    const result = await nuveiRequest(`/Application/CA/${externalRef}`) as { Status?: string } | null;
    return {
      status: mapNuveiStatus(result?.Status),
      raw: result,
    };
  },
};
