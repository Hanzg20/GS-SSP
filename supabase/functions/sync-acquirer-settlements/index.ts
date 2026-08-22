// Pulls settlement/transaction records from an acquirer's reporting API for
// a date range and upserts them into acquirer_settlement_records.
//
// SCAFFOLDING, NOT YET SCHEDULED OR VERIFIED -- built ahead of any GS-SSP
// merchant actually being approved/processing, per explicit user direction
// (2026-08-22) to have the shape ready rather than wait. Nothing here has
// run against real Nuvei data: no working reporting credentials exist, and
// no merchant has a MID yet to test against. Not wired into pg_cron or any
// schedule -- callable manually for now; cadence is a real decision to make
// once this is actually being exercised, not decided here.
//
// Runs as service_role (not the caller's JWT) since a sync pass writes
// records spanning many orgs in one call -- not a per-org-scoped operation
// the caller's own RLS should govern the way submit-acquirer-onboarding's
// per-org writes are. verify_jwt stays ON (default) to control who can
// *trigger* a sync; a scheduled invocation would authenticate as
// service_role itself, which passes that check trivially.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getReportingAdapter, type Acquirer, type DateRange } from "../_shared/reporting.ts";

type RecordType = "credit" | "authorization" | "ach" | "dispute" | "funding" | "batch";

interface NormalizedRecord {
  external_record_id: string;
  external_mid: string | null;
  record_date: string | null;
  amount_cents: number | null;
}

// Field extraction per type is a judgment call from Nuvei's documented
// example responses (see nuvei-reporting.ts header comment) -- not
// confirmed against real data. toCents() assumes decimal-dollar amounts
// matching Nuvei's own example ("Amount": 0.99), not already-cents.
function toCents(amount: unknown): number | null {
  return typeof amount === "number" ? Math.round(amount * 100) : null;
}
function toDate(v: unknown): string | null {
  return typeof v === "string" && v.length >= 10 ? v.slice(0, 10) : null;
}

const NORMALIZERS: Record<RecordType, (r: any) => NormalizedRecord> = {
  credit: (r) => ({
    external_record_id: String(r.TransactionNumber),
    external_mid: r.MID ?? null,
    record_date: toDate(r.TransactionDate),
    amount_cents: toCents(r.Amount),
  }),
  authorization: (r) => ({
    external_record_id: String(r.TransactionNumber),
    external_mid: r.MID ?? null,
    record_date: toDate(r.TransactionDate),
    amount_cents: toCents(r.Amount),
  }),
  ach: (r) => ({
    external_record_id: String(r.AchLid),
    external_mid: r.EntityID ?? null,
    record_date: toDate(r.TransactionDateTime),
    amount_cents: toCents(r.TotalAmount),
  }),
  dispute: (r) => ({
    external_record_id: String(r.CaseNumber),
    external_mid: r.MID ?? null,
    record_date: toDate(r.DisputeDate),
    amount_cents: toCents(r.DisputeAmount),
  }),
  funding: (r) => ({
    external_record_id: String(r.RefNumber),
    external_mid: r.MID ?? null,
    record_date: toDate(r.FundingDate),
    amount_cents: toCents(r.Amount),
  }),
  batch: (r) => ({
    external_record_id: String(r.BatchID),
    external_mid: r.MID ?? null,
    record_date: toDate(r.BatchCloseDate),
    amount_cents: toCents(r.BatchNet),
  }),
};

const ADAPTER_METHODS: Record<RecordType, keyof NonNullable<ReturnType<typeof getReportingAdapter>>> = {
  credit: "getCreditTransactions",
  authorization: "getAuthorizationTransactions",
  ach: "getAchTransactions",
  dispute: "getDisputeTransactions",
  funding: "getFundingTransactions",
  batch: "getBatchTransactions",
};

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "method_not_allowed" }), { status: 405 });
  }

  let body: { acquirer: Acquirer; startDate: string; endDate: string; recordTypes?: RecordType[] };
  try {
    body = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: "invalid_json" }), { status: 400 });
  }

  const { acquirer, startDate, endDate } = body;
  if (!acquirer || !startDate || !endDate) {
    return new Response(JSON.stringify({ error: "missing_fields" }), { status: 400 });
  }

  const adapter = getReportingAdapter(acquirer);
  if (!adapter) {
    return new Response(JSON.stringify({ error: "no_reporting_adapter_for_acquirer" }), { status: 400 });
  }

  const range: DateRange = { startDate, endDate };
  const recordTypes = body.recordTypes ?? (Object.keys(NORMALIZERS) as RecordType[]);

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  const results: Record<string, { fetched: number; upserted: number; error?: string }> = {};

  for (const recordType of recordTypes) {
    const methodName = ADAPTER_METHODS[recordType];
    const method = adapter[methodName] as ((r: DateRange) => Promise<unknown[]>) | undefined;
    if (!method) {
      results[recordType] = { fetched: 0, upserted: 0, error: "not_supported_by_adapter" };
      continue;
    }

    try {
      const rawRecords = await method.call(adapter, range);
      const normalizer = NORMALIZERS[recordType];
      const rows = rawRecords.map((raw) => {
        const normalized = normalizer(raw);
        return {
          acquirer,
          record_type: recordType,
          external_record_id: normalized.external_record_id,
          external_mid: normalized.external_mid,
          record_date: normalized.record_date,
          amount_cents: normalized.amount_cents,
          raw_data: raw,
        };
      });

      if (rows.length > 0) {
        const { error } = await supabase
          .from("acquirer_settlement_records")
          .upsert(rows, { onConflict: "acquirer,record_type,external_record_id" });
        if (error) throw error;
      }

      results[recordType] = { fetched: rawRecords.length, upserted: rows.length };
    } catch (e) {
      console.error(`[sync-acquirer-settlements] ${acquirer}/${recordType} failed:`, e);
      results[recordType] = { fetched: 0, upserted: 0, error: e instanceof Error ? e.message : String(e) };
    }
  }

  return new Response(JSON.stringify({ results }), { status: 200 });
});
