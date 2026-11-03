package com.goldsky.ssp.vending.logic

/**
 * The vend loop, one state per screen. Both ways a cashless vending machine
 * (MDB) is used are covered:
 *  - card first (how most machines around Ottawa work, Nayax/Cantaloupe style):
 *    Idle -> Authorizing(null) [a hold] -> SessionOpen [choose on the machine]
 *    -> Dispensing -> Success, the hold settled at the item's price;
 *  - item first (machines set to "always idle"): the machine asks for a price
 *    -> VendRequestReceived [tap to pay] -> Authorizing(price) -> Dispensing -> Success.
 * Anything that ends without an item ends in NotCharged: the hold / sale is
 * voided, never kept.
 */
sealed class VendingState {
    object Booting : VendingState()
    object Idle : VendingState()

    /** [amountCents] null = a hold for a card-first session, else the item's price. */
    data class Authorizing(val amountCents: Int?) : VendingState()

    /** Card approved first: the customer now chooses on the machine before [deadline]. */
    data class SessionOpen(val limitCents: Int, val deadline: Long) : VendingState()

    data class VendRequestReceived(
        val amountCents: Int,
        val itemSlot: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : VendingState()

    data class Dispensing(
        val transactionId: String,
        val amountCents: Int,
        val itemSlot: String,
        val deadline: Long // To handle Auto-Void if timeout
    ) : VendingState()

    data class Success(val orderId: String, val amountCents: Int, val itemSlot: String) : VendingState()

    data class NotCharged(val reason: Reason) : VendingState()

    /** Card payment can't be taken on this terminal: every vend request is denied. */
    object Unavailable : VendingState()

    /** [START_FIRST]: chosen on the machine where only card first is offered. */
    enum class Reason { NO_SELECTION, CANCELLED, VEND_FAILED, DECLINED, SOLD_OUT, OVER_LIMIT, START_FIRST }
}

/**
 * Which way customers buy on this terminal (technician panel, per machine, owner 2026-11-03):
 * [BOTH] = START on the idle screen or a price from the machine; [CARD_FIRST] = START only
 * (a selection on the machine without a card is refused); [ITEM_FIRST] = choose on the
 * machine, pay the price shown (no START).
 */
enum class PurchaseFlow { BOTH, CARD_FIRST, ITEM_FIRST }

/**
 * Abstract interface to isolate MDB hardware from the UI logic.
 */
interface IMdbController {
    fun initialize()
    /** False when the MDB board couldn't be opened: no machine to sell for. */
    val ready: Boolean get() = true
    /** A vending machine is on the bus (false = nothing to sell for; null = can't tell). */
    fun machineOnline(): Boolean? = true
    /** For the technician panel. */
    fun status(): String = "MDB simulator"
    fun onVendRequest(callback: (amount: Int, slot: String) -> Unit)
    fun onVendSuccess(callback: () -> Unit)
    fun onVendFailure(callback: (errorCode: Int) -> Unit)
    /** The machine cancelled (its own cancel button, or it closed the session). */
    fun onMachineCancel(callback: () -> Unit) {}
    fun approveVend()
    fun denyVend()
    /** Card first: tell the machine a card is good for up to [fundsCents] (MDB Begin Session). */
    fun beginSession(fundsCents: Int)
    /** Close the machine's session (no more selections on this card). */
    fun endSession()
    fun setFailNext(fail: Boolean)
}
