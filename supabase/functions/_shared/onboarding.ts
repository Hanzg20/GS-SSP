import { nuveiOnboarding } from "./gateways/nuvei-onboarding.ts";
import { elavonOnboarding } from "./gateways/elavon-onboarding.ts";

// Onboarding-agnostic contract for merchant KYC/application submission --
// separate from gateway.ts's PaymentGateway interface, which is about
// scan-to-pay collection, not underwriting. A gateway adapter (stripe.ts)
// and an onboarding adapter (nuvei-onboarding.ts) for the same acquirer are
// two different files/interfaces on purpose: an acquirer that only does
// onboarding today (Elavon/Nuvei right now) shouldn't need a fake
// createPaymentIntent() stub just to satisfy PaymentGateway.

export type Acquirer = "elavon" | "nuvei";

export interface MerchantApplicationData {
  orgId: string;
  legalName: string;
  // Remaining fields (tax id, bank account, contact info, etc.) intentionally
  // left open until the real Nuvei/Elavon onboarding API docs are in hand --
  // see the plan's open items. Adding a strict field list now would just be
  // guessed shape that has to be reworked once the real docs arrive.
  [key: string]: unknown;
}

export type OnboardingStatus =
  | "SUBMITTED"
  | "PENDING_REVIEW"
  | "NEEDS_INFO"
  | "APPROVED"
  | "REJECTED";

export interface SubmitApplicationResult {
  externalRef: string;
  status: OnboardingStatus;
  raw: unknown;
}

export interface ApplicationStatusResult {
  status: OnboardingStatus;
  raw: unknown;
}

export interface AcquirerOnboarding {
  readonly acquirer: Acquirer;
  submitMerchantApplication(data: MerchantApplicationData): Promise<SubmitApplicationResult>;
  getApplicationStatus(externalRef: string): Promise<ApplicationStatusResult>;
}

export function getOnboardingAdapter(acquirer: Acquirer): AcquirerOnboarding {
  switch (acquirer) {
    case "nuvei":
      return nuveiOnboarding;
    case "elavon":
      return elavonOnboarding;
    default: {
      // Exhaustiveness check -- if a third acquirer is ever added to the
      // Acquirer union without a case here, this is a compile error, not a
      // silent runtime fallthrough.
      const _exhaustive: never = acquirer;
      throw new Error(`getOnboardingAdapter: unknown acquirer ${_exhaustive}`);
    }
  }
}
