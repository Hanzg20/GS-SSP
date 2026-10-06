package com.goldsky.ssp.ui

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.common.QrUtils
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.core.ui.R
import com.goldsky.ssp.payment.CardTypeClassifier
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.PaymentProviderFactory
import com.goldsky.ssp.payment.TestSale
import com.goldsky.ssp.payment.TransactionRecord
import com.goldsky.ssp.payment.TransactionRepository
import com.goldsky.ssp.payment.VipLoadPlan
import com.goldsky.ssp.payment.VipLoadRepository
import com.goldsky.ssp.payment.VipLoadResult
import com.goldsky.ssp.payment.VipRepository
import com.goldsky.ssp.payment.hardware.DeclineReason
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import com.goldsky.ssp.payment.hardware.IScannerProvider
import kotlinx.coroutines.launch

/**
 * Buying a VIP card or topping one up at the terminal (2026-10-06).
 *
 * choose (new card / top up) -> phone (optional, new card) or member code
 * (scan or type, top-up) -> card payment -> server credits the card
 * (device_vip_load) -> result. The terminal never sets a balance itself.
 *
 * After the card payment is approved:
 *  - server credited it: show the card / new balance;
 *  - server said no (Rejected): nothing was credited, so the sale is
 *    reversed automatically, like a wash that fails to start;
 *  - no answer (Unreachable): the card may already be credited, so it is
 *    NOT reversed; the customer is sent to the attendant and a CRITICAL
 *    alert names the sale.
 *
 * A technician TestSale applies here too: the sale charges the test amount
 * and the server credits only that amount, no bonus.
 */
class VipPurchaseFlow(
    private val activity: AppCompatActivity,
    private val plan: VipLoadPlan,
    /** true while the flow is on screen -- the VIP page pauses its idle exit. */
    private val onActive: (Boolean) -> Unit,
) {
    private val dialog = Dialog(activity, R.style.Theme_SSP_Fullscreen)
    private val vendor = DeviceRepository.getPersistedHardwareVendor()
    private val deviceSn = DeviceRepository.getPersistedDeviceSn() ?: ""
    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { close() }
    private var scanner: IScannerProvider? = null

    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var qr: ImageView
    private lateinit var code: TextView
    private lateinit var input: EditText
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var cancel: Button

    fun start() {
        dialog.setContentView(R.layout.dialog_vip_purchase)
        title = dialog.findViewById(R.id.tv_vp_title)
        message = dialog.findViewById(R.id.tv_vp_message)
        qr = dialog.findViewById(R.id.img_vp_qr)
        code = dialog.findViewById(R.id.tv_vp_code)
        input = dialog.findViewById(R.id.et_vp_input)
        primary = dialog.findViewById(R.id.btn_vp_primary)
        secondary = dialog.findViewById(R.id.btn_vp_secondary)
        cancel = dialog.findViewById(R.id.btn_vp_cancel)
        dialog.setCancelable(false)
        dialog.setOnDismissListener { stopScan(); handler.removeCallbacks(autoClose); onActive(false) }
        onActive(true)
        dialog.show()
        showChoose()
    }

    // ---- steps ------------------------------------------------------------

    private fun showChoose() {
        screen(
            title = activity.getString(R.string.vip_buy_title, money(plan.amount_cents), money(plan.totalCents)),
            message = activity.getString(R.string.vip_buy_choose),
            primary = activity.getString(R.string.vip_buy_new) to { showPhone() },
            secondary = activity.getString(R.string.vip_buy_topup) to { showMemberCode() },
            cancellable = true,
        )
    }

    private fun showPhone() {
        screen(
            title = activity.getString(R.string.vip_phone_title),
            message = activity.getString(R.string.vip_phone_message),
            primary = activity.getString(R.string.vip_continue) to { pay(cardUid = null, phone = input.text.toString().trim().ifEmpty { null }) },
            secondary = activity.getString(R.string.vip_skip) to { pay(cardUid = null, phone = null) },
            cancellable = true,
        )
        input.apply {
            visibility = View.VISIBLE
            setText("")
            hint = "(343) 555-0123"
            inputType = InputType.TYPE_CLASS_PHONE
            filters = arrayOf(InputFilter.LengthFilter(20))
        }
    }

    private fun showMemberCode(error: String? = null) {
        screen(
            title = activity.getString(R.string.vip_code_title),
            message = error ?: activity.getString(R.string.vip_code_message),
            // The WizarPOS scanner opens its own full-screen camera page, so it
            // only starts when asked -- typing the code stays one tap away.
            primary = activity.getString(R.string.vip_scan_code) to { startScan() },
            secondary = activity.getString(R.string.vip_continue) to { lookUpCard(input.text.toString()) },
            cancellable = true,
        )
        input.apply {
            visibility = View.VISIBLE
            setText("")
            hint = "ABC123"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
        }
    }

    private fun lookUpCard(raw: String) {
        val memberCode = raw.trim().uppercase()
        if (!Regex("^[A-Z0-9]{6}$").matches(memberCode)) {
            showMemberCode(activity.getString(R.string.vip_code_invalid))
            return
        }
        stopScan()
        busy(activity.getString(R.string.vip_code_checking))
        activity.lifecycleScope.launch {
            val uid = VipRepository.resolveCardUidByQrCode(memberCode)
            val card = uid?.let { VipRepository.getVipCard(it) }
            when {
                uid == null || card == null -> showMemberCode(activity.getString(R.string.vip_code_unknown))
                !card.is_active -> showMemberCode(activity.getString(R.string.vip_code_inactive))
                else -> pay(cardUid = uid, phone = null, currentBalanceCents = card.balance_cents)
            }
        }
    }

    private fun pay(cardUid: String?, phone: String?, currentBalanceCents: Int? = null) {
        val testCents = TestSale.consume()
        val chargeCents = testCents ?: plan.amount_cents
        val ecrRefNum = (if (testCents != null) TestSale.REF_PREFIX else "VLOAD_") + System.currentTimeMillis()
        busy(
            (if (testCents != null) "TEST " else "") + activity.getString(R.string.vip_pay_title, money(chargeCents)),
            activity.getString(R.string.vip_pay_message) +
                (currentBalanceCents?.let { "\n" + activity.getString(R.string.vip_current_balance, money(it)) } ?: ""),
        )
        TtsManager.speak("Please present your card")
        val provider = PaymentProviderFactory.getPaymentProvider(activity, vendor)
        var cardInfo: IPaymentProvider.CardInfo? = null
        activity.lifecycleScope.launch {
            // PENDING before the bank call, same rule as every other sale.
            TransactionRepository.recordTransaction(
                activity,
                TransactionRecord(
                    device_sn = deviceSn, amount = chargeCents, payment_status = "PENDING",
                    ecr_ref_num = ecrRefNum, payment_method = "CREDIT_CARD",
                    vip_card_uid = cardUid, txn_kind = "VIP_LOAD",
                ),
            )
            provider.startSale(chargeCents, ecrRefNum, object : IPaymentProvider.PaymentCallback {
                override fun onCardInfo(info: IPaymentProvider.CardInfo) { cardInfo = info }

                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    activity.lifecycleScope.launch {
                        val card = cardInfo
                        TransactionRepository.updatePaymentStatus(
                            activity, ecrRefNum, "PAID", entryMode,
                            paymentMethod = card?.let { CardTypeClassifier.paymentMethod(it.scheme, it.aid) },
                            cardAid = card?.aid, cardBin = card?.bin, cardBrand = card?.brand,
                        )
                        busy(activity.getString(R.string.vip_loading))
                        credit(ecrRefNum, refNum, chargeCents, cardUid, phone)
                    }
                }

                override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                    activity.lifecycleScope.launch {
                        TransactionRepository.updatePaymentStatus(activity, ecrRefNum, "DECLINED")
                    }
                    if (isHardwareFault) DiagnosticManager.reportError(deviceSn, "CARD_READER_FAULT", severity = "CRITICAL", trace = errorMsg)
                    Log.w(TAG, "VIP load sale $ecrRefNum not completed: $errorMsg")
                    handler.post {
                        val text = activity.getString(
                            when (DeclineReason.classify(errorMsg, isHardwareFault)) {
                                DeclineReason.CANCELLED -> R.string.pay_result_cancelled
                                DeclineReason.UNAVAILABLE -> R.string.pay_result_unavailable
                                DeclineReason.DECLINED -> R.string.pay_result_declined
                            }
                        )
                        result(activity.getString(R.string.vip_not_completed), text, null)
                    }
                }

                override fun onProgress(message: String) {
                    if (message.isNotBlank()) handler.post { this@VipPurchaseFlow.message.text = message }
                }
            })
        }
    }

    private suspend fun credit(ecrRefNum: String, bankRef: String, chargeCents: Int, cardUid: String?, phone: String?) {
        when (val r = VipLoadRepository.load(ecrRefNum, plan.id, cardUid, phone)) {
            is VipLoadResult.Loaded -> {
                TransactionRepository.updateHardwareStatus(activity, ecrRefNum, "ACK_RECEIVED")
                if (r.created) {
                    TtsManager.speak("Your VIP card is ready. Please take a photo of your member code.")
                    result(
                        activity.getString(R.string.vip_done_new_title),
                        activity.getString(R.string.vip_done_new_message, money(r.balanceCents)),
                        r.qrCode,
                    )
                } else {
                    TtsManager.speak("Your balance has been topped up.")
                    result(activity.getString(R.string.vip_done_topup_title), activity.getString(R.string.vip_done_topup_message, money(r.balanceCents)), null)
                }
            }
            is VipLoadResult.Rejected -> {
                Log.e(TAG, "VIP load $ecrRefNum rejected: ${r.reason}; reversing")
                DiagnosticManager.reportError(deviceSn, "VIP_LOAD_REJECTED", severity = "WARNING", trace = "$ecrRefNum ${r.reason}")
                TransactionRepository.updateHardwareStatus(activity, ecrRefNum, "HARDWARE_ERROR")
                busy(activity.getString(R.string.vip_reversing))
                PaymentProviderFactory.getPaymentProvider(activity, vendor).voidOrRefund(bankRef, chargeCents) { ok, method ->
                    activity.lifecycleScope.launch {
                        if (ok) {
                            TransactionRepository.updatePaymentStatus(activity, ecrRefNum, if (method == "REFUND") "REFUNDED" else "VOIDED")
                            result(activity.getString(R.string.vip_not_completed), activity.getString(R.string.vip_reversed), null)
                        } else {
                            DiagnosticManager.reportError(deviceSn, "VOID_AND_REFUND_FAILED", severity = "CRITICAL", trace = ecrRefNum)
                            result(activity.getString(R.string.vip_not_completed), activity.getString(R.string.vip_see_attendant, ecrRefNum.takeLast(6)), null)
                        }
                    }
                }
            }
            is VipLoadResult.Unreachable -> {
                Log.e(TAG, "VIP load $ecrRefNum unconfirmed: ${r.error}")
                DiagnosticManager.reportError(
                    deviceSn, "VIP_LOAD_UNCONFIRMED", severity = "CRITICAL",
                    trace = "$ecrRefNum paid ${chargeCents}c, card ${cardUid ?: "new"}: no answer from server (${r.error}) -- check vip_card_ledger source_ref",
                )
                result(activity.getString(R.string.vip_unconfirmed_title), activity.getString(R.string.vip_see_attendant, ecrRefNum.takeLast(6)), null)
            }
        }
    }

    // ---- screen helpers -----------------------------------------------------

    private fun screen(
        title: String,
        message: String,
        primary: Pair<String, () -> Unit>?,
        secondary: Pair<String, () -> Unit>?,
        cancellable: Boolean,
    ) {
        handler.removeCallbacks(autoClose)
        this.title.text = title
        this.message.text = message
        qr.visibility = View.GONE
        code.visibility = View.GONE
        input.visibility = View.GONE
        bind(this.primary, primary)
        bind(this.secondary, secondary)
        cancel.visibility = if (cancellable) View.VISIBLE else View.GONE
        cancel.setOnClickListener { close() }
        // Unattended: a customer who walks away mid-choice is backed out.
        if (cancellable) handler.postDelayed(autoClose, IDLE_MS)
    }

    private fun bind(button: Button, action: Pair<String, () -> Unit>?) {
        if (action == null) {
            button.visibility = View.GONE
        } else {
            button.visibility = View.VISIBLE
            button.text = action.first
            button.setOnClickListener { action.second() }
        }
    }

    /** Waiting on the reader or the server: no way out mid-sale. */
    private fun busy(title: String, message: String = "") = screen(title, message, null, null, cancellable = false)

    private fun result(title: String, message: String, memberCode: String?) {
        screen(title, message, activity.getString(R.string.vip_done) to { close() }, null, cancellable = false)
        if (memberCode != null) {
            QrUtils.generateQrCode(memberCode, 340, 340)?.let { qr.setImageBitmap(it); qr.visibility = View.VISIBLE }
            code.text = memberCode
            code.visibility = View.VISIBLE
        }
        handler.postDelayed(autoClose, if (memberCode != null) RESULT_WITH_CODE_MS else RESULT_MS)
    }

    private val scanTimeout = Runnable { stopScan() }

    private fun startScan() {
        stopScan()
        val s = runCatching { HardwareFactory.getScannerProvider(activity, vendor) }.getOrNull() ?: return
        scanner = s
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        s.startScan(object : IScannerProvider.ScanCallback {
            override fun onScanSuccess(result: String) {
                handler.post { if (scanner != null) { input.setText(result.trim().take(6)); lookUpCard(result) } }
            }
            override fun onScanFailure(errorMsg: String) { Log.d(TAG, "Member code scan: $errorMsg") }
        })
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scanner?.let { runCatching { it.stopScan() } }
        scanner = null
    }

    private fun close() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun money(cents: Int) = VipLoadPlan.money(cents)

    private companion object {
        const val TAG = "VipPurchaseFlow"
        const val IDLE_MS = 60_000L
        const val SCAN_TIMEOUT_MS = 30_000L
        const val RESULT_MS = 20_000L
        /** Long enough to photograph the member code. */
        const val RESULT_WITH_CODE_MS = 120_000L
    }
}
