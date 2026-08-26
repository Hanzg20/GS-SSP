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

export interface CreateApplicationResult {
  externalRef: string;
  raw: unknown;
}

export interface SubmitApplicationResult {
  status: OnboardingStatus;
  raw: unknown;
}

export interface ApplicationStatusResult {
  status: OnboardingStatus;
  raw: unknown;
}

export interface ApplicationDetailsResult {
  // Whatever shape the acquirer's own "get me back what was submitted"
  // endpoint returns -- deliberately untyped here (see nuvei-onboarding.ts
  // for the real, confirmed shape for Nuvei). We never store the submitted
  // application data ourselves, only the acquirer's create/submit
  // acknowledgment -- this is the only way to show a user what's actually
  // on file once the form's local React state is gone (modal closed, page
  // reloaded, different session).
  raw: unknown;
}

export interface DocumentFile {
  bytes: Uint8Array;
  filename: string;
  contentType: string;
}

// Split into 3 explicit steps (create / upload document / submit) rather
// than one combined "submitMerchantApplication" call -- Nuvei's real
// documented flow is Create -> Document -> Submit, and Create returns the
// ApplicationId that document uploads need to reference, so they can't be
// collapsed into one atomic call once document upload is a real step.
// uploadDocument is optional since it's confirmed for Nuvei but not (yet)
// for Elavon -- an acquirer without it just doesn't support attaching
// supporting documents through this path yet.
export interface AcquirerOnboarding {
  readonly acquirer: Acquirer;
  createApplication(data: MerchantApplicationData): Promise<CreateApplicationResult>;
  uploadDocument?(externalRef: string, file: DocumentFile, documentType: string): Promise<void>;
  submitApplication(externalRef: string): Promise<SubmitApplicationResult>;
  getApplicationStatus(externalRef: string): Promise<ApplicationStatusResult>;
  // Optional -- not every acquirer's docs are known well enough yet to
  // implement this (Elavon doesn't have it). Callers must check for its
  // presence rather than assume every adapter has it.
  getApplicationDetails?(externalRef: string): Promise<ApplicationDetailsResult>;
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
