package com.goldsky.ssp.model

/**
 * Represents a single line item in the shopping cart.
 * Supports Clover-style modifiers.
 */
data class CartItem(
    val product: Product,
    val selectedModifiers: List<ProductModifier> = emptyList(),
    val id: String = java.util.UUID.randomUUID().toString(),
    var quantity: Int = 1
) {
    val unitPriceCents: Int get() = product.price_cents + selectedModifiers.sumOf { it.price_cents }
    val totalPriceCents: Int get() = unitPriceCents * quantity
}
