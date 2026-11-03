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
 * voided, never kept. A VIP member code (Cael payment methods "card + scan") can stand in
 * for the card in either flow: Scanning, then the balance pays (item first: at once; card
 * first: the session runs on the balance, each item deducted at its price).
 */
sealed class VendingState {
    object Booting : VendingState()
    object Idle : VendingState()

    /** [amountCents] null = a hold for a card-first session, else the item's price. */
    data class Authorizing(val amountCents: Int?) : VendingState()

    /** The VIP member code is being scanned: [amountCents] = the item's price (item first), null = card first. */
    data class Scanning(val amountCents: Int?) : VendingState()

    /**
     * Card approved first: the customer now chooses on the machine before [deadline].
     * [vipBalanceCents]: the session runs on a VIP card with this balance (no card hold).
     */
    data class SessionOpen(val limitCents: Int, val deadline: Long, val vipBalanceCents: Int? = null) : VendingState()

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

    /** [vipBalanceCents]: paid from a VIP card, its balance now. */
    data class Success(val orderId: String, val amountCents: Int, val itemSlot: String, val vipBalanceCents: Int? = null) : VendingState()

    data class NotCharged(val reason: Reason) : VendingState()

    /** Card payment can't be taken on this terminal: every vend request is denied. */
    object Unavailable : VendingState()

    /**
     * [START_FIRST]: chosen on the machine where only card first is offered. VIP: [NO_CODE] nothing
     * scanned, [VIP_INVALID] not a member code of this store (or inactive / expired / daily limit),
     * [VIP_LOW_BALANCE] the balance doesn't cover it, [VIP_OFFLINE] the server couldn't be reached.
     */
    enum class Reason {
        NO_SELECTION, CANCELLED, VEND_FAILED, DECLINED, SOLD_OUT, OVER_LIMIT, START_FIRST,
        NO_CODE, VIP_INVALID, VIP_LOW_BALANCE, VIP_OFFLINE,
    }
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
