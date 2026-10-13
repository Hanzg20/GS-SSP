package com.goldsky.ssp.model

/** One dish for the kitchen: options (and a note) on their own lines under it. */
data class KitchenLine(val name: String, val quantity: Int, val options: List<String>, val note: String? = null)

/**
 * An order as the kitchen (and the customer's receipt) sees it. Quick-service:
 * [number] is the call number. Dine-in: [table] is set, [number] is the check
 * number and [round] counts the sends (2 and up = "add" to the table).
 */
data class KitchenTicket(
    val number: Int,
    val takeout: Boolean,
    val note: String,
    val lines: List<KitchenLine>,
    val staffName: String?,
    val table: String? = null,
    val round: Int = 1,
    val guests: Int? = null
) {
    val orderType: String get() = if (takeout) "TAKEOUT" else "DINE_IN"
    val isDineIn: Boolean get() = table != null
}
