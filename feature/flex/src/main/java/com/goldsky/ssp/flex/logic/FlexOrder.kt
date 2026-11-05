package com.goldsky.ssp.flex.logic

import kotlinx.serialization.Serializable

/**
 * One sale's money, kept on the terminal from before the card is asked for until the
 * cloud has the final row (FlexSyncWorker): a crash in between leaves it PENDING and
 * FlexRecovery gives the money back on the next start.
 */
@Serializable
data class FlexOrder(
    val orderId: String,       // the payment's TransIndexCode (ecr_ref_num)
    val amountCents: Int,      // HOLD: the amount held until the controller confirms, then what's settled
    val slot: String,          // what was bought, for the log (the product's name)
    val paymentStatus: String,             // PENDING, AUTHORIZED (hold), PAID, VOIDED
    val dispenseStatus: String,            // PENDING, SUCCESS, FAILED
    val cloudSynced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * SALE (charged at once), HOLD (pre-authorization, settled once dispensed) or VIP
     * (paid from a VIP card's balance, [vipCardUid]; given back with VipRepository.refund).
     */
    val kind: String = "SALE",
    val authCode: String? = null,
    val entryMode: String? = null,
    val cardBrand: String? = null,
    val cardAid: String? = null,
    val cardBin: String? = null,
    val vipCardUid: String? = null,
    /** The Cael product (products.id); null for an item not from Cael. */
    val productId: String? = null,
    /** HOLD: the AuthCompletion's TransIndexCode that settled it -- what a void reverses. */
    val settleRef: String? = null,
)
