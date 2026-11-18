package com.goldsky.ssp.model

/** One dish for the kitchen: options (and a note) on their own lines under it. */
data class KitchenLine(val name: String, val quantity: Int, val options: List<String>, val note: String? = null)

/**
 * An order as the kitchen (and the customer's receipt) sees it. Quick-service:
 * [number] is the call number. Dine-in: [table] is set, [number] is the check
 * number and [round] counts the sends (2 and up = "add" to the table). Arke online
 * order: [onlineCode] is its number ("A12" pickup, "D12" delivery) and [customer] the name it
 * is for; a delivery order also carries [deliveryTo] (address lines, the phone) for the driver.
 * [platform] names a delivery platform's order ("UBER EATS"): printed as the heading instead.
 */
data class KitchenTicket(
    val number: Int,
    val takeout: Boolean,
    val note: String,
    val lines: List<KitchenLine>,
    val staffName: String?,
    val table: String? = null,
    val round: Int = 1,
    val guests: Int? = null,
    val onlineCode: String? = null,
    val customer: String? = null,
    val deliveryTo: List<String>? = null,
    val platform: String? = null,
    /** An online order booked ahead: when it's wanted, as printed ("11-19 18:30"). */
    val scheduledFor: String? = null
) {
    val orderType: String get() = if (takeout) "TAKEOUT" else "DINE_IN"
    val isDineIn: Boolean get() = table != null
    val isOnline: Boolean get() = onlineCode != null
    val isDelivery: Boolean get() = isOnline && deliveryTo != null
}
