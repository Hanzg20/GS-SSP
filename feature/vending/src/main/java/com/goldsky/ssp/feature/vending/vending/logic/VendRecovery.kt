package com.goldsky.ssp.vending.logic

import android.content.Context
import android.util.Log

/**
 * On start: vends the app never finished (crash, power cut, PAYWizard silent) -- no
 * item was confirmed, so the money goes back. A hold is released (nothing was ever
 * charged), a sale reversed (void, else refund). What can't be given back stays in the
 * journal as PAID / FAILED so the row reaches Cael for a refund by hand -- same rule as
 * Wash / Timer's PendingResolver: never charged-but-unfulfilled, never guessed.
 */
object VendRecovery {
    private const val TAG = "VendRecovery"

    fun run(context: Context, payment: VendPayment, onChanged: () -> Unit) {
        val app = context.applicationContext
        val left = OrderRepository.getUnfinishedOrders()
        if (left.isEmpty()) return
        Log.w(TAG, "${left.size} unfinished vend(s) from before: giving the money back")
        left.forEach { order ->
            if (order.kind == "HOLD") {
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
