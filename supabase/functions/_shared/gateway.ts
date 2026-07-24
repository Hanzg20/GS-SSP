import { stubGateway } from "./gateways/stub.ts";
import { stripeGateway } from "./gateways/stripe.ts";

// Gateway-agnostic contract for scan-to-pay. Nothing in create-qr-session or
// payment-webhook should ever import a specific provider (Stripe/Alipay/
// WeChat) directly -- they only talk to this interface, so picking a
// provider later is "write one adapter file + flip PAYMENT_GATEWAY", not a
// rewrite of the two functions that actually handle the money flow.

export interface CreatePaymentIntentParams {
  /** Our own reference (qr_payment_sessions.tx_id). Must be threaded through
   * to the gateway (as a client reference / out_trade_no / metadata field,
   * depending on provider) so the webhook can correlate its callback back to
   * this exact row without needing a second lookup table. */
  txId: string;
  amountCents: number;
  currency: string; // e.g. "CAD"
}

export interface CreatePaymentIntentResult {
  /** The payload to render as a QR code / deep link for the customer to scan. */
  codeUrl: string;
}

export interface WebhookEvent {
  txId: string;
  status: "PAID" | "FAILED";
  amountCents: number;
}

/**
 * A real gateway (Stripe included) delivers many event types to the same
 * webhook URL -- only some of them are ones we act on. Collapsing "signature
 * didn't verify" and "signature verified, but this event doesn't concern us"
 * into the same null would make payment-webhook answer irrelevant-but-valid
 * events with 401, which just makes the gateway retry them forever.
 *   - invalid: signature failed verification -- caller responds 401.
 *   - ignored: signature verified, event isn't one we act on -- caller
 *     responds 200 (acknowledged, nothing to do) so the gateway stops retrying.
 *   - event: a payment-status event we do act on.
 */
export type WebhookVerifyResult =
  | { kind: "invalid" }
  | { kind: "ignored" }
  | { kind: "event"; event: WebhookEvent };

export interface PaymentGateway {
  readonly name: string;

  createPaymentIntent(params: CreatePaymentIntentParams): Promise<CreatePaymentIntentResult>;

  /** Verifies the webhook's signature and classifies it -- see WebhookVerifyResult. */
  verifyWebhook(rawBody: string, headers: Headers): Promise<WebhookVerifyResult>;
}

/**
 * Selects the active gateway from the PAYMENT_GATEWAY env var (set via
 * `supabase secrets set` / the Edge Function's environment). Defaults to the
 * stub so create-qr-session keeps working end-to-end (fabricated QR payload,
 * never actually payable) before a real gateway is chosen and wired in --
 * see _shared/gateways/stub.ts for why that's safe to ship but not to rely on.
 */
export function getGateway(): PaymentGateway {
  const name = Deno.env.get("PAYMENT_GATEWAY") ?? "stub";
  switch (name) {
    case "stripe":
      return stripeGateway;
    case "stub":
    default:
      return stubGateway;
  }
}
