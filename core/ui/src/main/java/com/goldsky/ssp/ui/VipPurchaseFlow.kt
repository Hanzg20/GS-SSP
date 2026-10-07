package com.goldsky.ssp.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.goldsky.ssp.common.QrUtils
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.core.ui.R
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import com.goldsky.ssp.payment.CardTypeClassifier
import com.goldsky.ssp.payment.ConfigManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.DiagnosticManager
import com.goldsky.ssp.payment.PaymentProviderFactory
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
 *  - server credited it: show the customer's digital pass / new balance;
 *  - server said no (Rejected): nothing was credited, so the sale is
 *    reversed automatically, like a wash that fails to start;
 *  - no answer (Unreachable): the card may already be credited, so it is
 *    NOT reversed; the customer is sent to the attendant and a CRITICAL
 *    alert names the sale.
 *
 * A technician TestSale applies here too: the sale charges the test amount
 * and the server credits only that amount, no bonus.
 *
 * UI: one panel with a 3-step bar (choose -> details -> pay), choice cards,
 * an on-screen phone keypad (no system keyboard over the kiosk), an
 * Apple-Pay-style payment step, and the new card shown as a digital pass.
 */
class VipPurchaseFlow(
    private val activity: AppCompatActivity,
    private val plan: VipLoadPlan,
    private val brandName: String,
    /** true while the flow is on screen -- the VIP page pauses its idle exit. */
    private val onActive: (Boolean) -> Unit,
) {
    private val dialog = Dialog(activity, R.style.Theme_SSP_Fullscreen)
    private val vendor = DeviceRepository.getPersistedHardwareVendor()
    private val deviceSn = DeviceRepository.getPersistedDeviceSn() ?: ""
    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { close() }
    private val scanTimeout = Runnable { stopScan() }
    private var scanner: IScannerProvider? = null
    private var ripple: AnimatorSet? = null
    private var phoneDigits = ""

    private fun <T : View> v(id: Int): T = dialog.findViewById(id)
    private val title by lazy { v<TextView>(R.id.tv_vp_title) }
    private val message by lazy { v<TextView>(R.id.tv_vp_message) }
    private val stepLabel by lazy { v<TextView>(R.id.tv_vp_step) }
    private val steps by lazy { listOf(v<View>(R.id.vp_step1), v<View>(R.id.vp_step2), v<View>(R.id.vp_step3)) }
    private val options by lazy { v<View>(R.id.layout_vp_options) }
    private val input by lazy { v<EditText>(R.id.et_vp_input) }
    private val keypad by lazy { v<ViewGroup>(R.id.layout_vp_keypad) }
    private val payBlock by lazy { v<LinearLayout>(R.id.layout_vp_pay) }
    private val amount by lazy { v<TextView>(R.id.tv_vp_amount) }
    private val progress by lazy { v<View>(R.id.pb_vp) }
    private val icon by lazy { v<ImageView>(R.id.img_vp_icon) }
    private val pass by lazy { v<VipCardView>(R.id.vp_pass) }
    private val primary by lazy { v<Button>(R.id.btn_vp_primary) }
    private val secondary by lazy { v<Button>(R.id.btn_vp_secondary) }
    private val closeButton by lazy { v<View>(R.id.btn_vp_cancel) }

    fun start() {
        dialog.setContentView(R.layout.dialog_vip_purchase)
        dialog.setCancelable(false)
        dialog.setOnDismissListener {
            stopScan(); stopRipple(); handler.removeCallbacksAndMessages(null); onActive(false)
        }
        v<View>(R.id.opt_vp_new).setOnClickListener { showPhone() }
        v<View>(R.id.opt_vp_topup).setOnClickListener { showMemberCode() }
        closeButton.setOnClickListener { close() }
        wireKeypad()
        onActive(true)
        dialog.show()
        showChoose()
    }

    // ---- steps ------------------------------------------------------------

    private fun showChoose() {
        screen(Step.CHOOSE, activity.getString(R.string.vip_buy_title, money(plan.amount_cents), money(plan.totalCents)),
            activity.getString(R.string.vip_buy_choose))
        options.visibility = View.VISIBLE
    }

    private fun showPhone() {
        screen(
            Step.DETAILS, activity.getString(R.string.vip_phone_title), activity.getString(R.string.vip_phone_message),
            primary = activity.getString(R.string.vip_continue) to { pay(cardUid = null, phone = phoneDigits.ifEmpty { null }) },
            secondary = activity.getString(R.string.vip_skip) to { pay(cardUid = null, phone = null) },
        )
        phoneDigits = ""
        input.apply {
            visibility = View.VISIBLE
            // Display only: the on-screen keypad types into it.
            isFocusable = false
            showSoftInputOnFocus = false
            inputType = InputType.TYPE_NULL
            filters = arrayOf()
            hint = "(343) 555-0123"
            setText("")
        }
        keypad.visibility = View.VISIBLE
    }

    private fun showMemberCode(error: String? = null) {
        screen(
            Step.DETAILS, activity.getString(R.string.vip_code_title), error ?: activity.getString(R.string.vip_code_message),
            // The WizarPOS scanner opens its own full-screen camera page, so it
            // only starts when asked -- typing the code stays one tap away.
            primary = activity.getString(R.string.vip_continue) to { lookUpCard(input.text.toString()) },
            secondary = activity.getString(R.string.vip_scan_code) to { startScan() },
            errorTone = error != null,
        )
        input.apply {
            visibility = View.VISIBLE
            isFocusable = true
            isFocusableInTouchMode = true
            showSoftInputOnFocus = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
            hint = "ABC123"
            setText("")
        }
    }

    private fun lookUpCard(raw: String) {
        val memberCode = raw.trim().uppercase()
        if (!Regex("^[A-Z0-9]{6}$").matches(memberCode)) {
            showMemberCode(activity.getString(R.string.vip_code_invalid))
            return
        }
        stopScan()
        busy(Step.DETAILS, activity.getString(R.string.vip_code_checking))
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
        // Never the technician test amount: a $0.10 test must not turn into
        // VIP balance (server refuses TEST_ loads too). An armed test stays
        // armed for the next service sale.
        val chargeCents = plan.amount_cents
        val ecrRefNum = "VLOAD_" + System.currentTimeMillis()
        screen(
            Step.PAY, activity.getString(R.string.vip_pay_heading),
            activity.getString(R.string.vip_pay_message) +
                (currentBalanceCents?.let { "\n" + activity.getString(R.string.vip_current_balance, money(it)) } ?: ""),
            closable = false,
        )
        amount.text = money(chargeCents)
        payBlock.visibility = View.VISIBLE
        startRipple()
        TtsManager.speak("Please present your card")
        val provider = PaymentProviderFactory.getPaymentProvider(activity, vendor)
        var cardInfo: IPaymentProvider.CardInfo? = null
        var cancelled = false
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
                override fun onCancelled() { cancelled = true }

                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    activity.lifecycleScope.launch {
                        val card = cardInfo
                        TransactionRepository.updatePaymentStatus(
                            activity, ecrRefNum, "PAID", entryMode,
                            paymentMethod = card?.let { CardTypeClassifier.paymentMethod(it.scheme, it.aid, it.brand) },
                            cardAid = card?.aid, cardBin = card?.bin, cardBrand = card?.brand,
                            authCode = authCode,
                        )
                        busy(Step.PAY, activity.getString(R.string.vip_loading))
                        credit(ecrRefNum, refNum, chargeCents, cardUid, phone)
                    }
                }

                override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                    activity.lifecycleScope.launch {
                        TransactionRepository.recordFailedCardSale(activity, ecrRefNum, cardInfo, cancelled)
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
                        failure(activity.getString(R.string.vip_not_completed), text)
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
                if (r.created) newCard(r) else toppedUp(r)
            }
            is VipLoadResult.Rejected -> {
                Log.e(TAG, "VIP load $ecrRefNum rejected: ${r.reason}; reversing")
                DiagnosticManager.reportError(deviceSn, "VIP_LOAD_REJECTED", severity = "WARNING", trace = "$ecrRefNum ${r.reason}")
                TransactionRepository.updateHardwareStatus(activity, ecrRefNum, "HARDWARE_ERROR")
                busy(Step.PAY, activity.getString(R.string.vip_reversing))
                PaymentProviderFactory.getPaymentProvider(activity, vendor).voidOrRefund(bankRef, chargeCents) { ok, method ->
                    activity.lifecycleScope.launch {
                        if (ok) {
                            TransactionRepository.updatePaymentStatus(activity, ecrRefNum, if (method == "REFUND") "REFUNDED" else "VOIDED")
                            failure(activity.getString(R.string.vip_not_completed), activity.getString(R.string.vip_reversed))
                        } else {
                            DiagnosticManager.reportError(deviceSn, "VOID_AND_REFUND_FAILED", severity = "CRITICAL", trace = ecrRefNum)
                            failure(activity.getString(R.string.vip_not_completed), activity.getString(R.string.vip_see_attendant, ecrRefNum.takeLast(6)))
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
                failure(activity.getString(R.string.vip_unconfirmed_title), activity.getString(R.string.vip_see_attendant, ecrRefNum.takeLast(6)))
            }
        }
    }

    private fun newCard(r: VipLoadResult.Loaded) {
        TtsManager.speak("Your VIP card is ready. Please take a photo of your member code.")
        // No message line: the room goes to the card, the photo hint to the step label.
        screen(Step.DONE, activity.getString(R.string.vip_done_new_title), "",
            primary = activity.getString(R.string.vip_done) to { close() }, closable = false)
        stepLabel.text = activity.getString(R.string.vip_step_photo)
        // The card itself says it all; its height goes to the QR code.
        title.visibility = View.GONE
        pass.apply {
            mode = VipCardView.Mode.PASS
            brandName = this@VipPurchaseFlow.brandName
            memberCode = r.qrCode ?: ""
            balanceText = money(r.balanceCents)
            qrBitmap = r.qrCode?.let { QrUtils.generateQrCode(it, 360, 360, forLogo = true) }
            visibility = View.VISIBLE
        }
        loadLogo()
        handler.postDelayed(autoClose, RESULT_WITH_CODE_MS)
    }

    private fun toppedUp(r: VipLoadResult.Loaded) {
        TtsManager.speak("Your balance has been topped up.")
        screen(Step.DONE, activity.getString(R.string.vip_done_topup_title), activity.getString(R.string.vip_done_topup_hint),
            primary = activity.getString(R.string.vip_done) to { close() }, closable = false)
        icon.setImageResource(R.drawable.ic_check_circle)
        icon.visibility = View.VISIBLE
        // The payment block's amount line, without the ripple / secure line.
        amount.text = money(r.balanceCents)
        payBlock.visibility = View.VISIBLE
        payExtras(false)
        handler.postDelayed(autoClose, RESULT_MS)
    }

    /** Merchant logo (CMP branding) for the centre of the pass QR; the pass shows without it if it can't load. */
    private fun loadLogo() {
        val url = ConfigManager.getConfig()?.branding?.logo_url?.takeIf { it.isNotBlank() } ?: return
        activity.lifecycleScope.launch {
            val result = runCatching {
                activity.imageLoader.execute(ImageRequest.Builder(activity).data(url).allowHardware(false).build())
            }.getOrNull()
            val bmp = (result?.drawable as? BitmapDrawable)?.bitmap
            if (bmp != null) pass.logo = bmp else Log.w(TAG, "Merchant logo not loaded for the pass: $url")
        }
    }

    private fun failure(heading: String, text: String) {
        screen(Step.DONE, heading, text, primary = activity.getString(R.string.vip_done) to { close() }, closable = false, errorTone = true)
        icon.setImageResource(R.drawable.ic_error_circle)
        icon.visibility = View.VISIBLE
        handler.postDelayed(autoClose, RESULT_MS)
    }

    // ---- screen helpers -----------------------------------------------------

    private enum class Step { CHOOSE, DETAILS, PAY, DONE }

    private fun screen(
        step: Step,
        heading: String,
        text: String,
        primary: Pair<String, () -> Unit>? = null,
        secondary: Pair<String, () -> Unit>? = null,
        closable: Boolean = true,
        errorTone: Boolean = false,
    ) {
        handler.removeCallbacks(autoClose)
        stopRipple()
        title.text = heading
        title.visibility = View.VISIBLE
        message.text = text
        message.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        message.setTextColor(activity.getColor(if (errorTone) R.color.coral_red else R.color.text_muted))
        stepLabel.text = activity.getString(
            when (step) {
                Step.CHOOSE -> R.string.vip_step_choose
                Step.DETAILS -> R.string.vip_step_details
                Step.PAY -> R.string.vip_step_pay
                Step.DONE -> R.string.vip_step_done
            }
        )
        val reached = when (step) { Step.CHOOSE -> 1; Step.DETAILS -> 2; else -> 3 }
        steps.forEachIndexed { i, bar -> bar.setBackgroundResource(if (i < reached) R.drawable.bg_vip_step_on else R.drawable.bg_vip_step_off) }
        for (view in listOf(options, input, keypad, payBlock, progress, icon, pass)) view.visibility = View.GONE
        payExtras(true)
        bind(this.primary, primary)
        bind(this.secondary, secondary)
        closeButton.visibility = if (closable) View.VISIBLE else View.INVISIBLE
        // Unattended: a customer who walks away mid-choice is backed out.
        if (closable) handler.postDelayed(autoClose, IDLE_MS)
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
    private fun busy(step: Step, heading: String) {
        screen(step, heading, "", closable = false)
        progress.visibility = View.VISIBLE
    }

    /** Everything in the payment block after the amount line (ripple, "secure" line). */
    private fun payExtras(show: Boolean) {
        for (i in 1 until payBlock.childCount) payBlock.getChildAt(i).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun wireKeypad() {
        fun walk(g: ViewGroup) {
            for (i in 0 until g.childCount) {
                when (val c = g.getChildAt(i)) {
                    is ViewGroup -> walk(c)
                    is TextView -> if (c.isClickable) c.setOnClickListener { key(c) }
                }
            }
        }
        walk(keypad)
    }

    private fun key(k: TextView) {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, IDLE_MS)
        phoneDigits = when {
            k.tag == "del" -> phoneDigits.dropLast(1)
            phoneDigits.length < 15 -> phoneDigits + k.text
            else -> phoneDigits
        }
        input.setText(formatPhone(phoneDigits))
    }

    private fun formatPhone(d: String): String = when {
        d.length <= 3 -> d
        d.length <= 6 -> "(${d.take(3)}) ${d.drop(3)}"
        d.length <= 10 -> "(${d.take(3)}) ${d.substring(3, 6)}-${d.drop(6)}"
        else -> d
    }

    private fun startRipple() {
        stopRipple()
        fun pulse(view: View, delay: Long) = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.45f, 1f).apply { repeatCount = ValueAnimator.INFINITE },
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.45f, 1f).apply { repeatCount = ValueAnimator.INFINITE },
                ObjectAnimator.ofFloat(view, View.ALPHA, 0.9f, 0f).apply { repeatCount = ValueAnimator.INFINITE },
            )
            duration = 1600
            startDelay = delay
        }
        ripple = AnimatorSet().apply { playTogether(pulse(v(R.id.vp_ripple1), 0), pulse(v(R.id.vp_ripple2), 800)); start() }
    }

    private fun stopRipple() {
        ripple?.cancel()
        ripple = null
    }

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
