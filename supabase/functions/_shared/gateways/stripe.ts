import Stripe from "npm:stripe@^17.0.0";
import type {
  PaymentGateway,
  CreatePaymentIntentParams,
  CreatePaymentIntentResult,
  WebhookVerifyResult,
} from "../gateway.ts";

// Deno has no Node `http`/`crypto` modules, so both the HTTP transport and
// the webhook-signature crypto need Stripe's Web-standard implementations
// instead of the SDK's Node defaults.
const stripe = new Stripe(Deno.env.get("STRIPE_SECRET_KEY") ?? "", {
  httpClient: Stripe.createFetchHttpClient(),
  // apiVersion intentionally left unpinned -- defaults to whatever this SDK
  // version ships with. Pin explicitly (e.g. "2024-06-20") if this ever
  // needs to survive an SDK upgrade without an unreviewed API version bump.
});
const cryptoProvider = Stripe.createSubtleCryptoProvider();

export const stripeGateway: PaymentGateway = {
  name: "stripe",

  async createPaymentIntent(params: CreatePaymentIntentParams): Promise<CreatePaymentIntentResult> {
    // Checkout Session, not a raw PaymentIntent -- the customer scans this
    // on their own phone, so a Stripe-hosted page (card/Apple Pay/Google
    // Pay, and Alipay/WeChat Pay if enabled on the account, all on the same
    // URL) is simpler and safer than building a custom payment UI here.
    const session = await stripe.checkout.sessions.create({
      mode: "payment",
      payment_method_types: ["card"], // add "alipay" / "wechat_pay" once enabled on the Stripe account
      line_items: [
        {
          price_data: {
            currency: params.currency.toLowerCase(),
            product_data: { name: "GS-SSP Car Wash" },
            unit_amount: params.amountCents,
          },
          quantity: 1,
        },
      ],
      // Correlates the webhook back to qr_payment_sessions.tx_id -- this is
      // what payment-webhook reads out of the event instead of needing a
      // separate lookup table.
      client_reference_id: params.txId,
      // Required by Checkout even though nothing meaningful happens on these
      // pages -- the customer's own phone tab is irrelevant to the flow;
      // what actually resolves the payment is the webhook plus the kiosk's
      // own poll of qr_payment_sessions.
      success_url: "https://gs-ssp.ca/pay/complete",
      cancel_url: "https://gs-ssp.ca/pay/cancel",
    });

    if (!session.url) {
      throw new Error("Stripe Checkout Session created without a url");
    }
    return { codeUrl: session.url };
  },

  async verifyWebhook(rawBody: string, headers: Headers): Promise<WebhookVerifyResult> {
    const signature = headers.get("stripe-signature");
    const webhookSecret = Deno.env.get("STRIPE_WEBHOOK_SECRET");
    if (!signature || !webhookSecret) {
      return { kind: "invalid" };
    }

    let event: Stripe.Event;
    try {
      // constructEventAsync (not the sync constructEvent) is required here
      // -- it uses the SubtleCrypto provider above instead of Node's
      // `crypto` module, which doesn't exist in the Deno edge runtime.
      event = await stripe.webhooks.constructEventAsync(rawBody, signature, webhookSecret, undefined, cryptoProvider);
    } catch (e) {
      console.error("[stripe] webhook signature verification failed:", e);
      return { kind: "invalid" };
    }

    switch (event.type) {
      case "checkout.session.completed":
      case "checkout.session.async_payment_succeeded": {
        const session = event.data.object as Stripe.Checkout.Session;
        if (!session.client_reference_id) {
          console.error(`[stripe] ${event.type} with no client_reference_id, event id=${event.id}`);
          return { kind: "ignored" };
        }
        return {
          kind: "event",
          event: {
            txId: session.client_reference_id,
            status: "PAID",
            amountCents: session.amount_total ?? 0,
          },
        };
      }
      case "checkout.session.expired":
      case "checkout.session.async_payment_failed": {
        const session = event.data.object as Stripe.Checkout.Session;
        if (!session.client_reference_id) {
          return { kind: "ignored" };
        }
        return {
          kind: "event",
          event: {
            txId: session.client_reference_id,
            status: "FAILED",
            amountCents: session.amount_total ?? 0,
          },
        };
      }
      default:
        // Signature was valid, just not an event type we act on -- Stripe
        // sends many. Configure the webhook endpoint in the Stripe
        // Dashboard to subscribe only to the four event types above to
        // avoid most of this traffic in the first place, but "ignored" is
        // the correct fallback regardless of what's actually subscribed.
        return { kind: "ignored" };
    }
  },
};
