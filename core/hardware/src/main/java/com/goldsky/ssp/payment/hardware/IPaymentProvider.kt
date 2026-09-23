package com.goldsky.ssp.payment.hardware

/**
 * Common interface for card payment processing across different vendors (PAX, ID TECH).
 */
interface IPaymentProvider {

    /**
     * What the terminal reported about the card, for credit/debit
     * classification. [scheme] is the terminal's own "Credit"/"Debit" flag
     * (WizarPOS TransScheme) when it sends one; [aid] is the EMV application
     * id and [bin] the first 6 PAN digits -- kept so a card can be re-classified
     * later without re-reading it. Any field may be null.
     */
    data class CardInfo(val scheme: String?, val brand: String?, val aid: String?, val bin: String?)

    interface PaymentCallback {
        /** Called before [onSuccess] by providers that can report card details. Default: ignore. */
        fun onCardInfo(info: CardInfo) {}

        /**
         * [entryMode] records how the card was actually presented (e.g.
         * "MSR", "EMV_OR_CTLS(cardType=...)") for reconciliation/receipts --
         * see docs/card_payment_integration.md §3.3 "Entry Mode 未落库".
         * Defaults to "UNKNOWN" for callers that don't have a more specific
         * value (e.g. VIP/QR/coupon flows, which don't go through a card
         * reader at all).
         *
         * [refNum] is whatever THIS provider's [voidTransaction]/
         * [refundTransaction] need to find the sale again -- callers store it
         * only to pass back for an automatic reversal. For WizarPOS that's
         * the sale's own TransIndexCode (our ecrRefNum), not the bank RRN.
         */
        fun onSuccess(authCode: String, refNum: String, entryMode: String = "UNKNOWN")

        /**
         * [isHardwareFault] distinguishes "the reader/SDK itself is broken"
         * (disconnected, not initialized, SDK call failed) from an ordinary
         * business outcome (card declined, cancelled, customer walked away).
         * Callers should route the former to DiagnosticManager for ops
         * visibility -- a bad reader needs a technician, a declined card
         * doesn't.
         */
        fun onFailure(errorMsg: String, isHardwareFault: Boolean = false)
        fun onProgress(message: String)

        /**
         * Called when a card or NFC tag is detected.
         * [type] could be "MIFARE", "ISO_14443", "UNKNOWN", etc.
         * [uid] is the serial/unique ID of the card.
         */
        fun onCardDetected(type: String, uid: String) {}
    }

    /**
     * Initiates a SALE transaction.
     */
    fun startSale(amountInCents: Int, ecrRefNum: String, callback: PaymentCallback)

    /**
     * Voids a previous transaction.
     */
    fun voidTransaction(refNum: String, callback: PaymentCallback)

    /**
     * Refunds a previous transaction.
     */
    fun refundTransaction(refNum: String, amountInCents: Int, callback: PaymentCallback)

    /**
     * MainActivity calls this before [startSale], expecting a cheap
     * presence-only check (e.g. "is any card there") separate from actually
     * running the transaction. Not every vendor SDK has such a check: if
     * yours doesn't (see [com.goldsky.ssp.iris.hardware.idtech.IdTechPaymentProvider]
     * for why ID TECH can't), implementing this as an immediate no-op success
     * and doing the real multi-mode work in [startSale] is the correct choice
     * -- do not fake a detection step that starts a real, amount-bearing
     * transaction here AND again in startSale, or the customer's card gets
     * charged/read twice for one attempt.
     */
    fun startCardDetection(amountInCents: Int, callback: PaymentCallback)

    /**
     * Stops card detection.
     */
    fun stopCardDetection()

    /**
     * Cancels the current ongoing transaction (SALE/VOID/REFUND).
     */
    fun cancelCurrentTransaction()

    /**
     * Closes the current transaction batch (Settle).
     */
    fun closeBatch(callback: PaymentCallback)

    /**
     * Helper to attempt VOID first, then automatically fallback to REFUND
     * if VOID fails. Useful for hardware-failure-after-auth scenarios.
     */
    fun voidOrRefund(refNum: String, amountInCents: Int, callback: (success: Boolean, method: String) -> Unit) {
        voidTransaction(refNum, object : PaymentCallback {
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) = callback(true, "VOID")
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                // If VOID fails, try REFUND
                refundTransaction(refNum, amountInCents, object : PaymentCallback {
                    override fun onSuccess(authCode: String, refNum: String, entryMode: String) = callback(true, "REFUND")
                    override fun onFailure(errorMsg: String, isHardwareFault: Boolean) = callback(false, "NONE")
                    override fun onProgress(message: String) {}
                })
            }
            override fun onProgress(message: String) {}
        })
    }
}
