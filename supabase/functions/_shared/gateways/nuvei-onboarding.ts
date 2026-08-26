import type {
  AcquirerOnboarding,
  MerchantApplicationData,
  CreateApplicationResult,
  SubmitApplicationResult,
  ApplicationStatusResult,
  DocumentFile,
  OnboardingStatus,
} from "../onboarding.ts";

// Real implementation against Nuvei's "AppLink Web API" (Canada schema).
// Ground truth is now the real Postman collection Danny Meeker (Nuvei
// Partner Solutions Engineer) sent 2026-08-21 --
// `C:\goldsky\合作伙伴资料\NUVEI\Reconciliation and Data Access\AppLink_GoldSky Technologies_CA Copy.postman_collection.json`
// (cross-checked against the sibling US collection in the same folder) --
// which supersedes the doc-site-scrape guesses from earlier the same day.
//
// CONFIRMED, from the actual collection:
// - Base URL: https://api.nuvei.com/applink -- this IS the `api.nuvei.com`
//   host that manual DNS probing earlier flagged as the more plausible
//   candidate over the fabricated `api.sandbox.nuvei.com` from the
//   doc-site summarization pass. There is no separate sandbox subdomain;
//   matches the partner call's "test environment uses marked production
//   accounts" detail.
// - Auth is genuinely DIFFERENT per call, not uniform, in Danny's own
//   working examples:
//   - Create (POST /Application/CA): an `api_key` QUERY PARAM shaped
//     `username:password` -- no Authorization header at all.
//   - Submit (POST /Application/CA/{id}/Submit) and Document upload (POST
//     /Application/CA/{id}/Document): both real HTTP Basic Auth header.
//   Implemented literally as shown, not unified, since that's the only
//   ground truth available.
// - Real GoldSky sandbox username for the Basic-auth calls:
//   "goldskytechnologiessandboxca".
// - The `api_key` value literally embedded in the Create example,
//   `poprocketsandboxca:q47DC1wk2!`, is NOT GoldSky's -- the same request
//   body's `Agent`/`Office` fields are hardcoded to `"PopRocket Sandbox
//   CA"`, a different sandbox entity's copy/paste-from-template leftover
//   in Danny's exported collection (same pattern repeats in the sibling US
//   collection, and again in a `vavastonesandboxUS` credential on its
//   Submit example -- none of these are GoldSky's).
// - CONFIRMED WORKING 2026-08-25: Danny provided GoldSky's real,
//   unredacted CA credentials directly (API Key
//   `goldskytechnologiessandboxca:h69WQ3wj7!`, same string doubles as the
//   Basic Auth username:password for Submit/Document/status calls).
//   Verified with a live test call against this exact code path's request
//   shape: POST /Application/CA returned 200 with a real ApplicationId,
//   and a follow-up GET on that id (Basic Auth) returned
//   `"Agent":"GoldSky Technologies Sandbox CA","Office":"GoldSky
//   Technologies Sandbox CA","User":"goldskytechnologiessandboxca"` --
//   proof this routes to GoldSky's own sandbox account, not PopRocket's.
//   NUVEI_APPLINK_API_KEY/USERNAME/PASSWORD are now set as real Edge
//   Function secrets. Real Agent/Office name is
//   "GoldSky Technologies Sandbox CA" (see NUVEI_GOLDSKY_AGENT/OFFICE in
//   gs-ssp-cmp's nuveiTypes.ts, now filled in to match).
// - A parallel US credential also exists (`goldskytechnologiessandboxus`,
//   API Key `goldskytechnologiessandboxus:p28DP9hf8!`, Agent/Office
//   "GoldSky Technologies Sandbox US") and is stored as
//   NUVEI_APPLINK_API_KEY_US/USERNAME_US/PASSWORD_US, but this file is
//   still CA-only -- nothing here branches on country yet. The US
//   AppLink payload shape is meaningfully different from CA's (simpler
//   BankInformation/SalesProfile, no ElectronicDebitCreditAuthorization/
//   ControlPanel/EquipmentInformationSection/split-by-brand
//   CreditCardSalesProfile), so adding US support means a second payload
//   type + a country-branching path parameter, not just swapping the key.
// - IMPORTANT for any caller building the request URL manually (curl,
//   Postman, etc.): the `api_key` value contains `:` and `!` and MUST be
//   URL-encoded in the query string -- an unencoded `!` got rejected at
//   Akamai's edge with a 500 *before* reaching Nuvei's app (not a Nuvei
//   error). This file's own nuveiRequest() already does
//   `encodeURIComponent(API_KEY)` correctly; this note is for anyone
//   testing outside this code path.
// - Danny also flagged: these credentials run in Nuvei's live
//   infrastructure but are NOT tied to a real merchant account -- test
//   submissions do not trigger their Underwriting Team's review. Treat
//   test data accordingly (obviously-fake legal names etc.), same as the
//   example payload below does.
// - Full AppLink certification needs more than a working submission: a
//   signed PDF contract (matching the submitted application data, signed
//   via one of Nuvei's approved e-sign vendors) and their Integration
//   Team's review. Danny is chasing the blank contract template + the
//   approved e-sign vendor list from Nuvei's own Cdn PartnerRM team as of
//   2026-08-25 -- still outstanding on Nuvei's side, not actionable here.
//
// STILL UNKNOWN / not yet done:
// - Whether every field in the example payload is required vs optional,
//   and whether other Merchant Application "types" need a different shape
//   (the docs say the payload "is unique to the type of Merchant
//   Applications you can process").
// - CONFIRMED 2026-08-25: a full live Create -> Document -> Submit run
//   against the CA sandbox succeeded end to end (through this file's own
//   code, via submit-acquirer-onboarding/upload-acquirer-document, not
//   just a raw API poke) and the application reached Nuvei's "SUBMITTED"
//   state. Also confirmed live: Submit requires a document to already be
//   attached -- calling Submit before Document returns a 400 ("No
//   Supporting Documents attached"), and ContractVersionEtf.
//   MerchantSignDate must be MM/DD/YYYY -- Create silently accepts
//   DD/MM/YYYY but Submit 400s on it (fixed in gs-ssp-cmp's
//   defaultNuveiCaApplication()).
// - mapNuveiStatus()'s `result?.Status` read is LIKELY WRONG: a real GET
//   /Application/CA/{id} response (captured live 2026-08-25) has no
//   top-level `Status` field at all -- its keys are the application's own
//   data (MerchantBusinessInformation, ContractVersionEtf, Agent/Office/
//   User, etc.), not a status envelope. So `getApplicationStatus()`
//   currently always falls through mapNuveiStatus's default case and
//   silently reports PENDING_REVIEW regardless of the application's real
//   state. Confirms the 2026-08-21 partner call's claim that status
//   actually surfaces via Nuvei's Partner Dashboard (human-facing), not
//   this REST endpoint -- getApplicationStatus() needs a different
//   mechanism (a real status field elsewhere in this same response that
//   wasn't recognized, a different endpoint, or a webhook) before
//   sync-acquirer-settlements can trust it. Not guessing at a fix here
//   without seeing a non-DRAFT application's actual response shape.
// - Document upload (uploadDocument below) is now confirmed working
//   end-to-end against the real API (see above) -- the multipart shape
//   mirrored from the Postman example was correct as written.

interface NuveiCaOwner {
  AddressSameAs: string;
  Title: string;
  Guarantor: boolean;
  Email: string;
  PercentOwnership: number;
  FirstName: string;
  LastName: string;
  Birthday: string;
  ResidenceAddressCivicNum: string;
  ResidenceAddressStreet: string;
  City: string;
  State: string;
  Zip: string;
  Telephone: string;
  SocialSecurity: string;
  DriverLicense: string;
  DriverState: string;
}

// Real field shape from Danny's CreateApplication example -- see header
// comment. Optional-ness per field is NOT confirmed (only that this exact
// combination is accepted); treat every field as required until Nuvei's
// validation errors (surfaced via nuveiRequest's error path) prove
// otherwise.
export interface NuveiCaApplicationPayload {
  Agent: string;
  Office: string;
  Language: { Language: string };
  ContractVersionEtf: {
    ContractTerm: string;
    ContractVersion: string;
    MerchantSignDate: string;
  };
  MerchantBusinessInformation: {
    OwnershipType: string;
    LegalName: string;
    CorporateAddressCivicNum: string;
    CorporateAddressStreet: string;
    CorporateAddressUnitDesignator?: string;
    CorporateAddressUnit?: string;
    CorporateCity: string;
    CorporateState: string;
    CorporateZip: string;
    CorporateTelephone: string;
    FederalTaxId: string;
    BusinessEmail: string;
    GoodsType: string;
    BusinessDescription: string;
    BusinessPresence: string;
    BusinessPresenceMonths: string;
    PrefContactSame: boolean;
    PrefContactEmail: string;
    PrefContactPhone: string;
    MailingAttention: string;
    MailingAddress?: string;
    WebAddress: string;
  };
  DbaInformation: {
    SameAsLegal: boolean;
    DbaName: string;
    LocationAddressCivicNum: string;
    LocationAddressStreet: string;
    LocationAddressUnitDesignator?: string;
    LocationAddressUnit?: string;
    LocationCity: string;
    LocationState: string;
    LocationZip: string;
    LocationTelephone: string;
    TimeZone: string;
    GoodsType: string;
    BusinessPresence: string;
    BusinessPresenceMonths: string;
    MailingAttention: string;
    StatementEmail: string;
    MerchantCustomerServiceNumber: string;
    SubjectOfRiskProgram: boolean;
    McRiskProgramDescription?: string | null;
    McRiskProgramDate?: string | null;
    HasPreviousProcessor: string;
    MailingAddress: string;
    FederalRegistryNumber: string;
  };
  OwnersOrOfficers: {
    DbaContactTitle: string;
    OwnerCitizenship: string;
    OwnerCountry: string;
    BusinessStartupDate: string;
    PreviousProcessingIndicator: boolean;
    OwnerList: NuveiCaOwner[];
  };
  PreferredContact: {
    PrefContactSame: boolean;
    MailingAttention: string;
    PrefContactEmail: string;
    PrefContactPhone: string;
  };
  ElectronicDebitCreditAuthorization: {
    SupportSplitBanking: boolean;
    BankAccountHolderName: string;
    BankInstitutionNumber: string;
    BankTransitNumber: string;
    BankAccountNumber: string;
    BankZip: string;
    BankCity: string;
    BankProvince: string;
    OperatingBankAccountHolderName?: string | null;
    OperatingBankInstitutionNumber?: string | null;
    OperatingBankTransitNumber?: string | null;
    OperatingBankAccountNumber?: string | null;
    OperatingBankZip?: string | null;
  };
  ControlPanel: {
    ControlPanelAccess: boolean;
    AdminFirstName: string;
    AdminLastName: string;
    AdminTitle: string;
    AdminEmail: string;
    AdminTelephone: string;
  };
  EquipmentInformationSection: {
    Terminal: boolean;
    Mobile: boolean;
    GatewaySoftware: boolean;
    TerminalList?: unknown;
    MobileEquipmentList?: unknown;
    GatewaySoftwareList?: {
      GatewayName: string;
      ServiceOption: string;
      EndToEndEncryption: boolean;
      Authentication: boolean;
      Tokenization: boolean;
      PeripheralEquipment: string;
      EquipmentType: string;
      MakeModel: string;
      UnitPrice: string;
      NumberOfEquipmentUnits: string;
    };
  };
  SiteSurveyInformation: {
    MerchantsLocaleZoning: string;
    MerchantLocale: string;
    MerchantSquareFootage: string;
    RefundPolicyExists: boolean;
    RefundPolicy: string;
    TimeToCcRefund: number;
    CombinedDeliveryAndAuthorization: boolean;
    AgentSignature: string;
  };
  ScheduleA: {
    BillingOption: string;
    BillingType: string;
    SchedAMerchantType: string;
    OnlineDebit: boolean;
  };
  OtherServiceFees: {
    MonthlyMinimumFee: number;
    ChargebackFee: number;
    RetrievalFee: number;
    OptOutPivotal360Access: boolean;
  };
  PciAndPaymentsApplicationCompliance: {
    CcNumbersStored: boolean;
    ThirdPartyPaymentApplication: boolean;
  };
  CreditCardSalesProfile: {
    VisaMcSalesPercentCardSwipe: number;
    VisaMcSalesPercentMailOrTelephoneOrder: number;
    VisaMcSalesPercentInternet: number;
    VisaAverageMonthlyVolume: number;
    VisaAverageTicketSize: number;
    VisaHighTicketSize: number;
    McAverageMonthlyVolume: number;
    McAverageTicketSize: number;
    McHighTicketSize: number;
    DebitAverageMonthlyVolume: number;
    DebitAverageTicketSize: number;
    DebitHighTicketSize: number;
  };
  CreditCardProcessing: {
    Category: string;
    BillingPlan: string;
  };
  Miscellaneous: {
    ServiceType: string;
  };
}

const BASE_URL = "https://api.nuvei.com/applink";
// Real value confirmed 2026-08-25 (see header comment) and set as an Edge
// Function secret -- no default in source, on purpose: this is a real
// credential, not something to commit even as a fallback.
const API_KEY = Deno.env.get("NUVEI_APPLINK_API_KEY");
// Real username, safe to default (not secret, just an account name).
const USERNAME = Deno.env.get("NUVEI_APPLINK_USERNAME") ?? "goldskytechnologiessandboxca";
// Real value confirmed 2026-08-25, set as an Edge Function secret -- see
// API_KEY comment above for why there's still no source default.
const PASSWORD = Deno.env.get("NUVEI_APPLINK_PASSWORD");

async function nuveiRequest(
  path: string,
  init: RequestInit & { auth: "api_key" | "basic"; skipJsonContentType?: boolean },
): Promise<unknown> {
  const { auth, skipJsonContentType, ...requestInit } = init;
  let url = `${BASE_URL}${path}`;
  // Multipart bodies (document upload) need fetch to set its own
  // Content-Type with the correct boundary -- setting it manually to
  // application/json would break that.
  const headers: Record<string, string> = skipJsonContentType ? {} : { "Content-Type": "application/json" };

  if (auth === "api_key") {
    if (!API_KEY) {
      throw new Error(
        "NUVEI_APPLINK_API_KEY is not set -- the example value in Danny's collection belongs to a different sandbox entity (PopRocket, not GoldSky), fails loudly rather than using it",
      );
    }
    url += `${path.includes("?") ? "&" : "?"}api_key=${encodeURIComponent(API_KEY)}`;
  } else {
    if (!PASSWORD) {
      throw new Error(
        "NUVEI_APPLINK_PASSWORD is not set -- Postman redacts secret variable values on export, so the real password was never available; get it from Danny/the Postman environment directly",
      );
    }
    headers.Authorization = `Basic ${btoa(`${USERNAME}:${PASSWORD}`)}`;
  }

  const res = await fetch(url, { ...requestInit, headers: { ...headers, ...requestInit.headers } });

  if (!res.ok) {
    // Nuvei uses HTTP status codes for errors, per the docs -- surface the
    // body (usually validation error detail) rather than just the status.
    const body = await res.text();
    throw new Error(`Nuvei AppLink ${requestInit.method ?? "GET"} ${path} failed: ${res.status} ${body}`);
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

  async createApplication(data: MerchantApplicationData): Promise<CreateApplicationResult> {
    // orgId is our own bookkeeping field, not part of Nuvei's payload.
    // Everything else must already be NuveiCaApplicationPayload-shaped --
    // the caller (submit-acquirer-onboarding / the CMP onboarding form) is
    // responsible for that, since the fields genuinely differ from
    // Elavon's and there's no generic-enough shape to validate here.
    const { orgId: _orgId, ...applicationPayload } = data;

    const created = await nuveiRequest("/Application/CA", {
      auth: "api_key",
      method: "POST",
      body: JSON.stringify(applicationPayload),
    }) as { ApplicationId?: string };

    const applicationId = created?.ApplicationId;
    if (!applicationId) {
      throw new Error(`Nuvei AppLink create returned no ApplicationId: ${JSON.stringify(created)}`);
    }

    return { externalRef: applicationId, raw: created };
  },

  async uploadDocument(externalRef: string, file: DocumentFile, documentType: string): Promise<void> {
    const formData = new FormData();
    // Uint8Array.buffer is typed as ArrayBufferLike (includes
    // SharedArrayBuffer), which BlobPart doesn't accept -- this data always
    // comes from Uint8Array(await file.arrayBuffer()) (see
    // upload-acquirer-document/index.ts), never a real SharedArrayBuffer.
    formData.append("file", new Blob([file.bytes as BlobPart], { type: file.contentType }), file.filename);

    await nuveiRequest(
      `/Application/CA/${externalRef}/Document?documentType=${encodeURIComponent(documentType)}`,
      { auth: "basic", method: "POST", body: formData, skipJsonContentType: true },
    );
  },

  async submitApplication(externalRef: string): Promise<SubmitApplicationResult> {
    const submitResult = await nuveiRequest(`/Application/CA/${externalRef}/Submit`, {
      auth: "basic",
      method: "POST",
    });

    return { status: "SUBMITTED", raw: submitResult };
  },

  async getApplicationStatus(externalRef: string): Promise<ApplicationStatusResult> {
    const result = await nuveiRequest(`/Application/CA/${externalRef}`, { auth: "basic" }) as { Status?: string } | null;
    return {
      status: mapNuveiStatus(result?.Status),
      raw: result,
    };
  },
};
