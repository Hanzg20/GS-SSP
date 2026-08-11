// Receives the gateway's async payment confirmation. The device never talks
// to this function directly -- it only polls qr_payment_sessions.status,
// which this function is the sole writer of (see the "no UPDATE policy for
// authenticated" comment on that table in docs/supabase_full_schema.sql).
//
// verify_jwt must be OFF for this function (see supabase/config.toml) --
// the caller is the payment gateway, not a signed-in device, so there is no
// Supabase JWT to check. Authenticity comes entirely from
// gateway.verifyWebhook()'s signature check below; treat that as the only
// thing standing between this endpoint and anyone on the internet marking
// arbitrary sessions PAID.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getGateway } from "../_shared/gateway.ts";

// Lab-test stand-in for the kiosk->gateway/bay mapping GS-EdgeNexus's design
// doc lists as an open item (devices.gateway_sn does not exist yet -- no
// column or table anywhere maps a paying kiosk's device_sn to which
// EdgeNexus unit/bay serves it). Once that mapping is built, look it up
// here instead of hardcoding one test target. This only exists to prove
// the App -> CMP -> EdgeNexus -> CMP -> App round trip works end to end.
const TEST_EDGENEXUS_DEVICE_SN = "GS_EN_PRO_01";
const TEST_EDGENEXUS_BAY_ID = 1;
const DEFAULT_PULSE_WEIGHT_CENTS = 25;

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  // Read as raw text, not req.json() -- signature verification needs the
  // exact bytes the gateway signed, which re-serializing a parsed object
  // is not guaranteed to reproduce byte-for-byte.
  const rawBody = await req.text();

  const gateway = getGateway();
  const result = await gateway.verifyWebhook(rawBody, req.headers);

  if (result.kind === "invalid") {
    console.warn(`[payment-webhook] ${gateway.name} signature verification failed`);
    return new Response("invalid signature", { status: 401 });
  }
  if (result.kind === "ignored") {
    // Signature verified, just not an event we act on -- 200 so the gateway
    // doesn't keep retrying something we're never going to process.
    return new Response("ignored", { status: 200 });
  }
  const event = result.event;

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  if (event.status === "PAID") {
    // WHERE status='PENDING' makes this idempotent: gateways retry webhooks
    // liberally, and a second delivery for an already-PAID session just
    // matches zero rows instead of re-running any side effect. The
    // amount_cents match is a second check against a tampered/mismatched
    // callback before money and wash time get treated as reconciled.
    const { data, error } = await supabase
      .from("qr_payment_sessions")
      .update({ status: "PAID", paid_at: new Date().toISOString() })
      .eq("tx_id", event.txId)
      .eq("status", "PENDING")
      .eq("amount_cents", event.amountCents)
      .select("tx_id, device_sn");

    if (error) {
      console.error("[payment-webhook] update failed:", error);
      return new Response("db_error", { status: 500 });
    }
    if (!data || data.length === 0) {
      // Not necessarily a problem -- could be a legitimate duplicate
      // delivery of a webhook already processed, or an amount mismatch
      // worth a closer look. Logged, not surfaced as an error to the
      // gateway (which would just trigger more retries).
      console.warn(`[payment-webhook] no PENDING session matched tx_id=${event.txId} amount=${event.amountCents}`);
    } else {
      // Dispatch the actual relay pulse to EdgeNexus. Best-effort: a
      // failure here must not turn into a 500 (the gateway would just
      // retry the whole webhook, re-attempting a payment-side update that
      // already succeeded) -- log and move on, same as the qr_payment_sessions
      // no-match case above.
      try {
        const decidingDeviceSn = data[0].device_sn as string | null;
        let pulseWeightCents = DEFAULT_PULSE_WEIGHT_CENTS;

        if (decidingDeviceSn) {
          const { data: deviceRow } = await supabase
            .from("devices")
            .select("org_id")
            .eq("sn", decidingDeviceSn)
            .maybeSingle();

          if (deviceRow?.org_id) {
            const { data: configRow } = await supabase
              .from("app_configurations")
              .select("settings")
              .eq("org_id", deviceRow.org_id)
              .order("version", { ascending: false })
              .limit(1)
              .maybeSingle();

            const configuredWeight = configRow?.settings?.pulse_weight_cents;
            if (typeof configuredWeight === "number" && configuredWeight > 0) {
              pulseWeightCents = configuredWeight;
            }
          }
        }

        const pulses = Math.max(1, Math.round(event.amountCents / pulseWeightCents));

        const { error: cmdError } = await supabase.from("device_commands").insert({
          device_sn: TEST_EDGENEXUS_DEVICE_SN,
          command: "START_SERVICE",
          payload: {
            bay_id: TEST_EDGENEXUS_BAY_ID,
            pulses,
            duration_sec: 240,
            tx_id: event.txId,
          },
          status: "PENDING",
        });

        if (cmdError) {
          console.error("[payment-webhook] device_commands insert failed:", cmdError);
        }
      } catch (e) {
        console.error("[payment-webhook] EdgeNexus dispatch step threw:", e);
      }
    }
  } else {
    // FAILED: qr_payment_sessions.status has no FAILED value (see CHECK
    // constraint in docs/supabase_full_schema.sql) -- CANCELLED is the
    // closest existing terminal state. Left PENDING-only sessions to expire
    // via the cron sweep rather than adding a new status value here.
    await supabase
      .from("qr_payment_sessions")
      .update({ status: "CANCELLED" })
      .eq("tx_id", event.txId)
      .eq("status", "PENDING");
  }

  // Some gateways require a specific response body/format to stop retrying
  // (e.g. Alipay expects the literal string "success") -- once a real
  // gateway is wired in, check its docs for this before assuming plain 200
  // is enough.
  return new Response("ok", { status: 200 });
});
