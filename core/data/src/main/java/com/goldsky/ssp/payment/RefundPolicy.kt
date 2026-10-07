package com.goldsky.ssp.payment

/**
 * Whether the terminal reverses (VOID, falling back to REFUND) a charged
 * sale on its own: machine didn't start (Wash / Timer), VIP top-up refused
 * by the server, approved sale left PENDING (PendingResolver).
 *
 * Off for now (owner's decision, 2026-10-07): the sale stays PAID, the
 * customer is told to see the attendant, and a CRITICAL MANUAL_REFUND_NEEDED
 * alert (PENDING_APPROVED_REVIEW from PendingResolver) goes to CMP, where the
 * sale also shows under compensation. A person refunds in the merchant
 * portal or issues a compensation coupon. The technician payment self-test
 * still voids its own $1.00 sale -- that is the test.
 */
object RefundPolicy {
    const val AUTO_REVERSAL = false

    fun reportManualRefund(sn: String, ecrRefNum: String, amountCents: Int, why: String) {
        DiagnosticManager.reportError(
            sn, "MANUAL_REFUND_NEEDED", severity = "CRITICAL",
            trace = "$ecrRefNum $amountCents cents: $why -- refund in the merchant portal or compensate"
        )
    }
}
