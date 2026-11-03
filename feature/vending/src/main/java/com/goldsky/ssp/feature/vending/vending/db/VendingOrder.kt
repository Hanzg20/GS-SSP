package com.goldsky.ssp.vending.db

import kotlinx.serialization.Serializable

/**
 * One vend's money, kept on the terminal from before the card is asked for until the
 * cloud has the final row (VendingSyncWorker): a crash in between leaves it PENDING and
 * VendRecovery gives the money back on the next start.
 */
@Serializable
data class VendingOrder(
    val orderId: String,       // the payment's TransIndexCode (ecr_ref_num)
    val amountCents: Int,      // HOLD: the limit until an item is chosen, then its price
    val slot: String,
    val paymentStatus: String,             // PENDING, AUTHORIZED (hold), PAID, VOIDED
    val dispenseStatus: String,            // PENDING, SUCCESS, FAILED
    val cloudSynced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * SALE (item first), HOLD (card first: pre-authorization, settled at the price) or VIP
     * (paid from a VIP card's balance, [vipCardUid]; given back with VipRepository.refund).
     */
    val kind: String = "SALE",
    val authCode: String? = null,
    val entryMode: String? = null,
    val cardBrand: String? = null,
    val cardAid: String? = null,
    val cardBin: String? = null,
    val vipCardUid: String? = null,
)
