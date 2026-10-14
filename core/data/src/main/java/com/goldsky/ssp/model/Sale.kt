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
    val barcode: String? = null,
    // Taken off this line: its own discount plus its share of the order's (Pricing).
    val discountCents: Int = 0,
    // The line's own discount, for the receipt ("Senior 10%"); null = none.
    val discountLabel: String? = null
) {
    val grossCents: Int get() = unitPriceCents * quantity
    /** What the customer pays for the line before tax -- after discounts. Tax is on this. */
    val lineTotalCents: Int get() = grossCents - discountCents
}

/** What checkout hands to payment: totals plus the lines they came from. */
data class CheckoutSale(
    // Before tax, after discounts (also what tip suggestions are based on).
    val subtotalCents: Int,
    val taxCents: Int,
    val lines: List<SaleLine>,
    val discountCents: Int = 0,
    val discounts: List<com.goldsky.ssp.payment.AppliedDiscount> = emptyList(),
    // Set when this sale collects a delivery order (marked paid once it goes through).
    val deliveryOrderId: String? = null
)
