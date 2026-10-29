package com.goldsky.ssp.feature.retail

import android.content.Context
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.goldsky.ssp.db.OrderEntity
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.ReceiptPrinterManager
import com.goldsky.ssp.payment.StaffMember
import com.goldsky.ssp.payment.TransactionRepository
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whole-sale refund, approved by a manager's PIN -- shared by Iris and Ourea.
 *
 * Card: look the sale up first so PAYWizard has the original's IDs for a
 * reversal (they're only cached in-process), then reverse it -- no card
 * needed before the batch settles. Once settled, a socket-mode Refund needs
 * the customer's card (WizarPOS, 2026-09-28 / 2026-10-10), so the cashier
 * chooses ([RefundState.NeedsCard]): customer taps the card, or it was
 * refunded in the WizarPOS merchant portal and is only recorded here. Never
 * an automatic Refund that would just park PAYWizard's card screen.
 * Cash: just recorded; the cashier hands the money back.
 * Ourea Scan online payment: the server voids it at Nuvei, or refunds it once
 * settled, and records it (scan-pay-refund) -- no card, no PAYWizard.
 * Prints a refund slip either way. All callbacks arrive on the main thread.
 */
object RefundFlow {
    sealed class RefundState {
        data class Processing(val message: String) : RefundState()
        data class Done(val message: String) : RefundState()
        data class Failed(val message: String) : RefundState()
        data class NeedsCard(val order: OrderEntity, val approver: StaffMember) : RefundState()
    }

    private val main = Handler(Looper.getMainLooper())
    private fun amount(o: OrderEntity) = "%.2f".format(o.amountCents / 100.0)

    fun start(context: Context, provider: IPaymentProvider, order: OrderEntity, approver: StaffMember, onState: (RefundState) -> Unit) {
        if (order.paymentMethod == com.goldsky.ssp.payment.OnlinePaymentRepository.METHOD) {
            online(context, order, approver, onState)
            return
        }
        if (order.paymentMethod == "CASH") {
            finish(context, order, approver, "REFUNDED", "Cash handed back: $${amount(order)}", R.string.retail_refund_how_cash, onState)
            return
        }
        onState(RefundState.Processing(context.getString(R.string.retail_refund_reversing, amount(order))))
        val ref = order.ecrRefNum
        provider.queryTransaction(ref) { _ ->
            provider.voidTransaction(ref, object : IPaymentProvider.PaymentCallback {
                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    main.post { finish(context, order, approver, "VOIDED", "Card sale reversed", R.string.retail_refund_how_reversed, onState) }
                }
                override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                    Log.w("RefundFlow", "Reversal of $ref failed: $errorMsg")
                    main.post { onState(RefundState.NeedsCard(order, approver)) }
                }
                override fun onProgress(message: String) {}
            })
        }
    }

    /** Socket-mode Refund: PAYWizard asks for the customer's card. */
    fun withCard(context: Context, provider: IPaymentProvider, order: OrderEntity, approver: StaffMember, onState: (RefundState) -> Unit) {
        onState(RefundState.Processing(context.getString(R.string.retail_refund_tap_card)))
        provider.refundTransaction(order.ecrRefNum, order.amountCents, object : IPaymentProvider.PaymentCallback {
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                main.post { finish(context, order, approver, "REFUNDED", "Refunded to card", R.string.retail_refund_how_card, onState) }
            }
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                main.post { onState(RefundState.Failed(context.getString(R.string.retail_refund_failed, errorMsg))) }
            }
            override fun onProgress(message: String) {}
        })
    }

    /** Refunded in the WizarPOS merchant portal: record it, no PAYWizard call. */
    fun refundedInPortal(context: Context, order: OrderEntity, approver: StaffMember, onState: (RefundState) -> Unit) =
        finish(context, order, approver, "REFUNDED", "Refunded in WizarPOS merchant portal", R.string.retail_refund_how_portal, onState)

    private fun online(context: Context, order: OrderEntity, approver: StaffMember, onState: (RefundState) -> Unit) {
        onState(RefundState.Processing(context.getString(R.string.retail_refund_online_working, amount(order))))
        CoroutineScope(Dispatchers.IO).launch {
            val r = com.goldsky.ssp.payment.OnlinePaymentRepository.refund(order.ecrRefNum, approver.id, approver.name)
            if (r.status == null) {
                val why = when (r.error) {
                    "offline" -> context.getString(R.string.retail_refund_online_offline)
                    "already_voided", "already_refunded" -> context.getString(R.string.retail_refund_online_already)
                    "refund_in_progress" -> context.getString(R.string.retail_refund_online_in_progress)
                    else -> r.reason ?: r.error ?: "?"
                }
                withContext(Dispatchers.Main) { onState(RefundState.Failed(context.getString(R.string.retail_refund_failed, why))) }
                return@launch
            }
            val (how, howRes) = if (r.status == "VOIDED") "Online payment voided (Nuvei)" to R.string.retail_refund_how_online_voided
                                else "Online payment refunded to card (Nuvei)" to R.string.retail_refund_how_online_refunded
            printSlip(context, order, approver, how)
            withContext(Dispatchers.Main) { onState(RefundState.Done(context.getString(R.string.retail_refund_done, amount(order), context.getString(howRes)))) }
        }
    }

    private fun printSlip(context: Context, order: OrderEntity, approver: StaffMember, how: String) =
        ReceiptPrinterManager.printReceipt(
            context,
            ReceiptPrinterManager.ReceiptData(
                subtotalCents = order.subtotalCents, taxCents = order.taxCents, tipCents = order.tipCents,
                amountCents = order.amountCents, refNum = order.ecrRefNum, deviceSn = DeviceRepository.getPersistedDeviceSn() ?: "MOCK_SN",
                banner = "      *** REFUND ***",
                footer = listOf(how, "Approved by: ${approver.name}")
            ),
            vendor = DeviceRepository.getPersistedHardwareVendor()
        )

    /** [how] goes on the printed slip (English for now); [howRes] is what the screen says. */
    private fun finish(context: Context, order: OrderEntity, approver: StaffMember, status: String, how: String, howRes: Int, onState: (RefundState) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            TransactionRepository.recordRefund(context, order.ecrRefNum, status, approver.id, approver.name)
            printSlip(context, order, approver, how)
            withContext(Dispatchers.Main) { onState(RefundState.Done(context.getString(R.string.retail_refund_done, amount(order), context.getString(howRes)))) }
        }
    }
}

/** The choice when a card sale couldn't be reversed (usually: already settled). */
@Composable
fun RefundNeedsCardDialog(amountCents: Int, onCardTapped: () -> Unit, onRefundedInPortal: () -> Unit, onCancel: () -> Unit) {
    val amount = "%.2f".format(amountCents / 100.0)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.retail_refund_needs_the_card)) },
        text = {
            Text(stringResource(R.string.retail_refund_needs_card_body, amount))
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onCardTapped) { Text(stringResource(R.string.retail_customer_taps_card), fontWeight = FontWeight.Bold) }
                TextButton(onClick = onRefundedInPortal) { Text(stringResource(R.string.retail_already_refunded_in_portal)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.retail_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    )
}
