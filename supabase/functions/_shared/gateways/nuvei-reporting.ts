import type { AcquirerReporting, DateRange } from "../reporting.ts";

// Real implementation against Nuvei's "Merchant Dashboard API" (reporting),
// per https://docs.nuvei.com/documentation/partner-tools-docs/reporting/
// read 2026-08-22. Same caution as ../onboarding.ts's Nuvei adapter about
// this doc site's summarized text fabricating hostnames -- repeated here
// for a second, independent case:
//
// - The docs claimed a sandbox base URL "https://api.sandbox.nuvei.com/...".
//   Manually verified via `nslookup`: this hostname does NOT exist
//   (NXDOMAIN), identical failure mode to the AppLink onboarding doc's
//   fabricated `api.sandbox.nuvei.com`. `api.nuvei.com` (the same real host
//   AppLink uses) DOES resolve, and `curl`ing
//   `https://api.nuvei.com/merchant/api/Transactions/GetCreditTransactions?...`
//   returned HTTP 500 (route exists, just unauthenticated/malformed) rather
//   than a connection failure or 404 -- confirms this is the real host.
//   BASE_URL below has no default for the same "fail loudly, don't guess a
//   host" reason as the onboarding adapter.
// - Auth: HTTP Basic, per the docs. The generic example credential shown
//   (`apiuser` / `!@Mopicyn`) came from Nuvei's PUBLIC docs page itself (not
//   from Danny's GoldSky-specific Postman collection, unlike AppLink's
//   `goldskytechnologiessandboxca`), so its provenance is weaker -- treated
//   the same way AppLink's public "test"/"Testing123$" shared credential
//   was: a safe-until-configured DEFAULT, not confirmed to be GoldSky's own,
//   override via secrets once real credentials are known.
// - Response amounts are decimal dollars in Nuvei's own example
//   (`"Amount": 0.99`), NOT cents -- convert to cents when writing into
//   acquirer_settlement_records.amount_cents (Math.round(amount * 100)),
//   don't store the raw decimal into an integer-cents column.
//
// NOT YET DONE / genuinely unverified:
// - No real credentials have ever been used against this API -- every
//   method below is unexercised. Field names in each response are taken
//   verbatim from Nuvei's own documented examples, not guessed, but
//   whether pagination, rate limits, or additional required params exist
//   beyond startDate/endDate (+ dateType for ACH) is unconfirmed.
// - No sync/cron wiring exists yet -- see ../../sync-acquirer-settlements/index.ts,
//   which calls these methods but is not scheduled anywhere.
// - external_record_id extraction (which field is the natural per-row ID)
//   is a judgment call made here per record type based on which field
//   looks unique in Nuvei's examples (TransactionNumber, CaseNumber,
//   RefNumber, BatchID, AchLid) -- not confirmed as the actual unique key
//   Nuvei intends; may need adjusting once real duplicate/idempotency
//   behavior is observed.

const BASE_URL = Deno.env.get("NUVEI_REPORTING_BASE_URL");
const USERNAME = Deno.env.get("NUVEI_REPORTING_USERNAME") ?? "apiuser";
const PASSWORD = Deno.env.get("NUVEI_REPORTING_PASSWORD") ?? "!@Mopicyn";

async function nuveiReportingRequest(path: string, params: Record<string, string>): Promise<unknown[]> {
  if (!BASE_URL) {
    throw new Error(
      "NUVEI_REPORTING_BASE_URL is not set -- the real base URL was confirmed as https://api.nuvei.com/merchant/api/Transactions via manual curl/DNS check (see header comment), but is deliberately not hardcoded as a default; set it explicitly via `supabase secrets set`",
    );
  }

  const query = new URLSearchParams(params).toString();
  const res = await fetch(`${BASE_URL}${path}?${query}`, {
    headers: { Authorization: `Basic ${btoa(`${USERNAME}:${PASSWORD}`)}` },
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Nuvei Merchant Dashboard API GET ${path} failed: ${res.status} ${body}`);
  }
  const data = await res.json();
  return Array.isArray(data) ? data : [data];
}

function dateParams(range: DateRange): Record<string, string> {
  return { startDate: range.startDate, endDate: range.endDate };
}

export const nuveiReporting: AcquirerReporting = {
  acquirer: "nuvei",

  getCreditTransactions(range) {
    return nuveiReportingRequest("/GetCreditTransactions", dateParams(range));
  },

  getAuthorizationTransactions(range) {
    return nuveiReportingRequest("/GetAuthorizationTransactions", dateParams(range));
  },

  getAchTransactions(range) {
    // dateType=0 ("Transaction DateTime") per the docs' enum -- the other
    // two (EffectiveDate/DateCode) are real options but Transaction
    // DateTime is the closest match to the other 5 operations' plain
    // startDate/endDate semantics, so it's the default here rather than
    // exposing a third parameter this interface doesn't otherwise have.
    return nuveiReportingRequest("/GetACHTransactions", { ...dateParams(range), dateType: "0" });
  },

  getDisputeTransactions(range) {
    return nuveiReportingRequest("/GetDisputeTransactions", dateParams(range));
  },

  getFundingTransactions(range) {
    return nuveiReportingRequest("/GetFundingTransactions", dateParams(range));
  },

  getBatchTransactions(range) {
    return nuveiReportingRequest("/GetBatchTransactions", dateParams(range));
  },
};
