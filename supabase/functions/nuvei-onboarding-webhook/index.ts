// Receives Nuvei's async onboarding/KYC status updates (application moved to
// PENDING_REVIEW / NEEDS_INFO / APPROVED / REJECTED after the initial
// synchronous submission). Structure mirrors ../payment-webhook/index.ts:
// raw-body read, service-role client, idempotent status-column update,
// always 200 unless the signature itself is invalid.
//
// verify_jwt must be OFF for this function (see supabase/config.toml) --
// the caller is Nuvei, not a signed-in user.
//
// NOT YET FUNCTIONAL: signature verification and payload parsing below are
// placeholders. Nuvei's actual webhook signature scheme and payload shape
// are unknown until their onboarding API doc is shared into this session
// (see plan open item #1) -- do not wire this into Nuvei's dashboard as a
// live webhook URL until that's filled in, it will currently reject every
// call.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

function verifyNuveiSignature(_rawBody: string, _headers: Headers): boolean {
  // TODO: replace with Nuvei's real signature verification once the
  // onboarding API doc is available. Returning false unconditionally so
  // this function fails closed (401) rather than silently accepting
  // unverified webhook calls if it's ever deployed before this is filled in.
  return false;
}

function parseNuveiOnboardingEvent(
  _rawBody: string,
): { externalRef: string; status: "PENDING_REVIEW" | "NEEDS_INFO" | "APPROVED" | "REJECTED"; raw: unknown } | null {
  // TODO: replace with real parsing of Nuvei's onboarding webhook payload.
  return null;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  const rawBody = await req.text();

  if (!verifyNuveiSignature(rawBody, req.headers)) {
    console.warn("[nuvei-onboarding-webhook] signature verification failed (or not yet implemented)");
    return new Response("invalid signature", { status: 401 });
  }

  const event = parseNuveiOnboardingEvent(rawBody);
  if (!event) {
    console.warn("[nuvei-onboarding-webhook] event payload not recognized (or parsing not yet implemented)");
    return new Response("ignored", { status: 200 });
  }

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  const { error } = await supabase
    .from("merchant_acquirer_accounts")
    .update({
      status: event.status,
      raw_response: event.raw,
      ...(event.status === "APPROVED" ? { approved_at: new Date().toISOString() } : {}),
    })
    .eq("acquirer", "nuvei")
    .eq("external_merchant_id", event.externalRef);

  if (error) {
    console.error("[nuvei-onboarding-webhook] update failed:", error);
    return new Response("db_error", { status: 500 });
  }

  return new Response("ok", { status: 200 });
});
