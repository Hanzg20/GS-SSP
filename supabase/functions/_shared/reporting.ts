// Onboarding-agnostic reporting contract -- separate from onboarding.ts
// (application/KYC) and gateway.ts (scan-to-pay collection). This is about
// pulling an acquirer's own settlement/transaction records for
// reconciliation against GS-SSP's internal `transactions` table, once a
// merchant is actually approved and processing (not yet true for any
// GS-SSP merchant as of 2026-08-22 -- this is scaffolding ahead of that,
// per explicit user direction, not something wired into a live sync yet).

export type Acquirer = "elavon" | "nuvei";

export interface DateRange {
  startDate: string; // YYYY-MM-DD
  endDate: string; // YYYY-MM-DD
}

// One method per real source operation (mirrors Nuvei's own
// GetCreditTransactions/GetAuthorizationTransactions/etc. naming) rather
// than a single generic getRecords(type) -- keeps it obvious which method
// maps to which real API call, and lets an acquirer only implement the
// subset it actually has (all optional).
export interface AcquirerReporting {
  readonly acquirer: Acquirer;
  getCreditTransactions?(range: DateRange): Promise<unknown[]>;
  getAuthorizationTransactions?(range: DateRange): Promise<unknown[]>;
  getAchTransactions?(range: DateRange): Promise<unknown[]>;
  getDisputeTransactions?(range: DateRange): Promise<unknown[]>;
  getFundingTransactions?(range: DateRange): Promise<unknown[]>;
  getBatchTransactions?(range: DateRange): Promise<unknown[]>;
}

export function getReportingAdapter(acquirer: Acquirer): AcquirerReporting | null {
  switch (acquirer) {
    case "nuvei":
      return nuveiReporting;
    case "elavon":
      // No Elavon reporting API doc obtained yet -- null (not a throwing
      // stub) since this interface is entirely optional-method-shaped;
      // callers should treat "no adapter" as "nothing to sync" rather than
      // an error.
      return null;
    default: {
      const _exhaustive: never = acquirer;
      throw new Error(`getReportingAdapter: unknown acquirer ${_exhaustive}`);
    }
  }
}

import { nuveiReporting } from "./gateways/nuvei-reporting.ts";
