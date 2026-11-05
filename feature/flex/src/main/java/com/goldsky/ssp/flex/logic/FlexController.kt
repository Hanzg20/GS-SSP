package com.goldsky.ssp.flex.logic

/**
 * The partner's own controller that hands the product over (Aegis Flex's whole point: the
 * terminal takes the money, the customer's machine does the rest -- e.g. Outdoor Vend's InHand
 * EC3320 over the LAN, 2026-11-05). Told after the money is in; only its "dispensed" keeps it.
 */
interface FlexController {
    /** Shown in the technician panel. */
    val description: String

    /** True once a controller is set up: until then a sale is given back (see [NotConnected]). */
    val connected: Boolean

    /**
     * Hand over [quantity] of [productId] for the paid order [orderId] ([amountCents]).
     * True = the controller confirmed it; false = it refused, failed or never answered --
     * the money goes back.
     */
    suspend fun dispense(orderId: String, productId: String?, quantity: Int, amountCents: Int): Boolean

    /**
     * No controller yet (the LAN link is the next step): nothing can be handed over, so every
     * approved sale is reversed at once and the screen says so -- never charged-but-unfulfilled.
     */
    object NotConnected : FlexController {
        override val description = "未连接控制器"
        override val connected = false
        override suspend fun dispense(orderId: String, productId: String?, quantity: Int, amountCents: Int) = false
    }
}
