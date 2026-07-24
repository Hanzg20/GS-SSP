import type {
  PaymentGateway,
  CreatePaymentIntentParams,
  CreatePaymentIntentResult,
  WebhookVerifyResult,
} from "../gateway.ts";

/**
 * Placeholder gateway, active by default (PAYMENT_GATEWAY unset). Lets
 * create-qr-session be deployed and exercised end-to-end -- session row
 * created, QR rendered, app polls it -- before a real merchant account
 * exists. verifyWebhook always reports "invalid", so nothing can ever reach
 * payment-webhook and mark a session PAID through this path; sessions
 * created under the stub gateway will only ever time out.
 *
 * MUST NOT be selected in production once a real gateway is wired in --
 * there is no payment happening behind this QR code.
 */
export const stubGateway: PaymentGateway = {
  name: "stub",

  async createPaymentIntent(params: CreatePaymentIntentParams): Promise<CreatePaymentIntentResult> {
    return {
      codeUrl: `https://gs-ssp.ca/pay-stub?tx=${encodeURIComponent(params.txId)}&amt=${params.amountCents}`,
    };
  },

  async verifyWebhook(_rawBody: string, _headers: Headers): Promise<WebhookVerifyResult> {
    return { kind: "invalid" };
  },
};
