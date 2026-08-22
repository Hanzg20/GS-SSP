import type {
  AcquirerOnboarding,
  MerchantApplicationData,
  SubmitApplicationResult,
  ApplicationStatusResult,
} from "../onboarding.ts";

// Placeholder adapter -- the user has Nuvei's onboarding API docs but they
// have not been shared into this session yet, so the actual request/response
// shape below is deliberately NOT guessed. Read once the doc is available:
// endpoint URLs, auth scheme (API key vs signed request), the real
// application payload fields, and how status is reported back (sync
// response vs async webhook, see ../../nuvei-onboarding-webhook/index.ts).
//
// What IS already confirmed from real code elsewhere in this repo family
// (gs-ssp-cmp's TerminalConfigModal.tsx / wizarposService.ts,
// pushNuveiParams): once a Nuvei merchant application is approved, the
// resulting identifiers are merchantId / merchantSiteId / terminalId /
// sharedSecret, and Nuvei distinguishes an "int" (sandbox) vs "prod"
// environment -- see merchant_acquirer_accounts.env. Wire a NUVEI_API_KEY
// (or whatever the real auth scheme turns out to be) via `supabase secrets
// set`, and read it here with Deno.env.get(...), once known.

export const nuveiOnboarding: AcquirerOnboarding = {
  acquirer: "nuvei",

  async submitMerchantApplication(_data: MerchantApplicationData): Promise<SubmitApplicationResult> {
    throw new Error(
      "nuveiOnboarding.submitMerchantApplication: not implemented -- pending Nuvei onboarding API doc (see plan open item #1)",
    );
  },

  async getApplicationStatus(_externalRef: string): Promise<ApplicationStatusResult> {
    throw new Error(
      "nuveiOnboarding.getApplicationStatus: not implemented -- pending Nuvei onboarding API doc (see plan open item #1)",
    );
  },
};
