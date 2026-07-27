package com.goldsky.carwash

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
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
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.goldsky.carwash.dispense.DispenseEngine
import com.goldsky.carwash.dispense.DispenseJob
import com.goldsky.carwash.dispense.DispenseOutcome
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
    private var laserAnimator: ObjectAnimator? = null
    private var coordinatedTapAnimator: AnimatorSet? = null
    private var pulseAnimator: ValueAnimator? = null

    // True from the moment money-movement is initiated (card SALE sent to
    // POSLink, or a VIP balance deduction in flight) until it's fully
    // resolved. Gates the payment dialog's back/cancel buttons and its
    // timeout auto-dismiss, since dismissing mid-flight would desync the UI
    // from a bank transaction that's still actually in progress server-side.
    @Volatile private var paymentInFlight = false

    // Set by a home-screen "Scan Coupon / Member QR Code" scan (see
    // initCouponScan()) and consumed the moment the customer picks a
    // package/custom amount -- mutually exclusive, since a single scan
    // result is routed to exactly one of the two (see docs/
    // coupon_redemption_integration.md §2.1's format-based routing).
    private var pendingVipCardUid: String? = null
    private var pendingCoupon: CouponRedeemResult.Success? = null

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
        startLaserAnimation()
        start3DStatusPulse()
    }

    override fun onPause() {
        super.onPause()
        stopAdTimer()
        // Disable scanner LED when leaving main menu
        scannerManager?.setScannerLed(false)
        stopLaserAnimation()
        stop3DStatusPulse()
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
            applyClickFeedback(it)
            showCustomAmountDialog()
        }

        findViewById<View>(R.id.layout_vip_banner).setOnClickListener {
            applyClickFeedback(it)
            startActivity(Intent(this, VipActivity::class.java))
        }

        listOf(R.id.card_standard, R.id.card_delux, R.id.card_wax).forEach { id ->
            findViewById<View>(id)?.setOnClickListener { applyClickFeedback(it) }
        }

        findViewById<View>(R.id.layout_scan_belt).setOnClickListener {
            initCouponScan()
        }
    }

    private fun applyClickFeedback(view: View) {
        view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }.start()
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
            val oldPrice = priceView.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0
            val newPrice = product.price_cents / 100
            animatePrice(priceView, oldPrice, newPrice)
            labelView.text = product.name
            
            cardView.setOnClickListener {
                // ... same click logic ...
                val serialHex = product.attributes?.get("serial_hex")?.jsonPrimitive?.contentOrNull
                    ?: "AA000055"
                startPackagePurchaseFlow(product.price_cents, serialHex, product.id)
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
        val flipTens = dialog.findViewById<com.goldsky.carwash.view.FlipDigitView>(R.id.flip_tens)
        val flipOnes = dialog.findViewById<com.goldsky.carwash.view.FlipDigitView>(R.id.flip_ones)
        
        // Init values
        flipTens?.setValue(1, false)
        flipOnes?.setValue(0, false)
        
        dialog.findViewById<Button>(R.id.btn_plus).setOnClickListener {
            applyClickFeedback(it)
            if (amount < 40) {
                amount += 1
                flipTens?.setValue(amount / 10)
                flipOnes?.setValue(amount % 10)
            }
        }
        
        dialog.findViewById<Button>(R.id.btn_minus).setOnClickListener {
            applyClickFeedback(it)
            if (amount > 4) {
                amount -= 1
                flipTens?.setValue(amount / 10)
                flipOnes?.setValue(amount % 10)
            }
        }
        
        dialog.findViewById<Button>(R.id.btn_confirm_custom).setOnClickListener {
            applyClickFeedback(it)
            dialog.dismiss()
            val hex = "AA 01 ${"%02X".format(amount)} 55"
            startPackagePurchaseFlow(amount * 100, hex)
        }
        
        dialog.findViewById<Button>(R.id.btn_cancel_custom).setOnClickListener {
            applyClickFeedback(it)
            dialog.dismiss()
            resetAdTimer()
        }
        
        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.show()
    }

    /**
     * Entry point for the home-screen "Scan Coupon / Member QR Code" belt
     * (layout_scan_belt) -- previously pure decoration with no backing logic
     * (see docs/coupon_redemption_integration.md). Routes the scanned string
     * by format (§2.1): a 12-character alphanumeric code is a member QR
     * code, anything else is a coupon/voucher code. The client never judges
     * a coupon's validity itself -- redeem_coupon() does that atomically,
     * server-side; the client only routes the result.
     */
    private fun initCouponScan() {
        if (paymentDialog?.isShowing == true) return // a payment is already in flight, ignore

        scannerManager?.startScan(object : PaxScannerManager.ScanCallback {
            override fun onScanSuccess(result: String) {
                val scanned = result.trim()
                runOnUiThread {
                    if (Regex("^[A-Za-z0-9]{12}$").matches(scanned)) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val cardUid = VipRepository.resolveCardUidByQrCode(scanned)
                            if (cardUid != null) {
                                pendingCoupon = null
                                pendingVipCardUid = cardUid
                                Toast.makeText(this@MainActivity, getString(R.string.toast_member_recognized), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@MainActivity, getString(R.string.toast_member_code_invalid), Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        CoroutineScope(Dispatchers.Main).launch {
                            when (val redemption = CouponRepository.redeemCoupon(scanned, deviceSn)) {
                                is CouponRedeemResult.Success -> {
                                    if (redemption.applicableProductId == null) {
                                        pendingVipCardUid = null
                                        pendingCoupon = redemption
                                        Toast.makeText(this@MainActivity, getString(R.string.toast_coupon_applied), Toast.LENGTH_LONG).show()
                                    } else {
                                        // Already consumed server-side (uses_count incremented) -- the
                                        // client has no reliable way to match it against the currently
                                        // selected package, see the plan's product-id matching gap note.
                                        Toast.makeText(this@MainActivity, getString(R.string.toast_coupon_see_staff), Toast.LENGTH_LONG).show()
                                    }
                                }
                                is CouponRedeemResult.Rejected, CouponRedeemResult.NetworkError -> {
                                    // Never distinguish the reason to the customer (not_found vs
                                    // already_used vs expired, etc.) -- see doc §4.6.
                                    Toast.makeText(this@MainActivity, getString(R.string.toast_coupon_invalid), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            }
            override fun onScanFailure(errorMsg: String) {
                Log.w("MainActivity", "Coupon scan failed: $errorMsg")
            }
        })
    }

    /**
     * Single entry point for "customer has picked a price" -- both
     * refreshProductsUI's package cards and showCustomAmountDialog's confirm
     * button call this instead of showPaymentDialog directly, so any pending
     * scan result from initCouponScan() is applied exactly once, regardless
     * of which path the customer took to get here. [priceInCents]/[startHex]
     * are the package's own (pre-discount) price and hardware command.
     */
    private fun startPackagePurchaseFlow(priceInCents: Int, startHex: String, productId: String? = null) {
        val vipUid = pendingVipCardUid
        if (vipUid != null) {
            pendingVipCardUid = null
            startPreAuthenticatedVipFlow(priceInCents, startHex, vipUid, productId)
            return
        }

        val coupon = pendingCoupon
        if (coupon != null) {
            pendingCoupon = null
            // Formula from docs/coupon_redemption_integration.md §3.2 --
            // clamped at 0, never negative (no "change back to the customer").
            val finalPriceCents = when (coupon.type) {
                "PERCENT_OFF" -> priceInCents - (priceInCents * coupon.value / 100)
                "FIXED_OFF" -> maxOf(0, priceInCents - coupon.value)
                "FREE_WASH" -> 0
                else -> priceInCents
            }
            if (finalPriceCents <= 0) {
                startFreeWashFlow(priceInCents, startHex, productId)
            } else {
                showPaymentDialog(finalPriceCents, startHex, productId)
            }
            return
        }

        showPaymentDialog(priceInCents, startHex, productId)
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
    private fun showPaymentDialog(priceInCents: Int, startHex: String, productId: String? = null) {
        if (DeviceAccessManager.isLocked()) {
            Toast.makeText(this, "Terminal locked: ${DeviceAccessManager.lockReason()}", Toast.LENGTH_LONG).show()
            resetAdTimer()
            return
        }

        when (ConfigManager.getConfig()?.settings?.payment_method_mode ?: PaymentMethodMode.ALL) {
            PaymentMethodMode.CARD_ONLY -> {
                stopAdTimer()
                startPaymentFlow(true, priceInCents, startHex, productId)
                return
            }
            PaymentMethodMode.SCAN_ONLY -> {
                stopAdTimer()
                startPaymentFlow(false, priceInCents, startHex, productId)
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
            applyClickFeedback(it)
            selectionDialog.dismiss()
            startPaymentFlow(true, priceInCents, startHex, productId)
        }

        selectionDialog.findViewById<View>(R.id.btn_choice_scan).setOnClickListener {
            applyClickFeedback(it)
            selectionDialog.dismiss()
            startPaymentFlow(false, priceInCents, startHex, productId)
        }
        
        selectionDialog.findViewById<Button>(R.id.btn_cancel_choice).setOnClickListener {
            applyClickFeedback(it)
            selectionDialog.dismiss()
            resetAdTimer()
        }
        
        selectionDialog.setOnShowListener { applyKioskWindowFlags() }
        selectionDialog.show()
    }

    private fun startPaymentFlow(isCard: Boolean, priceInCents: Int, startHex: String, productId: String? = null) {
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
            startCoordinatedTapAnimation(dialog)
            
            // DYNAMIC VOICE: Localized amount and instruction
            TtsManager.announceAmount(priceInCents, "Total amount is")
            TtsManager.speak(getString(R.string.prompt_card_guide))

            // Start background NFC detection to distinguish between EMV and VIP
            scannerManager?.startCardDetection(object : PaxScannerManager.CardCallback {
                override fun onCardDetected(type: String, uid: String) {
                    if (type == "MIFARE") {
                        // VIP Membership detected
                        initVipPayment(uid, priceInCents, startHex, dialog, productId)
                    } else {
                        // Payment Card (EMV) detected - Hand over to POSLink
                        // Critical: We must close the low-level PICC before POSLink takes over
                        scannerManager?.stopCardDetection()
                        initCardPayment(priceInCents, startHex, dialog, productId)
                    }
                }
                override fun onDetectionError(error: String) {
                    Log.e("MainActivity", "NFC detection error: $error")
                }
            })
        } else {
            layoutCard.visibility = View.GONE
            layoutQr.visibility = View.VISIBLE
            initQrPayment(priceInCents, startHex, dialog, productId)
        }

        dialog.findViewById<View>(R.id.btn_back_pay)?.setOnClickListener {
            applyClickFeedback(it)
            if (paymentInFlight) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_payment_processing_wait), Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                showPaymentDialog(priceInCents, startHex, productId)
            }
        }
        dialog.findViewById<View>(R.id.btn_back_qr)?.setOnClickListener {
            applyClickFeedback(it)
            if (paymentInFlight) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_payment_processing_wait), Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                showPaymentDialog(priceInCents, startHex, productId)
            }
        }

        val dialogTimer = object : CountDownTimer(60000, 100) {
            override fun onTick(millisUntilFinished: Long) {
                pbTimeout.progress = (millisUntilFinished / 1000).toInt()
            }
            override fun onFinish() {
                // A card SALE/VIP-deduct/QR poll still in flight resolves on
                // its own (PosLink has its own 60s CommSetting timeout;
                // QrPaymentRepository.pollUntilPaid() polls for up to 120s)
                // -- don't yank the dialog out from under it. Without this
                // guard, a QR payment that took the customer 60-120s to
                // complete on their own phone would get its dialog
                // auto-dismissed (which cancels pollingJob) right as, or
                // just before, Stripe actually confirms it -- money moves,
                // app never finds out.
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
            stopCoordinatedTapAnimation()
        }
        dialog.show()
    }


    private fun initVipPayment(uid: String, priceInCents: Int, startHex: String, dialog: Dialog, productId: String? = null) {
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
                    startFinalizationSequence(priceInCents, startHex, "VIP_${uid}_${System.currentTimeMillis()}", dialog, productId = productId, paymentMethod = "VIP_CARD")
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
                    startPaymentFlow(true, priceInCents, startHex, productId)
                }
                VipDeductResult.NetworkError -> {
                    paymentInFlight = false
                    Toast.makeText(this@MainActivity, "Network error -- please tap your card again", Toast.LENGTH_LONG).show()
                    layoutStatus.visibility = View.GONE
                    startPaymentFlow(true, priceInCents, startHex, productId)
                }
            }
        }
    }

    /**
     * Like startPaymentFlow(isCard=true, ...) but for when the VIP identity
     * is already known from a home-screen member-QR-code scan
     * (initCouponScan()) -- skips the "please tap your card" guidance screen
     * entirely and goes straight into the same deduct-balance/finalization
     * logic initVipPayment() already implements for the NFC-tap path (on
     * rejection/network error, initVipPayment falls back to
     * startPaymentFlow(true, ...) itself, letting the customer tap a
     * physical card instead -- no special-casing needed here for that).
     * Deliberately a small dedicated function rather than folding into
     * startPaymentFlow -- that function's shape exists to race NFC detection
     * between EMV and MIFARE when the identity isn't known yet, which
     * doesn't apply here.
     */
    private fun startPreAuthenticatedVipFlow(priceInCents: Int, startHex: String, uid: String, productId: String? = null) {
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_payment)
        paymentDialog = dialog

        dialog.findViewById<ConstraintLayout>(R.id.layout_card_guidance).visibility = View.GONE
        dialog.findViewById<ConstraintLayout>(R.id.layout_qr_guidance).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.shared_subtitle).text = getString(R.string.prompt_pay_subtitle, "$${priceInCents / 100}")

        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.setOnDismissListener {
            pollingJob?.cancel()
            scannerManager?.stopScan()
        }
        dialog.show()

        initVipPayment(uid, priceInCents, startHex, dialog, productId)
    }

    /**
     * A redeemed coupon fully covered the package price (finalPriceCents ==
     * 0, see startPackagePurchaseFlow) -- skip card/QR/VIP payment entirely
     * and go straight to hardware dispense. [originalPriceCents] is the
     * package's own pre-discount price: startFinalizationSequence's real-
     * hardware path derives pulse count from the amount charged, and
     * amountCents=0 there would silently send zero pulses
     * (PulseCreditAdapter short-circuits on count<=0) -- the
     * customer would see "Enjoy your wash" and get nothing. pulseAmountCents
     * keeps "money charged" (0, for the transaction record) and "wash
     * dispensed" (the full package) as separate concerns.
     */
    private fun startFreeWashFlow(originalPriceCents: Int, startHex: String, productId: String? = null) {
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_payment)
        paymentDialog = dialog

        dialog.findViewById<ConstraintLayout>(R.id.layout_card_guidance).visibility = View.GONE
        dialog.findViewById<ConstraintLayout>(R.id.layout_qr_guidance).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.shared_subtitle).text = getString(R.string.prompt_pay_subtitle, "$0")

        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.setOnDismissListener {
            pollingJob?.cancel()
            scannerManager?.stopScan()
        }
        dialog.show()

        paymentInFlight = true
        startFinalizationSequence(0, startHex, "", dialog, pulseAmountCents = originalPriceCents, productId = productId, paymentMethod = "COUPON")
    }

    private fun initCardPayment(priceInCents: Int, startHex: String, dialog: Dialog, productId: String? = null) {
        // Unique per attempt -- also serves as the transactions.ecr_ref_num
        // for the PENDING row below (UNIQUE constraint), so a fixed constant
        // here would collide across repeated simulated attempts.
        val txRefNum = "CARD_" + System.currentTimeMillis()
        paymentInFlight = true

        CoroutineScope(Dispatchers.Main).launch {
            // Pre-write PENDING before calling the bank so a crash between
            // approval and our own audit write never leaves a
            // charged-but-untracked transaction (docs/card_payment_integration.md #3).
            // payment_method/product_id are set here, on the only INSERT for
            // this row -- startFinalizationSequence's later PENDING->PAID
            // transition is an UPDATE that only ever touches payment_status.
            TransactionRepository.recordTransaction(
                this@MainActivity,
                TransactionRecord(
                    device_sn = deviceSn,
                    amount = priceInCents,
                    payment_status = "PENDING",
                    ecr_ref_num = txRefNum,
                    payment_method = "CREDIT_CARD",
                    product_id = productId
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

    private fun initQrPayment(priceInCents: Int, startHex: String, dialog: Dialog, productId: String? = null) {
        val qrImageView = dialog.findViewById<ImageView>(R.id.img_pay_qr)
        val txId = "TX_" + System.currentTimeMillis()

        // Set for the whole create-session+poll window (up to ~2 minutes,
        // matching pollUntilPaid's own 60x2s budget below), not just around
        // the finalization sequence like card/VIP -- the customer completes
        // payment on their OWN phone, entirely outside our control, so there
        // is no earlier "safe to cancel" point: once a Checkout Session
        // exists, we can't tell from the kiosk side whether they're still
        // typing a card number or have already hit submit. Guards both the
        // Back button (btn_back_qr) and the 60s dialogTimer auto-dismiss --
        // without this, either could tear down the dialog (which cancels
        // pollingJob) while Stripe is mid-processing, leaving a paid session
        // the app never finds out about.
        paymentInFlight = true
        pollingJob = CoroutineScope(Dispatchers.Main).launch {
            // create-qr-session (Edge Function) is the only thing that talks
            // to the payment gateway and is the only writer of this session
            // row -- status can only flip to PAID from the server side
            // (payment-webhook), never from the client itself faking success
            // after N poll ticks. code_url comes from the gateway, not a
            // client-fabricated placeholder.
            val codeUrl = QrPaymentRepository.createSession(txId, deviceSn, priceInCents)
            if (codeUrl == null) {
                paymentInFlight = false
                Toast.makeText(this@MainActivity, "Failed to start QR session, try again", Toast.LENGTH_LONG).show()
                dialog.dismiss()
                resetAdTimer()
                return@launch
            }
            val qrBitmap: Bitmap? = PaymentService.generateQrCode(codeUrl, 250, 250)
            if (qrBitmap == null) {
                // Session was created (money-side is fine) but the customer has
                // nothing to scan -- must not fall through to pollUntilPaid,
                // which would silently wait up to 2 minutes on a QR that was
                // never shown, with no error and no way for the customer to
                // recover on their own.
                Log.e("SSP_QR", "generateQrCode returned null for codeUrl (len=${codeUrl.length})")
                paymentInFlight = false
                Toast.makeText(this@MainActivity, "Failed to render QR code, try again", Toast.LENGTH_LONG).show()
                dialog.dismiss()
                resetAdTimer()
                return@launch
            }
            qrImageView.setImageBitmap(qrBitmap)
            val paid = QrPaymentRepository.pollUntilPaid(txId)
            if (paid) {
                // paymentInFlight is cleared by startFinalizationSequence itself once it's done.
                startFinalizationSequence(priceInCents, startHex, "", dialog, productId = productId, paymentMethod = "QR_CODE")
            } else {
                // Polling gave up (customer never completed payment, or it's
                // still processing beyond our 2-minute budget) -- previously
                // this branch did nothing at all, leaving the dialog sitting
                // open forever with no way for the kiosk to recover on its own.
                paymentInFlight = false
                Toast.makeText(this@MainActivity, getString(R.string.toast_pay_timeout), Toast.LENGTH_LONG).show()
                dialog.dismiss()
                resetAdTimer()
            }
        }
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
        pendingEcrRefNum: String? = null,
        // Defaults to amountCents (unchanged behavior for every existing
        // call site). Separate parameter for startFreeWashFlow, where the
        // amount charged (0, what gets recorded/audited) and the amount of
        // wash to actually dispense (the package's full price) diverge --
        // see that function's doc comment.
        pulseAmountCents: Int = amountCents,
        // Only used on the insert branch below (pendingEcrRefNum == null,
        // i.e. VIP/QR/free-wash) -- the card path already wrote both of
        // these on its own PENDING insert in initCardPayment, and the
        // PENDING->PAID transition here is an UPDATE that doesn't touch them.
        productId: String? = null,
        paymentMethod: String = "UNKNOWN"
    ) {
        val layoutStatus = dialog?.findViewById<ConstraintLayout>(R.id.layout_status_overlay)
        val tvStatus = dialog?.findViewById<TextView>(R.id.tv_status_msg)
        val ivStatusIcon = dialog?.findViewById<ImageView>(R.id.iv_status_icon)
        val layoutWashStepper = dialog?.findViewById<ConstraintLayout>(R.id.layout_wash_stepper)
        val pbWashStages = dialog?.findViewById<ProgressBar>(R.id.pb_wash_stages)
        val tvStageLabel = dialog?.findViewById<TextView>(R.id.tv_stage_label)
        val stageLabels = resources.getStringArray(R.array.wash_stage_labels)
        val ecrRefNum = pendingEcrRefNum ?: (if (refNum.isEmpty()) "QR_${System.currentTimeMillis()}" else refNum)
        var washIconAnimator: ObjectAnimator? = null

        CoroutineScope(Dispatchers.Main).launch {
            layoutStatus?.visibility = View.VISIBLE
            tvStatus?.text = getString(R.string.status_approved)
            TtsManager.speak(getString(R.string.status_approved))

            // The whole approved->paid->dispensing wait is unattended (nothing
            // for the customer to tap), so the water-drop icon breathes for
            // the entire overlay lifetime, not just during hardware dispense --
            // otherwise the first two stages would look frozen.
            washIconAnimator = ivStatusIcon?.let { icon ->
                ObjectAnimator.ofPropertyValuesHolder(
                    icon,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f, 1f)
                ).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }

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
                        ecr_ref_num = ecrRefNum,
                        payment_method = paymentMethod,
                        product_id = productId
                    )
                )
            }

            delay(1200)
            tvStatus?.text = getString(R.string.status_paid)
            delay(1000)

            tvStatus?.text = getString(R.string.status_sending_command)
            layoutWashStepper?.visibility = View.VISIBLE
            pbWashStages?.progress = 0
            tvStageLabel?.text = stageLabels.firstOrNull()

            // 2. Delegate to the protocol/ack-strategy
            val outcome = DispenseEngine.dispense(
                DispenseJob(pulseAmountCents, startHex, deviceSn, ecrRefNum),
                isSimulationMode
            ) { unitsSent, totalUnits ->
                val fraction = if (totalUnits <= 0) 1f else unitsSent.toFloat() / totalUnits
                pbWashStages?.progress = (fraction * 100).toInt()
                val stageIdx = (fraction * stageLabels.size).toInt().coerceIn(0, stageLabels.size - 1)
                tvStageLabel?.text = stageLabels[stageIdx]
            }
            Log.i("SSP_HARDWARE", "Dispense outcome: $outcome")

            washIconAnimator?.cancel()
            layoutWashStepper?.visibility = View.GONE

            if (outcome !is DispenseOutcome.Failed) {
                ivStatusIcon?.setImageResource(R.drawable.ic_check_circle)
                popIcon(ivStatusIcon)
                // 3. Update cloud record with hardware success
                // (older boards with no ACK) gets its own status rather than being
                // folded into ACK_RECEIVED, so an audit query can tell "we know it
                // ran" from "we only know we sent it".
                val hwStatus = if (outcome is DispenseOutcome.Confirmed) "ACK_RECEIVED" else "COMMAND_SENT_UNCONFIRMED"
                TransactionRepository.updateHardwareStatus(this@MainActivity, ecrRefNum, hwStatus)
                if (outcome is DispenseOutcome.DeliveredUnconfirmed) {
                    DiagnosticManager.reportError(deviceSn, "HARDWARE_ACK_UNAVAILABLE", severity = "INFO")
                }

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

                tvStatus?.text = getString(R.string.status_enjoy_wash)
                TtsManager.speak(getString(R.string.toast_payment_success_enjoy))

                paymentInFlight = false
                delay(5000)
                dialog?.dismiss()
                onPaymentSuccess()
            } else {
                layoutStatus?.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.alert_red_bg))
                ivStatusIcon?.setImageResource(R.drawable.ic_error_circle)
                popIcon(ivStatusIcon)
                tvStatus?.text = getString(R.string.status_error_refund)
                TtsManager.speak(getString(R.string.status_error_refund))

                // Industrial Audit: Report hardware failure and trigger VOID
                // (falling back to REFUND automatically if VOID is declined,
                // e.g. because the batch already settled). Only DispenseOutcome.Failed
                // reaches this branch -- DeliveredUnconfirmed is handled above and
                // deliberately does NOT auto-void (see DispenseOutcome's doc comment).
                val failReason = (outcome as? DispenseOutcome.Failed)?.reason ?: "unknown"
                DiagnosticManager.reportError(deviceSn, "HARDWARE_PULSE_FAIL", severity = "CRITICAL", trace = failReason)
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

    /** Scale-up-from-zero reveal with overshoot, used for the terminal check/error icon. */
    private fun popIcon(view: ImageView?) {
        view ?: return
        view.scaleX = 0f
        view.scaleY = 0f
        view.animate().scaleX(1f).scaleY(1f).setDuration(400).setInterpolator(OvershootInterpolator()).start()
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
        // Uses the app's own palette (not raw Color.RED/GREEN/YELLOW primaries)
        // so this status dot doesn't clash with everything else on screen.
        when {
            isLocked -> indicator.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_dark))
            !isSerialOk || !isDbOk || !isKeyOk -> indicator.setBackgroundColor(ContextCompat.getColor(this, R.color.coral_red))
            isSimulationMode -> indicator.setBackgroundColor(ContextCompat.getColor(this, R.color.gold_accent))
            else -> indicator.setBackgroundColor(ContextCompat.getColor(this, R.color.emerald_green))
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
            // Was hardcoded to the literal "1234" -- AppConfig.KioskSettings
            // already has a cloud-configurable maintenance_pin field (same
            // "1234" default) that nothing ever actually read.
            val expectedPin = ConfigManager.getConfig()?.settings?.maintenance_pin ?: "1234"
            if (pin == expectedPin) {
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
        dialog.findViewById<Button>(R.id.btn_test_4).setOnClickListener {
            applyClickFeedback(it)
            sendTestCmd("AA 01 04 55")
        }
        dialog.findViewById<Button>(R.id.btn_test_6).setOnClickListener {
            applyClickFeedback(it)
            sendTestCmd("AA 01 06 55")
        }
        dialog.findViewById<Button>(R.id.btn_test_8).setOnClickListener {
            applyClickFeedback(it)
            sendTestCmd("AA 01 08 55")
        }
        dialog.findViewById<Button>(R.id.btn_test_stop).setOnClickListener {
            applyClickFeedback(it)
            sendTestCmd("AA 00 00 55")
        }

        // 4. Peripherals
        dialog.findViewById<Button>(R.id.btn_test_scan).setOnClickListener {
            applyClickFeedback(it)
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
            applyClickFeedback(it)
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
            applyClickFeedback(it)
            Toast.makeText(this, "System will hang in 2s. Watchdog should reboot.", Toast.LENGTH_LONG).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                while(true) { /* INFINITE HANG */ }
            }, 2000)
        }

        // 6. Diagnostics
        dialog.findViewById<Button>(R.id.btn_test_db).setOnClickListener {
            applyClickFeedback(it)
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
            applyClickFeedback(it)
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
            applyClickFeedback(it)
            TtsManager.speak("GS-SSP system voice test successful. Speaker and volume are operational.")
            Toast.makeText(this, "Playing Test Voice...", Toast.LENGTH_SHORT).show()
        }

        dialog.findViewById<Button>(R.id.btn_test_nfc).setOnClickListener {
            applyClickFeedback(it)
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
            applyClickFeedback(it)
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

    // --- UI Refinement Animations ---

    private fun startLaserAnimation() {
        val laserLine = findViewById<View>(R.id.view_laser_line) ?: return
        laserAnimator = ObjectAnimator.ofFloat(laserLine, "translationY", 0f, 150f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopLaserAnimation() {
        laserAnimator?.cancel()
    }

    private fun start3DStatusPulse() {
        val indicator = findViewById<View>(R.id.view_health_indicator) ?: return
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.3f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                indicator.scaleX = scale
                indicator.scaleY = scale
            }
            start()
        }
    }

    private fun stop3DStatusPulse() {
        pulseAnimator?.cancel()
    }

    /**
     * Unified rhythm-based animation for card tapping.
     * Synchronizes card vertical movement with a triple-ripple effect.
     */
    private fun startCoordinatedTapAnimation(dialog: Dialog) {
        val animatedCard = dialog.findViewById<View>(R.id.animated_card) ?: return
        val ring1 = dialog.findViewById<View>(R.id.view_tap_ring_1) ?: return
        val ring2 = dialog.findViewById<View>(R.id.view_tap_ring_2) ?: return
        val ring3 = dialog.findViewById<View>(R.id.view_tap_ring_3) ?: return

        coordinatedTapAnimator = AnimatorSet().apply {
            // 1. Card Movement (1800ms cycle)
            val cardAnim = ObjectAnimator.ofFloat(animatedCard, "translationY", 100f, -60f).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
            }

            // 2. Triple Ripple (timed to trigger at peak of card movement)
            val createRipple = { view: View, delay: Long ->
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(view, "scaleX", 0.8f, 1.8f),
                        ObjectAnimator.ofFloat(view, "scaleY", 0.8f, 1.8f),
                        ObjectAnimator.ofFloat(view, "alpha", 0.8f, 0f)
                    )
                    duration = 1200
                    startDelay = delay
                }
            }

            val ripple1 = createRipple(ring1, 0)
            val ripple2 = createRipple(ring2, 200)
            val ripple3 = createRipple(ring3, 400)

            val rippleGroup = AnimatorSet().apply {
                playTogether(ripple1, ripple2, ripple3)
            }

            // Coordination logic: trigger ripples periodically
            val loopHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val rippleRunnable = object : Runnable {
                override fun run() {
                    if (dialog.isShowing) {
                        rippleGroup.start()
                        loopHandler.postDelayed(this, 2000)
                    }
                }
            }

            play(cardAnim)
            loopHandler.post(rippleRunnable)
            start()
        }
    }

    private fun stopCoordinatedTapAnimation() {
        coordinatedTapAnimator?.cancel()
    }

    private fun animatePrice(textView: TextView, start: Int, end: Int) {
        ValueAnimator.ofInt(start, end).apply {
            duration = 1000
            addUpdateListener { anim ->
                textView.text = "$${anim.animatedValue}"
            }
            start()
        }
    }
}
