import type {
  AcquirerOnboarding,
  MerchantApplicationData,
  CreateApplicationResult,
  SubmitApplicationResult,
  ApplicationStatusResult,
} from "../onboarding.ts";

// Placeholder adapter -- Elavon's onboarding API docs have not been obtained
// yet (unlike Nuvei, where a real Postman collection + working payload
// example is now in hand -- see nuvei-onboarding.ts). Nothing about
// Elavon's request/response shape, auth scheme, or document-upload/status
// mechanism is known, so nothing below is guessed. Fill in once the doc
// arrives, mirroring nuvei-onboarding.ts's structure once it's real.
//
// uploadDocument is deliberately omitted (not even a throwing stub) --
// AcquirerOnboarding.uploadDocument is optional specifically so an acquirer
// with no confirmed document-upload mechanism doesn't need one.

export const elavonOnboarding: AcquirerOnboarding = {
  acquirer: "elavon",

  async createApplication(_data: MerchantApplicationData): Promise<CreateApplicationResult> {
    throw new Error(
      "elavonOnboarding.createApplication: not implemented -- Elavon onboarding API doc not yet obtained (see plan open item #2)",
    );
  },

  async submitApplication(_externalRef: string): Promise<SubmitApplicationResult> {
    throw new Error(
      "elavonOnboarding.submitApplication: not implemented -- Elavon onboarding API doc not yet obtained (see plan open item #2)",
    );
  },

  async getApplicationStatus(_externalRef: string): Promise<ApplicationStatusResult> {
    throw new Error(
      "elavonOnboarding.getApplicationStatus: not implemented -- Elavon onboarding API doc not yet obtained (see plan open item #2)",
    );
  },
};
