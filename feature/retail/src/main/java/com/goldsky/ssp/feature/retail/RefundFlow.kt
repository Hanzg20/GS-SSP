package com.goldsky.ssp.feature.retail

import android.content.Context
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
        if (order.paymentMethod == "CASH") {
            finish(context, order, approver, "REFUNDED", "Cash handed back: $${amount(order)}", onState)
            return
        }
        onState(RefundState.Processing("Reversing $${amount(order)}..."))
        val ref = order.ecrRefNum
        provider.queryTransaction(ref) { _ ->
            provider.voidTransaction(ref, object : IPaymentProvider.PaymentCallback {
                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    main.post { finish(context, order, approver, "VOIDED", "Card sale reversed", onState) }
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
        onState(RefundState.Processing("Customer: tap or insert the card used for this sale"))
        provider.refundTransaction(order.ecrRefNum, order.amountCents, object : IPaymentProvider.PaymentCallback {
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                main.post { finish(context, order, approver, "REFUNDED", "Refunded to card", onState) }
            }
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                main.post { onState(RefundState.Failed("Refund not done: $errorMsg")) }
            }
            override fun onProgress(message: String) {}
        })
    }

    /** Refunded in the WizarPOS merchant portal: record it, no PAYWizard call. */
    fun refundedInPortal(context: Context, order: OrderEntity, approver: StaffMember, onState: (RefundState) -> Unit) =
        finish(context, order, approver, "REFUNDED", "Refunded in WizarPOS merchant portal", onState)

    private fun finish(context: Context, order: OrderEntity, approver: StaffMember, status: String, how: String, onState: (RefundState) -> Unit) {
        val ref = order.ecrRefNum
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "MOCK_SN"
        CoroutineScope(Dispatchers.IO).launch {
            TransactionRepository.recordRefund(context, ref, status, approver.id, approver.name)
            ReceiptPrinterManager.printReceipt(
                context,
                ReceiptPrinterManager.ReceiptData(
                    subtotalCents = order.subtotalCents, taxCents = order.taxCents, tipCents = order.tipCents,
                    amountCents = order.amountCents, refNum = ref, deviceSn = sn,
                    banner = "      *** REFUND ***",
                    footer = listOf(how, "Approved by: ${approver.name}")
                ),
                vendor = DeviceRepository.getPersistedHardwareVendor()
            )
            withContext(Dispatchers.Main) { onState(RefundState.Done("Refunded $${amount(order)}. $how")) }
        }
    }
}

/** The choice when a card sale couldn't be reversed (usually: already settled). */
@Composable
fun RefundNeedsCardDialog(amountCents: Int, onCardTapped: () -> Unit, onRefundedInPortal: () -> Unit, onCancel: () -> Unit) {
    val amount = "%.2f".format(amountCents / 100.0)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Refund needs the card") },
        text = {
            Text(
                "This sale couldn't be reversed -- it has most likely been settled already. " +
                    "A refund of $$amount from this terminal needs the customer's card.\n\n" +
                    "If the customer isn't here, refund it in the WizarPOS merchant portal (no card needed), then record it here."
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onCardTapped) { Text("CUSTOMER TAPS CARD", fontWeight = FontWeight.Bold) }
                TextButton(onClick = onRefundedInPortal) { Text("ALREADY REFUNDED IN PORTAL") }
                TextButton(onClick = onCancel) { Text("CANCEL", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    )
}
