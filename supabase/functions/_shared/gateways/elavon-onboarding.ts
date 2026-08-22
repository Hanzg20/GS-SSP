import type {
  AcquirerOnboarding,
  MerchantApplicationData,
  SubmitApplicationResult,
  ApplicationStatusResult,
} from "../onboarding.ts";

// Placeholder adapter -- Elavon's onboarding API docs have not been obtained
// yet (unlike Nuvei, where the doc exists but hasn't been shared into this
// session). Nothing about Elavon's request/response shape, auth scheme, or
// sync-vs-webhook status reporting is known, so nothing below is guessed.
// Fill in once the doc arrives; mirror nuvei-onboarding.ts's eventual real
// implementation for structure once that one is written.

export const elavonOnboarding: AcquirerOnboarding = {
  acquirer: "elavon",

  async submitMerchantApplication(_data: MerchantApplicationData): Promise<SubmitApplicationResult> {
    throw new Error(
      "elavonOnboarding.submitMerchantApplication: not implemented -- Elavon onboarding API doc not yet obtained (see plan open item #2)",
    );
  },

  async getApplicationStatus(_externalRef: string): Promise<ApplicationStatusResult> {
    throw new Error(
      "elavonOnboarding.getApplicationStatus: not implemented -- Elavon onboarding API doc not yet obtained (see plan open item #2)",
    );
  },
};
