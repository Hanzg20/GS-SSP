package com.goldsky.ssp.flex.logic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch

/**
 * On start: sales the app never finished (crash, power cut, PAYWizard silent) -- the
 * controller never confirmed a dispense, so the money goes back. A hold is released (nothing was ever
 * charged), a sale reversed (void, else refund). What can't be given back stays in the
 * journal as PAID / FAILED so the row reaches Cael for a refund by hand -- same rule as
 * Wash / Timer's PendingResolver: never charged-but-unfulfilled, never guessed. A VIP
 * payment's balance is given back to the card (VipRepository.refund).
 */
object FlexRecovery {
    private const val TAG = "FlexRecovery"
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    fun run(context: Context, payment: FlexPayment, onChanged: () -> Unit) {
        val app = context.applicationContext
        val left = OrderRepository.getUnfinishedOrders()
        if (left.isEmpty()) return
        Log.w(TAG, "${left.size} unfinished sale(s) from before: giving the money back")
        left.forEach { order ->
            val vipUid = order.vipCardUid
            if (order.kind == "VIP" && vipUid != null) {
                // Paid from a VIP card: give the balance back (once per order, server-side;
                // nothing deducted = nothing to give). Offline: kept for the next start.
                scope.launch {
                    if (com.goldsky.ssp.payment.VipRepository.refund(vipUid, order.orderId)) {
                        Log.i(TAG, "VIP ${order.orderId} given back")
                        if (order.paymentStatus == "PENDING") OrderRepository.remove(app, order.orderId)
                        else OrderRepository.updateOrder(app, order.orderId, "FAILED", "VOIDED")
                        onChanged()
                    } else {
                        Log.e(TAG, "VIP ${order.orderId}: refund not confirmed -- retried on the next start")
                    }
                }
            } else if (order.kind == "HOLD") {
                payment.release(order.orderId) { ok ->
                    Log.i(TAG, "Hold ${order.orderId} released: $ok")
                    // A hold that couldn't be released expires on its own; nothing was taken.
                    OrderRepository.remove(app, order.orderId)
                    onChanged()
                }
            } else {
                payment.reverse(order.orderId, order.amountCents) { ok ->
                    Log.i(TAG, "Sale ${order.orderId} reversed: $ok")
                    if (ok && order.paymentStatus == "PENDING") {
                        // Never answered and now reversed: whether it was charged or not,
                        // nothing is owed either way -- no row.
                        OrderRepository.remove(app, order.orderId)
                    } else if (!ok && order.paymentStatus == "PENDING") {
                        // Reversal refused: most likely the sale never happened. Kept local
                        // (not synced: no card behind it) and logged for a look.
                        Log.e(TAG, "Sale ${order.orderId}: no answer then, reversal refused now -- check PAYWizard")
                        OrderRepository.update(app, order.orderId) { it.copy(dispenseStatus = "FAILED", paymentStatus = "UNKNOWN") }
                    } else {
                        OrderRepository.updateOrder(app, order.orderId, "FAILED", if (ok) "VOIDED" else "PAID")
                    }
                    onChanged()
                }
            }
        }
    }
}
