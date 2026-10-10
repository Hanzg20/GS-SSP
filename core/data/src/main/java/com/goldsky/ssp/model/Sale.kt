package com.goldsky.ssp.model

import kotlinx.serialization.Serializable

/** One line of a sale, as rung up -- the source for transaction_items and the receipt. */
@Serializable
data class SaleLine(
    val name: String,
    val quantity: Int,
    val unitPriceCents: Int,
    val taxRateBps: Int,
    val productId: String? = null,
    val barcode: String? = null
) {
    val lineTotalCents: Int get() = unitPriceCents * quantity
}

/** What checkout hands to payment: totals plus the lines they came from. */
data class CheckoutSale(
    val subtotalCents: Int,
    val taxCents: Int,
    val lines: List<SaleLine>,
    // Set when this sale collects a delivery order (marked paid once it goes through).
    val deliveryOrderId: String? = null
)
