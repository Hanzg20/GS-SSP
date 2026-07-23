package com.goldsky.carwash

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.airbnb.lottie.LottieAnimationView
import com.goldsky.carwash.model.PaymentMethodMode
import com.goldsky.carwash.model.Product
import com.goldsky.carwash.model.WashPackage
import com.goldsky.carwash.payment.*
import com.goldsky.carwash.serial.SerialPortManager
import com.pax.dal.IDAL
import com.pax.neptunelite.api.NeptuneLiteUser
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull


/**
 * Main Controller Activity. Displays service options, manages payment modals,
 * operates serial communication to trigger hardware relalys.
 */
class MainActivity : BaseAdActivity() {

    private lateinit var layoutPackageSelection: ConstraintLayout
    private lateinit var layoutWorking: ConstraintLayout

    // State Variables
    private var isWorking = false
    private var isSimulationMode = true // Set to false for real PAX hardware integration
    private var paymentDialog: Dialog? = null
    private var pollingJob: Job? = null
    private var scannerManager: PaxScannerManager? = null
    private var deviceSn: String = "SIMULATOR_SN"
    private var deviceControl: com.pax.dal.IDeviceControl? = null
    private var watchdogJob: Job? = null

    // True from the moment money-movement is initiated (card SALE sent to
    // POSLink, or a VIP balance deduction in flight) until it's fully
    // resolved. Gates the payment dialog's back/cancel buttons and its
    // timeout auto-dismiss, since dismissing mid-flight would desync the UI
    // from a bank transaction that's still actually in progress server-side.
    @Volatile private var paymentInFlight = false

    // Technician/Maintenance Variables
    private var logoClickCount = 0
    private var lastClickTime = 0L
    private var isHighBrightness = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setupCrashHandler()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        TtsManager.registerLifecycle(this, this) // Register dynamic voice engine

        // Init views
        layoutPackageSelection = findViewById(R.id.layout_package_selection)
        layoutWorking = findViewById(R.id.layout_working)

        scannerManager = PaxScannerManager(this)

        DeviceRepository.init(this)
        DeviceAccessManager.init(this)

        setupClickListeners()
        setupMaintenanceTrigger()

        extractDeviceIdentity()
        initHardwareControl()
        AdManager.init(this)

        RemoteCommandManager.startListening(this, deviceSn) {
            loadInitialConfig(com.goldsky.carwash.payment.DeviceRepository.getPersistedOrgId()) 
        }

        ShadowManager.startSync(this, deviceSn)

        if (!isSimulationMode) {
            SerialPortManager.openPort(this)
        }
        
        performHealthCheck()
        startWatchdog()

        // Update version display
        findViewById<TextView>(R.id.tv_app_version)?.text = "v${BuildConfig.VERSION_NAME}"
    }

    private fun initHardwareControl() {
        if (!isSimulationMode) {
            try {
                val dal: IDAL = NeptuneLiteUser.getInstance().getDal(this)
                deviceControl = dal.deviceControl
            } catch (e: Exception) {
                Log.e("SSP_HW", "Failed to init device control: ${e.message}")
            }
        }
    }

    /**
     * Extracts hardware serial number and registers device with the cloud.
     */
    private fun extractDeviceIdentity() {
        if (isSimulationMode) {
            deviceSn = "MOCK_SN_${System.currentTimeMillis()}"
            Log.i("SSP_IDENTITY", "Simulated SN: $deviceSn")
        } else {
            try {
                val dal: IDAL = NeptuneLiteUser.getInstance().getDal(this)
                deviceSn = dal.getSys().getTermSerial() ?: "UNKNOWN_SN"
                Log.i("SSP_IDENTITY", "Hardware SN: $deviceSn")
            } catch (e: Exception) {
                Log.e("SSP_IDENTITY", "Failed to get hardware SN: ${e.message}")
            }
        }

        // Persist so background components without DAL access (HeartbeatWorker)
        // can still identify the device instead of using a placeholder.
        com.goldsky.carwash.payment.DeviceRepository.persistDeviceSn(deviceSn)

        // Offline-first: load immediately using whatever org_id was cached
        // from a previous successful identity sync (null on first-ever
        // launch), so the UI is populated from cache/assets right away
        // rather than blocking on the network calls below.
        loadInitialConfig(com.goldsky.carwash.payment.DeviceRepository.getPersistedOrgId())

        // Async: authenticate both Supabase clients, register the device,
        // link its auth session (device_auth_map) to learn/refresh its
        // org_id, then reload config now that the org is freshly known --
        // covers first-ever launch (no cached org_id yet) and an org
        // reassignment happening server-side between launches.
        CoroutineScope(Dispatchers.Main).launch {
            SupabaseClientProvider.ensureAuthenticated()
            com.goldsky.carwash.payment.DeviceRepository.registerDevice(deviceSn, BuildConfig.VERSION_NAME)
            val identity = com.goldsky.carwash.payment.DeviceRepository.syncDeviceIdentity(deviceSn)
            DeviceAccessManager.applyActiveState(identity?.is_active)
            performHealthCheck()
            if (identity?.org_id != null) {
                loadInitialConfig(identity.org_id)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyKioskWindowFlags()
        resetAdTimer()
        // Enable scanner LED for voucher scan on main menu
        scannerManager?.setScannerLed(true)
        performHealthCheck()
    }

    override fun onPause() {
        super.onPause()
        stopAdTimer()
        // Disable scanner LED when leaving main menu
        scannerManager?.setScannerLed(false)
    }

    private fun setupClickListeners() {
        // Long press logo to toggle Simulation Mode (for demo purposes)
        findViewById<View>(R.id.img_logo).setOnLongClickListener {
            isSimulationMode = !isSimulationMode
            val modeMsg = if (isSimulationMode) getString(R.string.toast_mode_demo) else getString(R.string.toast_mode_prod)
            Toast.makeText(this, modeMsg, Toast.LENGTH_SHORT).show()
            performHealthCheck()
            true
        }

        findViewById<View>(R.id.card_custom).setOnClickListener {
            showCustomAmountDialog()
        }

        findViewById<View>(R.id.layout_vip_banner).setOnClickListener {
            startActivity(Intent(this, VipActivity::class.java))
        }
    }

    /**
     * (Re)loads config, scoped to [orgId] on the cloud tier when known.
     */
    private fun loadInitialConfig(orgId: String?) {
        CoroutineScope(Dispatchers.Main).launch {
            val config = ConfigManager.loadConfig(this@MainActivity, orgId)
            refreshProductsUI(config.products)
            
            // Sync dynamic TTS language
            TtsManager.setLocale(config.settings.locale_tag)
            
            // Apply tenant branding
            BrandingManager.applyLogo(findViewById(R.id.img_logo), config.branding)
        }
    }

    /**
     * Dynamically binds products to the UI cards.
     */
    private fun refreshProductsUI(products: List<Product>) {
        val cards = listOf(
            Triple(R.id.card_standard, R.id.tv_price1, R.id.tv_label1),
            Triple(R.id.card_delux, R.id.tv_price2, R.id.tv_label2),
            Triple(R.id.card_wax, R.id.tv_price3, R.id.tv_label3)
        )

        products.take(3).forEachIndexed { index, product ->
            val (cardId, priceId, labelId) = cards[index]
            val cardView = findViewById<View>(cardId)
            val priceView = findViewById<TextView>(priceId)
            val labelView = findViewById<TextView>(labelId)

            cardView.visibility = View.VISIBLE
            priceView.text = "$${product.price_cents / 100}"
            labelView.text = product.name
            
            cardView.setOnClickListener {
                // Extract serial hex from generic attributes
                val serialHex = product.attributes?.get("serial_hex")?.jsonPrimitive?.contentOrNull 
                    ?: "AA000055"
                showPaymentDialog(product.price_cents, serialHex)
            }
        }

        // Hide unused cards
        if (products.size < 3) {
            for (i in products.size until 3) {
                findViewById<View>(cards[i].first).visibility = View.GONE
            }
        }
    }

    private fun showCustomAmountDialog() {
        stopAdTimer()
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_custom_amount)
        
        var amount = 10
        val tvAmount = dialog.findViewById<TextView>(R.id.tv_amount_display)
        
        dialog.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            if (amount < 50) {
                amount += 2
                tvAmount.text = "$$amount"
            }
        }
        
        dialog.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            if (amount > 2) {
                amount -= 2
                tvAmount.text = "$$amount"
            }
        }
        
        dialog.findViewById<Button>(R.id.btn_confirm_custom).setOnClickListener {
            dialog.dismiss()
            val hex = "AA 01 ${"%02X".format(amount)} 55"
            showPaymentDialog(amount * 100, hex)
        }
        
        dialog.findViewById<Button>(R.id.btn_cancel_custom).setOnClickListener {
            dialog.dismiss()
            resetAdTimer()
        }
        
        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.show()
    }

    /**
     * Overrides parent check to prevent launching AdActivity while wash is active.
     */
    override fun isCarWashSessionActive(): Boolean {
        return isWorking || (paymentDialog != null && paymentDialog!!.isShowing)
    }

    /**
     * Entry point for payment. Shows the card-vs-scan selection dialog only
     * when both methods are enabled (KioskSettings.payment_method_mode == ALL);
     * when the operator has restricted the terminal to a single method, the
     * selection page is skipped entirely and the terminal goes straight into
     * that flow.
     */
    private fun showPaymentDialog(priceInCents: Int, startHex: String) {
        if (DeviceAccessManager.isLocked()) {
            Toast.makeText(this, "Terminal locked: ${DeviceAccessManager.lockReason()}", Toast.LENGTH_LONG).show()
            resetAdTimer()
            return
        }

        when (ConfigManager.getConfig()?.settings?.payment_method_mode ?: PaymentMethodMode.ALL) {
            PaymentMethodMode.CARD_ONLY -> {
                stopAdTimer()
                startPaymentFlow(true, priceInCents, startHex)
                return
            }
            PaymentMethodMode.SCAN_ONLY -> {
                stopAdTimer()
                startPaymentFlow(false, priceInCents, startHex)
                return
            }
            // Any other value (including ALL) falls through to the selection
            // dialog below -- an invalid config must never silently disable
            // every payment method.
        }

        stopAdTimer()

        val selectionDialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        selectionDialog.setContentView(R.layout.dialog_payment_selection)
        
        selectionDialog.findViewById<View>(R.id.btn_choice_card).setOnClickListener {
            selectionDialog.dismiss()
            startPaymentFlow(true, priceInCents, startHex)
        }
        
        selectionDialog.findViewById<View>(R.id.btn_choice_scan).setOnClickListener {
            selectionDialog.dismiss()
            startPaymentFlow(false, priceInCents, startHex)
        }
        
        selectionDialog.findViewById<Button>(R.id.btn_cancel_choice).setOnClickListener {
            selectionDialog.dismiss()
            resetAdTimer()
        }
        
        selectionDialog.setOnShowListener { applyKioskWindowFlags() }
        selectionDialog.show()
    }

    private fun startPaymentFlow(isCard: Boolean, priceInCents: Int, startHex: String) {
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_payment)
        paymentDialog = dialog

        val layoutCard = dialog.findViewById<ConstraintLayout>(R.id.layout_card_guidance)
        val layoutQr = dialog.findViewById<ConstraintLayout>(R.id.layout_qr_guidance)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.shared_subtitle)
        val pbTimeout = dialog.findViewById<ProgressBar>(R.id.pb_pay_timeout)
        
        tvSubtitle.text = getString(R.string.prompt_pay_subtitle, "$${priceInCents / 100}")

        if (isCard) {
            layoutCard.visibility = View.VISIBLE
            layoutQr.visibility = View.GONE
            startTapCardAnimation(dialog)
            
            // DYNAMIC VOICE: Localized amount and instruction
            TtsManager.announceAmount(priceInCents, "Total amount is")
            TtsManager.speak(getString(R.string.prompt_card_guide))

            // Start background NFC detection to distinguish between EMV and VIP
            scannerManager?.startCardDetection(object : PaxScannerManager.CardCallback {
                override fun onCardDetected(type: String, uid: String) {
                    if (type == "MIFARE") {
                        // VIP Membership detected
                        initVipPayment(uid, priceInCents, startHex, dialog)
                    } else {
                        // Payment Card (EMV) detected - Hand over to POSLink
                        // Critical: We must close the low-level PICC before POSLink takes over
                        scannerManager?.stopCardDetection()
                        initCardPayment(priceInCents, startHex, dialog)
                    }
                }
                override fun onDetectionError(error: String) {
                    Log.e("MainActivity", "NFC detection error: $error")
                }
            })
        } else {
            layoutCard.visibility = View.GONE
            layoutQr.visibility = View.VISIBLE
            initQrPayment(priceInCents, startHex, dialog)
        }

        dialog.findViewById<View>(R.id.btn_back_pay)?.setOnClickListener {
            if (paymentInFlight) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_payment_processing_wait), Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                showPaymentDialog(priceInCents, startHex)
            }
        }
        dialog.findViewById<View>(R.id.btn_back_qr)?.setOnClickListener {
            dialog.dismiss()
            showPaymentDialog(priceInCents, startHex)
        }

        val dialogTimer = object : CountDownTimer(60000, 100) {
            override fun onTick(millisUntilFinished: Long) {
                pbTimeout.progress = (millisUntilFinished / 1000).toInt()
            }
            override fun onFinish() {
                // A card SALE/VIP-deduct still in flight resolves on its own
                // (PosLink has its own 60s CommSetting timeout) -- don't
                // yank the dialog out from under it.
                if (dialog.isShowing && !paymentInFlight) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_pay_timeout), Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    resetAdTimer()
                }
            }
        }
        dialogTimer.start()

        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.setOnDismissListener {
            dialogTimer.cancel()
            scannerManager?.stopScan()
            pollingJob?.cancel()
        }
        dialog.show()
    }

    private fun startTapCardAnimation(dialog: Dialog) {
        val animatedCard = dialog.findViewById<View>(R.id.animated_card)
        ObjectAnimator.ofFloat(animatedCard, "translationY", 100f, -50f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun initVipPayment(uid: String, priceInCents: Int, startHex: String, dialog: Dialog) {
        val layoutStatus = dialog.findViewById<ConstraintLayout>(R.id.layout_status_overlay)
        val tvStatus = dialog.findViewById<TextView>(R.id.tv_status_msg)

        layoutStatus.visibility = View.VISIBLE
        tvStatus.text = "VIP Card Detected\nVerifying Balance..."

        paymentInFlight = true
        CoroutineScope(Dispatchers.Main).launch {
            // A plain true/false here used to collapse "network/RPC call
            // failed" and "card genuinely has no money" into the same
            // message ("Balance Insufficient") -- wrong and confusing when
            // the real cause was a dropped connection. See VipDeductResult
            // for why this is deliberately NOT retried via a background
            // queue the way TransactionRepository retries audit writes.
            when (val result = VipRepository.deductBalance(uid, priceInCents)) {
                is VipDeductResult.Success -> {
                    tvStatus.text = "VIP Payment Successful!"
                    delay(1500)
                    // Unique per attempt -- ecr_ref_num is UNIQUE in transactions;
                    // "VIP_$uid" alone would collide on every wash after the
                    // card's first use and permanently fail into the offline
                    // queue (confirmed live: "duplicate key value violates
                    // unique constraint transactions_ecr_ref_num_key").
                    startFinalizationSequence(priceInCents, startHex, "VIP_${uid}_${System.currentTimeMillis()}", dialog)
                }
                is VipDeductResult.Rejected -> {
                    paymentInFlight = false
                    val message = when (result.reason) {
                        "card_inactive" -> "This VIP Card Has Been Deactivated"
                        "card_not_found" -> "VIP Card Not Recognized"
                        else -> "VIP Card Balance Insufficient"
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    if (result.reason == "insufficient_balance") {
                        VoiceManager.playLowBalance(this@MainActivity)
                    }
                    layoutStatus.visibility = View.GONE
                    startPaymentFlow(true, priceInCents, startHex)
                }
                VipDeductResult.NetworkError -> {
                    paymentInFlight = false
                    Toast.makeText(this@MainActivity, "Network error -- please tap your card again", Toast.LENGTH_LONG).show()
                    layoutStatus.visibility = View.GONE
                    startPaymentFlow(true, priceInCents, startHex)
                }
            }
        }
    }

    private fun initCardPayment(priceInCents: Int, startHex: String, dialog: Dialog) {
        // Unique per attempt -- also serves as the transactions.ecr_ref_num
        // for the PENDING row below (UNIQUE constraint), so a fixed constant
        // here would collide across repeated simulated attempts.
        val txRefNum = "CARD_" + System.currentTimeMillis()
        paymentInFlight = true

        CoroutineScope(Dispatchers.Main).launch {
            // Pre-write PENDING before calling the bank so a crash between
            // approval and our own audit write never leaves a
            // charged-but-untracked transaction (docs/card_payment_integration.md #3).
            TransactionRepository.recordTransaction(
                this@MainActivity,
                TransactionRecord(
                    device_sn = deviceSn,
                    amount = priceInCents,
                    payment_status = "PENDING",
                    ecr_ref_num = txRefNum
                )
            )

            if (isSimulationMode) {
                delay(3000)
                startFinalizationSequence(priceInCents, startHex, "MOCK_REF_123", dialog, txRefNum)
            } else {
                PaymentService.startCardPayment(priceInCents, txRefNum, object : PaymentService.PaymentCallback {
                    override fun onSuccess(txId: String, refNum: String) {
                        startFinalizationSequence(priceInCents, startHex, refNum, dialog, txRefNum)
                    }
                    override fun onFailure(errorMsg: String) {
                        paymentInFlight = false
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Card Payment Failed: $errorMsg", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                            resetAdTimer()
                        }
                        CoroutineScope(Dispatchers.Main).launch {
                            TransactionRepository.updatePaymentStatus(this@MainActivity, txRefNum, "DECLINED")
                        }
                    }
                })
            }
        }
    }

    private fun initQrPayment(priceInCents: Int, startHex: String, dialog: Dialog) {
        val qrImageView = dialog.findViewById<ImageView>(R.id.img_pay_qr)
        val txId = "TX_" + System.currentTimeMillis()
        val checkoutUrl = "https://gs-ssp.ca/pay?tx=$txId&amt=${priceInCents}"
        val qrBitmap: Bitmap? = PaymentService.generateQrCode(checkoutUrl, 250, 250)
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap)
        }

        pollingJob = CoroutineScope(Dispatchers.Main).launch {
            // Persist a real session so status can only flip to PAID from the
            // server side (payment gateway webhook), never from the client
            // itself faking success after N poll ticks.
            val created = QrPaymentRepository.createSession(txId, deviceSn, priceInCents)
            if (!created) {
                Toast.makeText(this@MainActivity, "Failed to start QR session, try again", Toast.LENGTH_LONG).show()
                return@launch
            }
            val paid = QrPaymentRepository.pollUntilPaid(txId)
            if (paid) {
                startFinalizationSequence(priceInCents, startHex, "", dialog)
            }
        }

        scannerManager?.startScan(object : PaxScannerManager.ScanCallback {
            override fun onScanSuccess(result: String) {
                startFinalizationSequence(priceInCents, startHex, "", dialog)
            }
            override fun onScanFailure(errorMsg: String) {}
        })
    }

    /**
     * Sequence of confirmation stages after tap/scan success.
     * Records transaction to cloud and triggers hardware pulses.
     *
     * [pendingEcrRefNum] is set only for card payments: initCardPayment
     * already wrote a PENDING row before calling POSLink, so this flips it
     * to PAID instead of inserting a second row (ecr_ref_num is UNIQUE).
     * QR/VIP payments have no pre-write -- their money movement (webhook
     * PAID / deduct_vip_balance RPC) already happened server-side before
     * this function runs, so the first audit row is inserted here.
     */
    private fun startFinalizationSequence(
        amountCents: Int,
        startHex: String,
        refNum: String,
        dialog: Dialog?,
        pendingEcrRefNum: String? = null
    ) {
        val layoutStatus = dialog?.findViewById<ConstraintLayout>(R.id.layout_status_overlay)
        val tvStatus = dialog?.findViewById<TextView>(R.id.tv_status_msg)
        val viewSuccessBg = dialog?.findViewById<View>(R.id.view_final_success_bg)
        val ecrRefNum = pendingEcrRefNum ?: (if (refNum.isEmpty()) "QR_${System.currentTimeMillis()}" else refNum)

        CoroutineScope(Dispatchers.Main).launch {
            layoutStatus?.visibility = View.VISIBLE
            tvStatus?.text = getString(R.string.status_approved)
            TtsManager.speak(getString(R.string.status_approved))

            // 1. Record transaction to Supabase (v2.0 Audit)
            if (pendingEcrRefNum != null) {
                TransactionRepository.updatePaymentStatus(this@MainActivity, pendingEcrRefNum, "PAID")
            } else {
                TransactionRepository.recordTransaction(
                    this@MainActivity,
                    TransactionRecord(
                        device_sn = deviceSn,
                        amount = amountCents,
                        payment_status = "PAID",
                        ecr_ref_num = ecrRefNum
                    )
                )
            }

            delay(1200)
            tvStatus?.text = getString(R.string.status_paid)
            delay(1000)

            tvStatus?.text = getString(R.string.status_sending_command)

            var successAck = false
            if (isSimulationMode) {
                delay(1500)
                successAck = true
            } else {
                // 2. Calculate and send pulses based on settings
                val settings = ConfigManager.getConfig()?.settings
                val pulseWeight = settings?.pulse_weight_cents ?: 25
                val pulseHex = settings?.pulse_hex ?: "AA 01 01 55"
                val pulseCount = amountCents / pulseWeight

                Log.i("SSP_HARDWARE", "Sending $pulseCount pulses for $$amountCents cents")
                successAck = SerialPortManager.sendPulses(pulseHex, pulseCount)
            }

            if (successAck) {
                // 3. Update cloud record with hardware success
                TransactionRepository.updateHardwareStatus(this@MainActivity, ecrRefNum, "ACK_RECEIVED")

                // Receipt printing is cloud-configurable (KioskSettings.print_receipt_enabled) --
                // never blocks or fails the payment flow either way.
                if (ConfigManager.getConfig()?.settings?.print_receipt_enabled == true) {
                    withContext(Dispatchers.IO) {
                        ReceiptPrinterManager.printReceipt(
                            this@MainActivity,
                            ReceiptPrinterManager.ReceiptData(
                                brandName = ConfigManager.getConfig()?.branding?.brand_name ?: "GS-SSP",
                                amountCents = amountCents,
                                refNum = ecrRefNum,
                                deviceSn = deviceSn
                            )
                        )
                    }
                }

                viewSuccessBg?.visibility = View.VISIBLE
                tvStatus?.text = getString(R.string.status_enjoy_wash)
                TtsManager.speak(getString(R.string.toast_payment_success_enjoy))
                viewSuccessBg?.alpha = 0f
                viewSuccessBg?.animate()?.alpha(1f)?.setDuration(500)?.start()

                paymentInFlight = false
                delay(5000)
                dialog?.dismiss()
                onPaymentSuccess()
            } else {
                layoutStatus?.setBackgroundColor(Color.parseColor("#C62828"))
                tvStatus?.text = getString(R.string.status_error_refund)
                TtsManager.speak(getString(R.string.status_error_refund))

                // Industrial Audit: Report hardware failure and trigger VOID
                // (falling back to REFUND automatically if VOID is declined,
                // e.g. because the batch already settled).
                DiagnosticManager.reportError(deviceSn, "HARDWARE_PULSE_FAIL", severity = "CRITICAL")
                TransactionRepository.updateHardwareStatus(this@MainActivity, ecrRefNum, "HARDWARE_ERROR")

                if (refNum.isNotEmpty()) {
                    PaymentService.voidOrRefund(refNum, amountCents) { success, method ->
                        CoroutineScope(Dispatchers.Main).launch {
                            if (success) {
                                TransactionRepository.updatePaymentStatus(
                                    this@MainActivity, ecrRefNum, if (method == "REFUND") "REFUNDED" else "VOIDED"
                                )
                            } else {
                                // Neither VOID nor REFUND went through -- money was
                                // captured but no automatic reversal succeeded.
                                DiagnosticManager.reportError(deviceSn, "VOID_AND_REFUND_FAILED", severity = "CRITICAL")
                            }
                        }
                    }
                }
                paymentInFlight = false
                delay(5000)
                dialog?.dismiss()
                resetAdTimer()
            }
        }
    }

    private fun onPaymentSuccess() {
        runOnUiThread {
            paymentDialog = null
            layoutPackageSelection.visibility = View.VISIBLE
            isWorking = false
            resetAdTimer()
        }
    }

    override fun onDestroy() {
        stopWatchdog()
        RemoteCommandManager.stopListening()
        ShadowManager.stopSync()
        super.onDestroy()
        paymentDialog?.dismiss()
        pollingJob?.cancel()
        scannerManager?.stopScan()
        if (!isSimulationMode) {
            SerialPortManager.closePort()
        }
    }

    // --- Technician & Maintenance Mode Logic ---

    /**
     * Global crash handler to ensure the app restarts immediately in case of failure.
     * Vital for unattended Kiosk environments.
     */
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e("SSP_CRASH", "System crash detected, restarting...", throwable)
            try {
                val stackTrace = throwable.stackTraceToString()
                val job = DiagnosticManager.reportError(deviceSn, "APP_CRASH", severity = "CRITICAL", trace = stackTrace)
                // reportError is otherwise fire-and-forget; block briefly (bounded)
                // so the report has a real chance to reach the network before
                // System.exit() below kills the process mid-flight.
                runBlocking { withTimeoutOrNull(2000) { job.join() } }
            } catch (e: Exception) {
                Log.e("SSP_CRASH", "Failed to report crash telemetry: ${e.message}")
            }
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            System.exit(1)
        }
    }

    /**
     * Checks hardware connectivity and network status to update the health indicator.
     */
    private fun performHealthCheck() {
        val indicator = findViewById<View>(R.id.view_health_indicator) ?: return
        val isSerialOk = isSimulationMode || SerialPortManager.isOpened()
        val isDbOk = isSimulationMode || ConfigManager.isDatabaseOnline()
        val isLocked = DeviceAccessManager.isLocked()
        val isKeyOk = KeyHealthMonitor.isPaymentAllowed()

        // Black: admin/remote locked. Red: hardware/DB/key fault. Yellow: sim mode. Green: operational.
        when {
            isLocked -> indicator.setBackgroundColor(Color.BLACK)
            !isSerialOk || !isDbOk || !isKeyOk -> indicator.setBackgroundColor(Color.RED)
            isSimulationMode -> indicator.setBackgroundColor(Color.YELLOW)
            else -> indicator.setBackgroundColor(Color.GREEN)
        }
    }

    /**
     * Hidden trigger for technician menu (7 clicks on logo within 2 seconds).
     */
    private fun setupMaintenanceTrigger() {
        findViewById<View>(R.id.img_logo).setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime > 2000) {
                logoClickCount = 0
            }
            lastClickTime = currentTime
            logoClickCount++
            
            if (logoClickCount >= 3) {
                logoClickCount = 0
                showTechPinDialog()
            }
            
            // Standard click logic (reset ad timer)
            resetAdTimer()
        }
    }

    private fun showTechPinDialog() {
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_tech_login)
        
        val etPin = dialog.findViewById<android.widget.EditText>(R.id.et_tech_pin)
        
        dialog.findViewById<View>(R.id.btn_tech_confirm).setOnClickListener {
            val pin = etPin.text.toString().trim()
            Log.d("SSP_TECH", "Attempting login with PIN: $pin")
            if (pin == "1234") {
                dialog.dismiss()
                openTechTools()
            } else {
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
                etPin.setText("")
            }
        }
        
        dialog.findViewById<View>(R.id.btn_tech_cancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.show()
    }

    /**
     * Specialized Maintenance Dashboard for technicians.
     */
    private fun openTechTools() {
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_maintenance)
        
        // 1. Header & Exit
        dialog.findViewById<Button>(R.id.btn_dash_exit).setOnClickListener { dialog.dismiss() }

        // 2. Status Indicators
        val tvSerial = dialog.findViewById<TextView>(R.id.tv_status_serial)
        val tvNet = dialog.findViewById<TextView>(R.id.tv_status_network)
        val tvDb = dialog.findViewById<TextView>(R.id.tv_status_db)
        
        val serialStatus = if (isSimulationMode) "MOCK" else if (SerialPortManager.isOpened()) "OPEN" else "OFF"
        tvSerial.text = "Serial: $serialStatus"
        tvSerial.setTextColor(if (serialStatus == "OFF") Color.RED else Color.GREEN)

        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val netInfo = cm.activeNetworkInfo
        val isOnline = netInfo != null && netInfo.isConnected
        tvNet.text = "Net: ${if (isOnline) "ONLINE" else "OFFLINE"}"
        tvNet.setTextColor(if (isOnline) Color.GREEN else Color.RED)

        val dbStatus = if (ConfigManager.isDatabaseOnline()) "ONLINE" else "OFF"
        tvDb.text = "DB: $dbStatus"
        tvDb.setTextColor(if (ConfigManager.isDatabaseOnline()) Color.GREEN else Color.RED)

        // 3. Relay Command Buttons
        dialog.findViewById<Button>(R.id.btn_test_4).setOnClickListener { sendTestCmd("AA 01 04 55") }
        dialog.findViewById<Button>(R.id.btn_test_6).setOnClickListener { sendTestCmd("AA 01 06 55") }
        dialog.findViewById<Button>(R.id.btn_test_8).setOnClickListener { sendTestCmd("AA 01 08 55") }
        dialog.findViewById<Button>(R.id.btn_test_stop).setOnClickListener { sendTestCmd("AA 00 00 55") }

        // 4. Peripherals
        dialog.findViewById<Button>(R.id.btn_test_scan).setOnClickListener {
            Toast.makeText(this, "Scanner Active...", Toast.LENGTH_SHORT).show()
            scannerManager?.startScan(object : PaxScannerManager.ScanCallback {
                override fun onScanSuccess(result: String) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Scan: $result", Toast.LENGTH_LONG).show() }
                }
                override fun onScanFailure(errorMsg: String) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Scan Failed", Toast.LENGTH_SHORT).show() }
                }
            })
        }

        val btnQrTest = dialog.findViewById<Button>(R.id.btn_test_qr_gen)
        val tvFooter = dialog.findViewById<TextView>(R.id.tv_dash_title) 
        tvFooter.text = "SN: $deviceSn\nTECH DASHBOARD"
        
        btnQrTest.text = "FORCE SYNC"
        btnQrTest.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(this@MainActivity, "Syncing Config...", Toast.LENGTH_SHORT).show()

                // A technician forcing a sync is also the right moment to clear
                // any stale lock state (key-health lockout, remote LOCK) so the
                // terminal doesn't stay dark after they've fixed the underlying issue.
                KeyHealthMonitor.reset()
                DeviceAccessManager.setRemoteLock(false)
                val identity = DeviceRepository.syncDeviceIdentity(deviceSn)
                DeviceAccessManager.applyActiveState(identity?.is_active)
                performHealthCheck()

                val config = ConfigManager.loadConfig(this@MainActivity, identity?.org_id)
                refreshProductsUI(config.products)

                // Record maintenance action
                DiagnosticManager.recordMaintenance(deviceSn, "FORCE_SYNC")

                // Also trigger Ad Sync now
                val adRequest = androidx.work.OneTimeWorkRequestBuilder<AdSyncWorker>().build()
                androidx.work.WorkManager.getInstance(this@MainActivity).enqueue(adRequest)

                Toast.makeText(this@MainActivity, "Sync Complete (v${config.version})", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.findViewById<Button>(R.id.btn_sim_hang).setOnClickListener {
            Toast.makeText(this, "System will hang in 2s. Watchdog should reboot.", Toast.LENGTH_LONG).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                while(true) { /* INFINITE HANG */ }
            }, 2000)
        }

        // 6. Diagnostics
        dialog.findViewById<Button>(R.id.btn_test_db).setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                tvDb.text = "DB: TESTING..."
                tvDb.setTextColor(Color.YELLOW)
                val ok = ConfigManager.checkDatabaseHealth()
                tvDb.text = "DB: ${if (ok) "ONLINE" else "ERROR"}"
                tvDb.setTextColor(if (ok) Color.GREEN else Color.RED)
                performHealthCheck()
            }
        }

        var ledCycle = 0
        dialog.findViewById<Button>(R.id.btn_test_led).setOnClickListener {
            val indicator = findViewById<View>(R.id.view_health_indicator)
            when (ledCycle % 3) {
                0 -> indicator?.setBackgroundColor(Color.RED)
                1 -> indicator?.setBackgroundColor(Color.YELLOW)
                2 -> indicator?.setBackgroundColor(Color.GREEN)
            }
            ledCycle++
            Toast.makeText(this, "LED Cycled", Toast.LENGTH_SHORT).show()
        }

        dialog.findViewById<Button>(R.id.btn_test_voice).setOnClickListener {
            TtsManager.speak("GS-SSP system voice test successful. Speaker and volume are operational.")
            Toast.makeText(this, "Playing Test Voice...", Toast.LENGTH_SHORT).show()
        }

        dialog.findViewById<Button>(R.id.btn_test_nfc).setOnClickListener {
            Toast.makeText(this, "NFC Probing for 10s... Tap a card.", Toast.LENGTH_LONG).show()
            scannerManager?.startCardDetection(object : PaxScannerManager.CardCallback {
                override fun onCardDetected(type: String, uid: String) {
                    runOnUiThread { 
                        Toast.makeText(this@MainActivity, "NFC Detected! Type: $type, UID: $uid", Toast.LENGTH_LONG).show()
                        scannerManager?.stopCardDetection()
                    }
                }
                override fun onDetectionError(error: String) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "NFC Probe Error: $error", Toast.LENGTH_SHORT).show() }
                }
            })
        }

        dialog.findViewById<Button>(R.id.btn_test_brightness).setOnClickListener {
            if (isSimulationMode) {
                isHighBrightness = !isHighBrightness
                Toast.makeText(this, "Simulating Brightness: ${if (isHighBrightness) "100%" else "50%"}", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    isHighBrightness = !isHighBrightness
                    val value = if (isHighBrightness) 255 else 128
                    val dal = NeptuneLiteUser.getInstance().getDal(this)
                    dal.getSys()?.setScreenBrightness(value)
                    ShadowManager.syncReportedState(this, deviceSn)
                    Toast.makeText(this, "Brightness set to ${if (isHighBrightness) "100%" else "50%"}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("SSP_TECH", "Brightness adjustment failed: ${e.message}")
                }
            }
        }

        // 7. Configuration Switch
        val swSim = dialog.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_sim_mode)
        swSim.isChecked = isSimulationMode
        swSim.setOnCheckedChangeListener { _, isChecked ->
            isSimulationMode = isChecked
            if (!isSimulationMode) SerialPortManager.openPort(this) else SerialPortManager.closePort()
            performHealthCheck()
            tvSerial.text = "Serial: ${if (isSimulationMode) "MOCK" else if (SerialPortManager.isOpened()) "OPEN" else "OFF"}"
        }

        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.show()
    }

    // --- Watchdog & Resilience Logic ---

    /**
     * Starts the hardware watchdog feeding loop.
     */
    private fun startWatchdog() {
        if (isSimulationMode) return
        
        watchdogJob?.cancel()
        watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                deviceControl?.watchdogOpen()
                Log.i("SSP_WATCHDOG", "Hardware Watchdog OPENED")
                
                while (isActive) {
                    // Feed the dog every 15 seconds. 
                    // Hardware timeout is usually 30-60s on PAX.
                    deviceControl?.watchdogFeed()
                    Log.v("SSP_WATCHDOG", "Watchdog FED")
                    delay(15000)
                }
            } catch (e: Exception) {
                Log.e("SSP_WATCHDOG", "Watchdog error: ${e.message}")
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        try {
            deviceControl?.watchdogClose()
            Log.i("SSP_WATCHDOG", "Hardware Watchdog CLOSED")
        } catch (e: Exception) {}
    }

    private fun sendTestCmd(hex: String) {
        if (isSimulationMode) {
            Toast.makeText(this, "Simulating: $hex", Toast.LENGTH_SHORT).show()
        } else {
            val sent = SerialPortManager.sendHexString(hex)
            Toast.makeText(this, if (sent) "Sent: $hex" else "Send FAILED", Toast.LENGTH_SHORT).show()
        }
    }
}
