package com.goldsky.ssp.vending.db

import kotlinx.serialization.Serializable

/**
 * Audit record for Vending transactions.
 */
@Serializable
data class VendingOrder(
    val orderId: String,       // ECR Ref Num / Transaction ID
    val amountCents: Int,
    val slot: String,
    val paymentStatus: String,             // PENDING, PAID, VOIDED, REFUNDED
    val dispenseStatus: String,            // PENDING, SUCCESS, FAILED, TIMEOUT
    val cloudSynced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
