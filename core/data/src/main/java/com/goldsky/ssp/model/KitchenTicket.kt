package com.goldsky.ssp.model

/** One dish for the kitchen: options on their own lines under it. */
data class KitchenLine(val name: String, val quantity: Int, val options: List<String>)

/** A quick-service order as the kitchen (and the customer's receipt) sees it. */
data class KitchenTicket(
    val number: Int,
    val takeout: Boolean,
    val note: String,
    val lines: List<KitchenLine>,
    val staffName: String?
) {
    val orderType: String get() = if (takeout) "TAKEOUT" else "DINE_IN"
}
