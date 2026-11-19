// Arke sweep: finishes what online.sweep_kick (pg_cron, every minute) found due --
//   1. gives back the money of rejected orders still holding it (auto-rejected because nobody accepted them in time,
//      or a desk's reject whose own refund never finished), through the same steps as the desk's refund;
//   2. mails each such customer once their money is back.
// docs/migrations/2026-11-19_arke_accept_deadline.sql.
//
// verify_jwt off and no secret on purpose: it only acts on what the database says is due (each refund at most 5
// tries, each email once), so anyone calling it can at most make that happen a few seconds early.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { corsHeaders } from "../_shared/cors.ts";
import { refundRejectedOrder } from "../_shared/arke-refund.ts";
import { mailRefundedOrder, type RefundMail } from "../_shared/arke-mail.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);

  const refunds: Record<string, string> = {};
  const { data: due, error } = await supabase.rpc("online_sweep_refunds");
  if (error) {
    console.error("[arke-sweep] refunds claim failed:", error);
    return json({ error: "failed" }, 500);
  }
  for (const d of (due ?? []) as { order_id: string; org_id: string }[]) {
    const r = await refundRejectedOrder(supabase, d.org_id, d.order_id, "arke-sweep");
    refunds[d.order_id] = r.ok ? r.status : `${r.error}${r.reason ? `: ${r.reason}` : ""}`;
  }

  const mails: Record<string, string> = {};
  const { data: toMail, error: mailError } = await supabase.rpc("online_sweep_mails");
  if (mailError) console.error("[arke-sweep] mails claim failed:", mailError);
  for (const m of (toMail ?? []) as (RefundMail & { order_id: string })[]) {
    const status = await mailRefundedOrder(m);
    mails[m.order_id] = status;
    const { error: e } = await supabase.rpc("online_sweep_mail_result", { p_order: m.order_id, p_status: status });
    if (e) console.error(`[arke-sweep] could not record mail result for ${m.order_id}: ${e.message}`);
  }

  if (Object.keys(refunds).length || Object.keys(mails).length) console.log("[arke-sweep]", JSON.stringify({ refunds, mails }));
  return json({ refunds, mails });
});
