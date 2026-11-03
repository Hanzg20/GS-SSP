package com.goldsky.ssp.vending.logic

import kotlinx.serialization.json.*

/**
 * What the terminal's screen shows, as sent to the big screen (VendScreenLink). Only what a
 * display needs -- no order or card data. A countdown goes as the time left, not a clock time,
 * so the two devices' clocks don't have to agree.
 */
object VendScreenWire {

    /** [limitCents]: the terminal's card-first hold, for "over the limit"; [flow]: what the idle screen asks for. */
    data class Shown(
        val state: VendingState, val french: Boolean, val simulator: Boolean, val limitCents: Int,
        val flow: PurchaseFlow = PurchaseFlow.BOTH,
    )

    fun encode(
        state: VendingState, french: Boolean, simulator: Boolean, limitCents: Int, flow: PurchaseFlow = PurchaseFlow.BOTH,
    ): JsonObject = buildJsonObject {
        put("flow", flow.name)
        put("fr", french)
        put("sim", simulator)
        put("hold", limitCents)
        when (state) {
            VendingState.Booting -> put("s", "BOOTING")
            VendingState.Idle -> put("s", "IDLE")
            VendingState.Unavailable -> put("s", "UNAVAILABLE")
            is VendingState.Authorizing -> { put("s", "AUTHORIZING"); state.amountCents?.let { put("amount", it) } }
            is VendingState.SessionOpen -> {
                put("s", "SESSION")
                put("limit", state.limitCents)
                put("left_ms", (state.deadline - System.currentTimeMillis()).coerceAtLeast(0))
            }
            is VendingState.VendRequestReceived -> { put("s", "PRICE"); put("amount", state.amountCents); put("item", state.itemSlot) }
            is VendingState.Dispensing -> { put("s", "DISPENSING"); put("amount", state.amountCents); put("item", state.itemSlot) }
            is VendingState.Success -> { put("s", "SUCCESS"); put("amount", state.amountCents); put("item", state.itemSlot) }
            is VendingState.NotCharged -> { put("s", "NOT_CHARGED"); put("reason", state.reason.name) }
        }
    }

    /** Null for a message this version doesn't know. */
    fun decode(json: JsonObject): Shown? {
        fun int(k: String) = json[k]?.jsonPrimitive?.intOrNull ?: 0
        fun str(k: String) = json[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        val state = when (str("s")) {
            "BOOTING" -> VendingState.Booting
            "IDLE" -> VendingState.Idle
            "UNAVAILABLE" -> VendingState.Unavailable
            "AUTHORIZING" -> VendingState.Authorizing(json["amount"]?.jsonPrimitive?.intOrNull)
            "SESSION" -> VendingState.SessionOpen(int("limit"), System.currentTimeMillis() + (json["left_ms"]?.jsonPrimitive?.longOrNull ?: 0L))
            "PRICE" -> VendingState.VendRequestReceived(int("amount"), str("item"))
            "DISPENSING" -> VendingState.Dispensing("", int("amount"), str("item"), 0L)
            "SUCCESS" -> VendingState.Success("", int("amount"), str("item"))
            "NOT_CHARGED" -> VendingState.NotCharged(
                runCatching { VendingState.Reason.valueOf(str("reason")) }.getOrDefault(VendingState.Reason.CANCELLED)
            )
            else -> return null
        }
        return Shown(
            state, json["fr"]?.jsonPrimitive?.booleanOrNull ?: false, json["sim"]?.jsonPrimitive?.booleanOrNull ?: false,
            json["hold"]?.jsonPrimitive?.intOrNull ?: 1000,
            runCatching { PurchaseFlow.valueOf(str("flow")) }.getOrDefault(PurchaseFlow.BOTH), // older terminals: BOTH
        )
    }
}
