package com.goldsky.ssp.feature.wash

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
import android.os.Handler
import android.os.Looper
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
import com.goldsky.ssp.DeviceAdapter
import com.goldsky.ssp.common.QrUtils
import com.goldsky.ssp.common.TtsManager
import com.goldsky.ssp.core.ui.R
import com.goldsky.ssp.dispense.DispenseEngine
import com.goldsky.ssp.dispense.DispenseJob
import com.goldsky.ssp.dispense.DispenseOutcome
import com.goldsky.ssp.model.PaymentMethodMode
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.WashPackage
import com.goldsky.ssp.model.forVertical
import com.goldsky.ssp.payment.*
import com.goldsky.ssp.payment.hardware.DeclineReason
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.ui.BaseAdActivity
import com.goldsky.ssp.ui.VipActivity
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull


/**
 * Main Controller Activity. Displays service options, manages payment modals,
 * operates serial communication to trigger hardware relalys.
 */
class MainActivity : BaseAdActivity() {

    companion object {
        // Only this vertical's packages -- the org's config also carries other
        // terminals' products (e.g. Aegis Timer's vacuum packages), see forVertical.
        private const val WASH_VERTICAL = "WASH"
        // How long the machine-error screen waits for the automatic VOID/REFUND
        // before telling the customer to see the attendant instead.
        private const val REVERSAL_WAIT_MS = 90_000L

        // docs/coupon_redemption_integration.md §4.2: real coupon codes are
        // exactly 8 chars of random alphanumeric (shortened 2026-09-19 from
        // the original 16+ char UUID-derived format, for printing/on-screen
        // display -- see issue_compensation_coupon()'s comment). Set below
        // that real length, not equal to it, so a genuine code is never
        // rejected by this floor; member codes are the separate,
        // exactly-6-char format (shortened from 12, 2026-09-20) checked
        // elsewhere in initCouponScan()).
        // Used only to pick which rejection message to show after the
        // server has already said "not_found" -- see that call site's
        // comment for why this must never gate the RPC call itself.
        private const val MIN_PLAUSIBLE_COUPON_CODE_LENGTH = 6

        // The vendor scan SDK has no timeout of its own -- foundBarcode()
        // simply never fires if nothing is ever presented to the camera, so
        // without this the preview stayed open indefinitely with no way back
        // to the idle screen (confirmed on real WizarPOS Q3mini hardware,
        // 2026-09-19). 15s is enough time to position a code in front of the
        // camera without leaving a customer stuck on a dead scan screen.
        private const val SCAN_TIMEOUT_MS = 15000L
    }

    private lateinit var layoutPackageSelection: ConstraintLayout
    private lateinit var layoutWorking: ConstraintLayout

    // State Variables
    private var isWorking = false
    private var isSimulationMode = false
    private var paymentDialog: Dialog? = null
    private var pollingJob: Job? = null
    private var deviceSn: String = "SIMULATOR_SN"
    private var hardwareVendor: String = DeviceAdapter.getRecommendedVendor()
    private var watchdogJob: Job? = null
    private var laserAnimator: ObjectAnimator? = null
    private var coordinatedTapAnimator: AnimatorSet? = null
    private var pulseAnimator: ValueAnimator? = null

    // showScanFeedback()'s own timer, separate from Toast's system-managed
    // one -- lets us control both font size (via tv_scan_feedback's layout)
    // and on-screen duration (Toast caps out around 3.5s regardless of what
    // duration is requested, too short to read at kiosk viewing distance).
    private val scanFeedbackHandler = Handler(Looper.getMainLooper())
    private var scanFeedbackHideRunnable: Runnable? = null

    // Forces the scan session closed after SCAN_TIMEOUT_MS of no result --
    // see initCouponScan().
    private val scanTimeoutHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null
    private var isScanTimingOut = false

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
    // Card details the terminal reported for the card payment in flight
    // (set in initCardPayment's callback, consumed once by startFinalizationSequence).
    private var pendingCardInfo: com.goldsky.ssp.payment.hardware.IPaymentProvider.CardInfo? = null
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

        DeviceRepository.init(this)
        DeviceAccessManager.init(this)

        // Initialize Hardware Layer (ID TECH / PAX)
        DeviceRepository.persistHardwareVendor(hardwareVendor)
        val hardware = HardwareFactory.getHardwareProvider(hardwareVendor)
        hardware.init(this)
        hardware.registerLifecycle(this, this)

        setupClickListeners()
        setupMaintenanceTrigger()

        // Flavor-specific UI logic
        val currentFlavor = BuildConfig.FLAVOR
        if (currentFlavor == "wash") {
            if (DeviceAdapter.isRichUiSupported()) {
                Log.d("SSP_UI", "IM30 detected on wash flavor, showing rich UI")
            } else {
                Log.d("SSP_UI", "Non-IM30 terminal on wash flavor, showing compact maintenance UI")
                // In a real app, we'd switch layouts or activities here.
                // For now, per instructions, we just ensure it builds and runs current logic.
            }
        } else {
            Log.d("SSP_UI", "Flavor '$currentFlavor' detected. Launching generic Maintenance/Selection UI.")
            // Placeholder: for now we just keep current wash UI if it's 'wash', 
            // but we've acknowledged the flavor.
        }

        extractDeviceIdentity()
        initHardwareControl()
        AdManager.init(this)

        RemoteCommandManager.startListening(this, deviceSn, hardwareVendor, object : RemoteCommandManager.CommandListener {
            override fun onSyncRequested() {
                loadInitialConfig(DeviceRepository.getPersistedOrgId())
            }

            override fun onLockRequested(locked: Boolean) {
                if (locked) {
                    paymentDialog?.dismiss()
                    Toast.makeText(this@MainActivity, "Terminal Locked Remotely", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Terminal Unlocked Remotely", Toast.LENGTH_LONG).show()
                }
                performHealthCheck()
            }
        })

        ShadowManager.startSync(this, deviceSn)

        HardwareFactory.getSerialProvider(this, hardwareVendor).open(this)
        
        performHealthCheck()
        startWatchdog()

        // Update version display
        findViewById<TextView>(R.id.tv_app_version)?.text = "v${BuildConfig.VERSION_NAME}"
    }

    private fun initHardwareControl() {
        // Now managed via HardwareProvider in HAL
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
                val hardware = com.goldsky.ssp.payment.hardware.HardwareFactory.getHardwareProvider(hardwareVendor)
                deviceSn = hardware.getSerialNumber(this)
                Log.i("SSP_IDENTITY", "Hardware SN ($hardwareVendor): $deviceSn")
            } catch (e: Exception) {
                Log.e("SSP_IDENTITY", "Failed to get hardware SN: ${e.message}")
            }
        }

        // Persist so background components without DAL access (HeartbeatWorker)
        // can still identify the device instead of using a placeholder.
        com.goldsky.ssp.payment.DeviceRepository.persistDeviceSn(deviceSn)

        // Offline-first: load immediately using whatever org_id was cached
        // from a previous successful identity sync (null on first-ever
        // launch), so the UI is populated from cache/assets right away
        // rather than blocking on the network calls below.
        loadInitialConfig(com.goldsky.ssp.payment.DeviceRepository.getPersistedOrgId())

        // Async: authenticate both Supabase clients, register the device,
        // link its auth session (device_auth_map) to learn/refresh its
        // org_id, then reload config now that the org is freshly known --
        // covers first-ever launch (no cached org_id yet) and an org
        // reassignment happening server-side between launches.
        CoroutineScope(Dispatchers.Main).launch {
            SupabaseClientProvider.ensureAuthenticated()
            com.goldsky.ssp.payment.DeviceRepository.registerDevice(deviceSn, BuildConfig.VERSION_NAME)
            val identity = com.goldsky.ssp.payment.DeviceRepository.syncDeviceIdentity(deviceSn)
            DeviceAccessManager.applyActiveState(identity?.is_active)
            performHealthCheck()
            if (identity?.org_id != null) {
                loadInitialConfig(identity.org_id)
            }
            // Card sales left PENDING by a crash: reverse the approved ones
            // (charged but never washed), decline the rest -- only while no
            // customer payment is in flight.
            delay(15_000)
            if (!paymentInFlight) runCatching { com.goldsky.ssp.payment.PendingResolver.resolve(this@MainActivity) }
        }
    }

    override fun onResume() {
        super.onResume()
        applyKioskWindowFlags()
        resetAdTimer()
        // Enable scanner LED for voucher scan on main menu
        val scanner = com.goldsky.ssp.payment.hardware.HardwareFactory.getScannerProvider(this, hardwareVendor)
        scanner.setScannerLed(true)
        performHealthCheck()
        startLaserAnimation()
        start3DStatusPulse()
    }

    override fun onPause() {
        super.onPause()
        stopAdTimer()
        // Disable scanner LED when leaving main menu
        val scanner = com.goldsky.ssp.payment.hardware.HardwareFactory.getScannerProvider(this, hardwareVendor)
        scanner.setScannerLed(false)
        stopLaserAnimation()
        stop3DStatusPulse()
    }

    private fun setupClickListeners() {
        // Long press the version label (bottom-left corner) to toggle
        // Simulation Mode -- moved off img_logo, see tv_app_version's own
        // layout comment for why.
        findViewById<View>(R.id.tv_app_version).setOnLongClickListener {
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

        listOf(R.id.card_4, R.id.card_5, R.id.card_6, R.id.card_7, R.id.card_8).forEach { id ->
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
            refreshProductsUI(config.products.forVertical(WASH_VERTICAL))
            
            // Sync dynamic TTS language
            TtsManager.setLocale(config.settings.locale_tag)
            
            // Apply tenant branding
            BrandingManager.applyLogo(findViewById(R.id.img_logo), config.branding)
            BrandingManager.applyHeader(findViewById(R.id.tv_brand_title), findViewById(R.id.tv_title), config.branding, getString(R.string.header_default_title))
        }
    }

    /**
     * Dynamically binds products to the UI cards.
     */
    private fun refreshProductsUI(products: List<Product>) {
        val cards = listOf(
            arrayOf(R.id.card_4, R.id.tv_price_4, R.id.tv_label_4, R.id.tv_duration_4),
            arrayOf(R.id.card_5, R.id.tv_price_5, R.id.tv_label_5, R.id.tv_duration_5),
            arrayOf(R.id.card_6, R.id.tv_price_6, R.id.tv_label_6, R.id.tv_duration_6),
            arrayOf(R.id.card_7, R.id.tv_price_7, R.id.tv_label_7, R.id.tv_duration_7),
            arrayOf(R.id.card_8, R.id.tv_price_8, R.id.tv_label_8, R.id.tv_duration_8)
        )

        // Iterate exactly 5 times to ensure all $4-$8 buttons are handled
        for (index in 0 until 5) {
            val (cardId, priceId, labelId, durationId) = cards[index]
            val cardView = findViewById<View>(cardId)
            val priceView = findViewById<TextView>(priceId)
            val labelView = findViewById<TextView>(labelId)
            val durationView = findViewById<TextView>(durationId)

            val product = products.getOrNull(index)
            if (product != null) {
                cardView.visibility = View.VISIBLE
                val oldPrice = priceView.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0
                val newPrice = product.price_cents / 100
                animatePrice(priceView, oldPrice, newPrice)
                labelView.text = product.name
                // duration_sec rides in the same flexible `attributes` JSONB
                // as serial_hex -- falls back to the price-as-minutes
                // convention the default config already uses (1 dollar = 1
                // minute) if a product omits it rather than showing nothing.
                val durationSec = product.attributes?.get("duration_sec")?.jsonPrimitive?.intOrNull
                    ?: (newPrice * 60)
                durationView.text = "${durationSec / 60} min"

                cardView.setOnClickListener {
                    applyClickFeedback(it)
                    val serialHex = product.attributes?.get("serial_hex")?.jsonPrimitive?.contentOrNull
                        ?: "AA 01 ${"%02X".format(newPrice)} 55"
                    startPackagePurchaseFlow(product.price_cents, serialHex, product.id)
                }
            } else {
                // Fallback: If cloud/cache doesn't have 5 products, show the requested defaults
                val fallbackPrice = index + 4
                cardView.visibility = View.VISIBLE
                priceView.text = "$$fallbackPrice"
                labelView.text = when(fallbackPrice) {
                    4 -> "Starter"
                    5 -> "Basic"
                    6 -> "Deluxe"
                    7 -> "Extra"
                    8 -> "Premium"
                    else -> "Wash"
                }
                durationView.text = "$fallbackPrice min"
                cardView.setOnClickListener {
                    applyClickFeedback(it)
                    val hex = "AA 01 ${"%02X".format(fallbackPrice)} 55"
                    startPackagePurchaseFlow(fallbackPrice * 100, hex)
                }
            }
        }
    }

    private fun showCustomAmountDialog() {
        stopAdTimer()
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_custom_amount)
        
        var amount = 10
        val flipTens = dialog.findViewById<com.goldsky.ssp.view.FlipDigitView>(R.id.flip_tens)
        val flipOnes = dialog.findViewById<com.goldsky.ssp.view.FlipDigitView>(R.id.flip_ones)
        
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
     * by format (§2.1): a 6-character alphanumeric code (shortened from 12,
     * 2026-09-20) is a member QR code, anything else is a coupon/voucher
     * code. The client never judges
     * a coupon's validity itself -- redeem_coupon() does that atomically,
     * server-side; the client only routes the result.
     */
    private fun initCouponScan() {
        if (paymentDialog?.isShowing == true) return // a payment is already in flight, ignore

        val scanner = HardwareFactory.getScannerProvider(this, hardwareVendor)

        // Cancel any previous pending timeout before scheduling a new one --
        // re-tapping the scan belt while a scan is already in flight must not
        // leave two timeouts racing against each other.
        scanTimeoutRunnable?.let { scanTimeoutHandler.removeCallbacks(it) }
        // stopScan() below makes the vendor SDK invoke its own foundBarcode()
        // callback with a "cancelled" result code as a side effect (confirmed
        // on real WizarPOS Q3mini hardware: closing the scanner ourselves
        // still fires onScanFailure("Scan error: -1") a moment later) -- this
        // flag lets that expected, self-inflicted failure be silently
        // ignored instead of immediately overwriting the "No code detected"
        // message with the generic "couldn't read that code" one.
        isScanTimingOut = false
        val timeoutRunnable = Runnable {
            Log.w("MainActivity", "Coupon scan timed out after ${SCAN_TIMEOUT_MS}ms with no result")
            isScanTimingOut = true
            scanner.stopScan()
            showScanFeedback(getString(R.string.toast_scan_timeout))
        }
        scanTimeoutRunnable = timeoutRunnable
        scanTimeoutHandler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS)

        scanner.startScan(object : com.goldsky.ssp.payment.hardware.IScannerProvider.ScanCallback {
            override fun onScanSuccess(result: String) {
                scanTimeoutRunnable?.let { scanTimeoutHandler.removeCallbacks(it) }
                val scanned = result.trim()
                // Length + suffix only, not the full code -- enough to
                // cross-reference against the coupons table when a
                // redemption result looks wrong, without logging a
                // reusable code in full. Added after a real "already_used"
                // report that turned out to be a stale QR code still on
                // screen (a coupon genuinely already redeemed, correctly
                // rejected) rather than a bug -- there was previously no way
                // to tell which code the camera actually decoded without
                // this, 2026-09-19.
                Log.d("MainActivity", "Scanned code: len=${scanned.length} suffix=${scanned.takeLast(6)}")
                runOnUiThread {
                    if (Regex("^[A-Za-z0-9]{6}$").matches(scanned)) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val cardUid = VipRepository.resolveCardUidByQrCode(scanned)
                            if (cardUid != null) {
                                pendingCoupon = null
                                pendingVipCardUid = cardUid
                                // Best-effort lookup for the card no./balance line; a
                                // failure here must not block the flow, the deduct RPC
                                // re-validates the card server-side anyway.
                                val card = VipRepository.getVipCard(cardUid)
                                val base = getString(R.string.toast_member_recognized)
                                showScanFeedback(if (card != null) "${vipCardSummary(card)}\n$base" else base)
                            } else {
                                showScanFeedback(getString(R.string.toast_member_code_invalid))
                            }
                        }
                    } else {
                        CoroutineScope(Dispatchers.Main).launch {
                            // peek_coupon() only -- redeem_coupon() (the real,
                            // atomic consumption) is deliberately deferred until
                            // the customer confirms below, see
                            // showCouponConfirmDialog()'s comment for why.
                            when (val peek = CouponRepository.peekCoupon(scanned, deviceSn)) {
                                is CouponPeekResult.Success -> {
                                    val matchedProduct = findMatchingLocalProduct(peek)
                                    if (matchedProduct != null) {
                                        pendingVipCardUid = null
                                        showCouponConfirmDialog(scanned, peek, matchedProduct)
                                    } else if (peek.applicableProductId == null) {
                                        // Generic "any package" discount (no specific package to
                                        // pre-select and confirm) -- unchanged from the original
                                        // flow: consume now, let the customer pick a package
                                        // afterward, discount applied on top of it.
                                        when (val redemption = CouponRepository.redeemCoupon(scanned, deviceSn)) {
                                            is CouponRedeemResult.Success -> {
                                                pendingVipCardUid = null
                                                pendingCoupon = redemption
                                                showScanFeedback(getString(R.string.toast_coupon_applied))
                                            }
                                            is CouponRedeemResult.Rejected, CouponRedeemResult.NetworkError -> {
                                                showScanFeedback(getString(couponRejectionMessageRes(scanned)))
                                            }
                                        }
                                    } else {
                                        // Bound to a specific package (applicable_product_id set),
                                        // but nothing in the local catalog matches that id -- most
                                        // likely the device's synced config and the portal's
                                        // products table have drifted (see products vs
                                        // app_configurations comment in docs/supabase_full_schema.sql).
                                        // Never consumed here (peek_coupon only) -- staff can still
                                        // redeem it manually.
                                        showScanFeedback(getString(R.string.toast_coupon_see_staff))
                                    }
                                }
                                is CouponPeekResult.Rejected, CouponPeekResult.NetworkError -> {
                                    showScanFeedback(getString(couponRejectionMessageRes(scanned)))
                                }
                            }
                        }
                    }
                }
            }
            override fun onScanFailure(errorMsg: String) {
                scanTimeoutRunnable?.let { scanTimeoutHandler.removeCallbacks(it) }
                if (isScanTimingOut) {
                    // Our own timeout already stopped the scan and showed its
                    // own message -- this is that stop's self-inflicted
                    // cancellation callback arriving, not a genuine failure.
                    isScanTimingOut = false
                    Log.d("MainActivity", "Scan stop after timeout confirmed: $errorMsg")
                    return
                }
                Log.w("MainActivity", "Coupon scan failed: $errorMsg")
                runOnUiThread { showScanFeedback(getString(R.string.toast_scan_failed)) }
            }
        })
    }

    /**
     * docs/coupon_redemption_integration.md §2.1/§4.2: real coupon codes are
     * exactly 8 random alphanumeric chars (member codes are the separate,
     * exactly-6-char format, shortened from 12 on 2026-09-20, checked
     * earlier in initCouponScan()). Shared by
     * both peek_coupon() rejection paths (initial scan, and the confirm
     * dialog's own redeem_coupon() call) so both apply the exact same
     * §4.6 anti-probing rule consistently.
     */
    private fun couponRejectionMessageRes(scannedCode: String): Int {
        return if (scannedCode.length < MIN_PLAUSIBLE_COUPON_CODE_LENGTH) {
            R.string.toast_code_unrecognized
        } else {
            R.string.toast_coupon_invalid
        }
    }

    /**
     * Resolves a peeked coupon to a single package this terminal can
     * actually dispense, so initCouponScan() can skip package selection and
     * go straight to a confirm dialog. Two cases:
     * - [CouponPeekResult.Success.applicableProductId] set (Cael's Campaign
     *   Builder device/package picker) -- match it by id against the local
     *   catalog.
     * - Unset but FIXED_OFF (e.g. a compensation coupon, which is always
     *   FIXED_OFF with value = the amount to offset, never bound to a
     *   product -- see issue_compensation_coupon()) -- match by price
     *   instead: a coupon meant to fully offset one specific wash is, by
     *   construction, for whichever package costs exactly that much. Only
     *   matches when exactly one local package has that price -- an
     *   ambiguous or zero match falls back to manual selection rather than
     *   guessing.
     */
    private fun findMatchingLocalProduct(peek: CouponPeekResult.Success): Product? {
        val localProducts = ConfigManager.getConfig()?.products?.forVertical(WASH_VERTICAL) ?: emptyList()
        return when {
            peek.applicableProductId != null ->
                localProducts.firstOrNull { it.id == peek.applicableProductId }
            peek.type == "FIXED_OFF" ->
                localProducts.filter { it.price_cents == peek.value }.singleOrNull()
            else -> null
        }
    }

    /** Same fallback convention refreshProductsUI() uses for a product missing serial_hex. */
    private fun serialHexOf(product: Product): String {
        return product.attributes?.get("serial_hex")?.jsonPrimitive?.contentOrNull
            ?: "AA 01 ${"%02X".format(product.price_cents / 100)} 55"
    }

    /**
     * Shows the "do you want to use this coupon" confirmation and only calls
     * the real, consuming redeem_coupon() if the customer confirms.
     * peek_coupon() (already called before this) never touches uses_count,
     * so declining here, or the customer just walking away, leaves the
     * coupon completely untouched -- "cancel" is a real cancel, not a
     * courtesy label on an already-spent coupon. This is the reason
     * initCouponScan() calls peek_coupon() instead of redeem_coupon()
     * directly: redeem_coupon() consumes atomically on every call (by
     * design, to block concurrent double-redemption -- see its own
     * comment), so a confirm/cancel dialog can only be honest if placed
     * before that call, not after it.
     */
    private fun showCouponConfirmDialog(code: String, peek: CouponPeekResult.Success, product: Product) {
        val originalPriceCents = product.price_cents
        val discountedCents = when (peek.type) {
            "PERCENT_OFF" -> originalPriceCents - (originalPriceCents * peek.value / 100)
            "FIXED_OFF" -> maxOf(0, originalPriceCents - peek.value)
            "FREE_WASH" -> 0
            else -> originalPriceCents
        }
        val expiryLine = peek.expiresAt?.let { getString(R.string.coupon_confirm_expires, it.replace("T", " ").take(16)) } ?: ""
        val message = if (discountedCents <= 0) {
            getString(R.string.coupon_confirm_message_free, product.name, originalPriceCents / 100.0, expiryLine)
        } else {
            getString(R.string.coupon_confirm_message_discounted, product.name, originalPriceCents / 100.0, discountedCents / 100.0, expiryLine)
        }

        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_coupon_confirm)
        dialog.findViewById<TextView>(R.id.tv_coupon_confirm_message).text = message

        dialog.findViewById<Button>(R.id.btn_coupon_confirm).setOnClickListener {
            applyClickFeedback(it)
            dialog.dismiss()
            CoroutineScope(Dispatchers.Main).launch {
                when (val redemption = CouponRepository.redeemCoupon(code, deviceSn)) {
                    is CouponRedeemResult.Success -> {
                        pendingCoupon = redemption
                        startPackagePurchaseFlow(originalPriceCents, serialHexOf(product), product.id)
                    }
                    is CouponRedeemResult.Rejected, CouponRedeemResult.NetworkError -> {
                        // Consumed elsewhere in the gap between peek and confirm --
                        // an existing, already-handled outcome (already_used), not new.
                        showScanFeedback(getString(couponRejectionMessageRes(code)))
                    }
                }
            }
        }
        dialog.findViewById<Button>(R.id.btn_coupon_cancel).setOnClickListener {
            applyClickFeedback(it)
            dialog.dismiss()
            showScanFeedback(getString(R.string.toast_coupon_cancelled))
        }
        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.show()
    }

    /**
     * Shows [message] in a large, kiosk-readable banner (see
     * tv_scan_feedback in activity_main.xml) for [durationMs], then hides it.
     * Plain Toast tops out around ~3.5s on LENGTH_LONG regardless of what's
     * requested, and its text size isn't customizable without a deprecated
     * custom-view Toast -- neither is good enough to read from normal kiosk
     * standing distance, so this manages its own visibility/timer instead.
     * A second call while one is already showing replaces the pending hide,
     * it doesn't stack -- exactly one scan result is ever pending at a time
     * (see pendingVipCardUid/pendingCoupon's own single-slot comment).
     */
    private fun showScanFeedback(message: String, durationMs: Long = 4500L) {
        val tv = findViewById<TextView>(R.id.tv_scan_feedback)
        tv.text = message
        tv.visibility = View.VISIBLE
        scanFeedbackHideRunnable?.let { scanFeedbackHandler.removeCallbacks(it) }
        val hideRunnable = Runnable { tv.visibility = View.GONE }
        scanFeedbackHideRunnable = hideRunnable
        scanFeedbackHandler.postDelayed(hideRunnable, durationMs)
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
                Log.i("SSP_TEST", "Direct CARD_ONLY path triggered")
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
            Log.i("SSP_TEST", "Card selected in dialog")
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
            if (hardwareVendor.uppercase() == "WIZARPOS") {
                // WizarPOS: PAYWizard app handles its own fullscreen payment UI, status, and voice guidance.
                // We should completely skip our own card guidance screen and voice announcements to avoid overlap.
                layoutCard.visibility = View.GONE
                layoutQr.visibility = View.GONE
                initCardPayment(priceInCents, startHex, dialog, productId)
            } else {
                layoutCard.visibility = View.VISIBLE
                layoutQr.visibility = View.GONE
                startCoordinatedTapAnimation(dialog)
                
                // DYNAMIC VOICE: Localized amount and instruction
                TtsManager.announceAmount(priceInCents, "Total amount is")
                TtsManager.speak(getString(R.string.prompt_card_guide))

                // Presence-check step -- a no-op pass-through for ID TECH (which has
                // no cheap detection separate from the real sale; see
                // IdTechPaymentProvider.startCardDetection's doc), so this resolves
                // immediately and initCardPayment() -> startSale() is what actually
                // arms the reader for insert/tap/swipe.
                val provider = PaymentProviderFactory.getPaymentProvider(this, hardwareVendor)
                provider.startCardDetection(priceInCents, object : com.goldsky.ssp.payment.hardware.IPaymentProvider.PaymentCallback {
                    override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                        initCardPayment(priceInCents, startHex, dialog, productId)
                    }
                    override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                        Log.e("MainActivity", "Card detection error: $errorMsg")
                        if (isHardwareFault) {
                            DiagnosticManager.reportError(deviceSn, "IDTECH_HARDWARE_FAULT", severity = "CRITICAL", trace = errorMsg)
                        }
                    }
                    override fun onProgress(message: String) {
                        runOnUiThread { dialog.findViewById<TextView>(R.id.tv_status_msg)?.text = message }
                    }
                })
            }
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
                val provider = PaymentProviderFactory.getPaymentProvider(this, hardwareVendor)
                provider.cancelCurrentTransaction()
                
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
            HardwareFactory.getScannerProvider(this, hardwareVendor).stopScan()
            pollingJob?.cancel()
            stopCoordinatedTapAnimation()
        }
        dialog.show()
    }


    private fun formatCents(cents: Int): String = "$" + String.format(java.util.Locale.US, "%.2f", cents / 100.0)

    // display_card_number is what's printed on the card; card_uid is the NFC
    // serial and not human-legible, so it's only a last-resort fallback.
    private fun vipCardNumber(card: VipCard): String = card.display_card_number ?: card.card_uid

    private fun vipCardSummary(card: VipCard): String =
        "Card No. ${vipCardNumber(card)}  |  Balance: ${formatCents(card.balance_cents)}"

    private fun initVipPayment(uid: String, priceInCents: Int, startHex: String, dialog: Dialog, productId: String? = null) {
        val layoutStatus = dialog.findViewById<ConstraintLayout>(R.id.layout_status_overlay)
        val tvStatus = dialog.findViewById<TextView>(R.id.tv_status_msg)

        layoutStatus.visibility = View.VISIBLE
        tvStatus.text = "VIP Card Detected\nVerifying Status..."

        paymentInFlight = true
        CoroutineScope(Dispatchers.Main).launch {
            // Tiered Discount Logic: fetch card details first
            val card = VipRepository.getVipCard(uid)
            val finalPrice = if (card != null && card.is_active) {
                val discountFactor = when (card.tier) {
                    "PLATINUM" -> 0.85 // 15% off
                    "GOLD" -> 0.90     // 10% off
                    else -> 1.0
                }
                val discounted = (priceInCents * discountFactor).toInt()
                if (discounted < priceInCents) {
                    tvStatus.text = "${card.tier} MEMBER\n${(100 - discountFactor * 100).toInt()}% Discount Applied!"
                    delay(1500)
                }
                discounted
            } else {
                priceInCents
            }

            tvStatus.text = listOfNotNull(card?.let { vipCardSummary(it) }, "Authorizing Payment...").joinToString("\n")
            when (val result = VipRepository.deductBalance(uid, finalPrice)) {
                is VipDeductResult.Success -> {
                    tvStatus.text = listOfNotNull(
                        "VIP Payment Successful!",
                        card?.let { "Card No. ${vipCardNumber(it)}" },
                        "Remaining Balance: ${formatCents(result.newBalanceCents)}"
                    ).joinToString("\n")
                    delay(1500)
                    startFinalizationSequence(finalPrice, startHex, "VIP_${uid}_${System.currentTimeMillis()}", dialog, productId = productId, paymentMethod = "VIP_CARD", entryMode = "NFC_TAP", vipCardUid = uid)
                }
                is VipDeductResult.Rejected -> {
                    paymentInFlight = false
                    val message = when (result.reason) {
                        "card_inactive" -> "This VIP Card Has Been Deactivated"
                        "card_not_found" -> "VIP Card Not Recognized"
                        else -> "VIP Card Balance Insufficient"
                    }
                    val detail = if (card != null && result.reason == "insufficient_balance")
                        "\n${vipCardSummary(card)}\nAmount Due: ${formatCents(finalPrice)}" else ""
                    Toast.makeText(this@MainActivity, message + detail, Toast.LENGTH_LONG).show()
                    if (result.reason == "insufficient_balance") {
                        TtsManager.speak(getString(R.string.voice_vip_low_balance))
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
        // Only interrupt the customer when the payment can't succeed anyway
        // (balance short / card deactivated) -- say so up front instead of a
        // rejected deduct. Otherwise go straight to payment; the card no. and
        // balance are shown in the payment status (see initVipPayment).
        CoroutineScope(Dispatchers.Main).launch {
            val card = VipRepository.getVipCard(uid)
            // card == null: lookup failed (network) -- don't block, the deduct
            // RPC still validates server-side and NetworkError falls back to
            // tap-card.
            if (card != null && (!card.is_active || card.balance_cents < priceInCents)) {
                showVipUnavailableDialog(card, priceInCents)
            } else {
                proceedVipPayment(priceInCents, startHex, uid, productId)
            }
        }
    }

    /** pendingVipCardUid is already cleared, so Back lands on package selection with no card bound. */
    private fun showVipUnavailableDialog(card: VipCard, priceInCents: Int) {
        val no = "Card No. ${vipCardNumber(card)}"
        val message = if (!card.is_active) {
            "$no\nThis VIP card has been deactivated.\nPlease use another payment method."
        } else {
            "$no\nBalance: ${formatCents(card.balance_cents)}\nAmount Due: ${formatCents(priceInCents)}\n" +
                "Short by ${formatCents(priceInCents - card.balance_cents)}.\nPlease top up, or choose another package / payment method."
        }
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_coupon_confirm)
        dialog.findViewById<TextView>(R.id.tv_coupon_confirm_title).text =
            if (card.is_active) "Insufficient Balance" else "Card Deactivated"
        dialog.findViewById<TextView>(R.id.tv_coupon_confirm_message).text = message
        dialog.findViewById<Button>(R.id.btn_coupon_confirm).visibility = View.GONE
        dialog.findViewById<Button>(R.id.btn_coupon_cancel).apply {
            text = "Back"
            setOnClickListener { applyClickFeedback(it); dialog.dismiss() }
        }
        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.show()
        if (card.is_active) TtsManager.speak(getString(R.string.voice_vip_low_balance))
    }

    private fun proceedVipPayment(priceInCents: Int, startHex: String, uid: String, productId: String? = null) {
        val dialog = Dialog(this, R.style.Theme_SSP_Fullscreen)
        dialog.setContentView(R.layout.dialog_payment)
        paymentDialog = dialog

        dialog.findViewById<ConstraintLayout>(R.id.layout_card_guidance).visibility = View.GONE
        dialog.findViewById<ConstraintLayout>(R.id.layout_qr_guidance).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.shared_subtitle).text = getString(R.string.prompt_pay_subtitle, "$${priceInCents / 100}")

        dialog.setOnShowListener { applyKioskWindowFlags() }
        dialog.setOnDismissListener {
            pollingJob?.cancel()
            HardwareFactory.getScannerProvider(this, hardwareVendor).stopScan()
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
            HardwareFactory.getScannerProvider(this, hardwareVendor).stopScan()
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
                startFinalizationSequence(priceInCents, startHex, "MOCK_REF_123", dialog, txRefNum, entryMode = "SIMULATED")
            } else {
                val provider = PaymentProviderFactory.getPaymentProvider(this@MainActivity, hardwareVendor)
                provider.startSale(priceInCents, txRefNum, object : com.goldsky.ssp.payment.hardware.IPaymentProvider.PaymentCallback {
                    override fun onCardInfo(info: com.goldsky.ssp.payment.hardware.IPaymentProvider.CardInfo) {
                        pendingCardInfo = info
                    }
                    override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                        startFinalizationSequence(priceInCents, startHex, refNum, dialog, txRefNum, entryMode = entryMode)
                    }
                    override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                        paymentInFlight = false
                        // Raw provider text ("Payment Error: cancelled by user
                        // (-139)") stays in the log; the customer gets plain words.
                        Log.w("MainActivity", "Card sale failed: $errorMsg (hardwareFault=$isHardwareFault)")
                        val reason = DeclineReason.classify(errorMsg, isHardwareFault)
                        runOnUiThread {
                            val msg = getString(
                                when (reason) {
                                    DeclineReason.CANCELLED -> R.string.pay_result_cancelled
                                    DeclineReason.UNAVAILABLE -> R.string.pay_result_unavailable
                                    DeclineReason.DECLINED -> R.string.pay_result_declined
                                }
                            )
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            if (reason != DeclineReason.CANCELLED) TtsManager.speak(msg)
                            dialog.dismiss()
                            resetAdTimer()
                        }
                        if (isHardwareFault) {
                            DiagnosticManager.reportError(deviceSn, "IDTECH_HARDWARE_FAULT", severity = "CRITICAL", trace = errorMsg)
                        }
                        CoroutineScope(Dispatchers.Main).launch {
                            TransactionRepository.updatePaymentStatus(this@MainActivity, txRefNum, "DECLINED")
                        }
                    }
                    override fun onProgress(message: String) {
                        runOnUiThread { 
                            // Update status msg if available
                            dialog.findViewById<TextView>(R.id.tv_status_msg)?.text = message
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
            val qrBitmap: Bitmap? = QrUtils.generateQrCode(codeUrl, 250, 250)
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
                // qrTxId = txId so the DispenseJob threaded to DispenseEngine carries the
                // SAME id the payment-webhook used for this session (see qrTxId param doc
                // comment below) -- needed so EdgeNexusRemoteAdapter can find the matching
                // device_commands row. refNum stays "" as before (it gates the card
                // void/refund path on hardware failure, which doesn't apply to QR).
                startFinalizationSequence(priceInCents, startHex, "", dialog, productId = productId, paymentMethod = "QR_CODE", qrTxId = txId)
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
        paymentMethod: String = "UNKNOWN",
        entryMode: String? = null,
        // VIP_CARD payments only: recorded on the transaction row so a card's spend can be traced.
        vipCardUid: String? = null,
        // Real tx_id from QrPaymentRepository.createSession/pollUntilPaid, set only
        // by the QR call site. Deliberately kept separate from refNum (rather than
        // passing it as refNum directly) because refNum.isEmpty() also gates the
        // card auto-void/refund branch below on hardware failure -- that branch
        // assumes refNum is a card ecr_ref_num a payment provider can void, which a
        // Stripe/QR tx_id is not. When set, this becomes ecrRefNum so DispenseJob.txRef
        // carries the payment-webhook's own tx_id, letting EdgeNexusRemoteAdapter
        // correlate the device_commands row the webhook inserted for this payment.
        qrTxId: String? = null
    ) {
        val layoutStatus = dialog?.findViewById<ConstraintLayout>(R.id.layout_status_overlay)
        val tvStatus = dialog?.findViewById<TextView>(R.id.tv_status_msg)
        val ivStatusIcon = dialog?.findViewById<ImageView>(R.id.iv_status_icon)
        val layoutWashStepper = dialog?.findViewById<ConstraintLayout>(R.id.layout_wash_stepper)
        val pbWashStages = dialog?.findViewById<ProgressBar>(R.id.pb_wash_stages)
        val tvStageLabel = dialog?.findViewById<TextView>(R.id.tv_stage_label)
        val stageLabels = resources.getStringArray(R.array.wash_stage_labels)
        val ecrRefNum = pendingEcrRefNum ?: qrTxId ?: (if (refNum.isEmpty()) "QR_${System.currentTimeMillis()}" else refNum)
        var washIconAnimator: ObjectAnimator? = null

        CoroutineScope(Dispatchers.Main).launch {
            layoutStatus?.visibility = View.VISIBLE
            // Card payments (pendingEcrRefNum != null) go through PAYWizard,
            // which already shows and speaks its own "transaction successful"
            // confirmation before control returns here -- our own "Transaction
            // Approved" text+TTS on top of that is a redundant second
            // announcement. QR/VIP/free-wash have no other confirmation, so
            // they still get it.
            if (pendingEcrRefNum == null) {
                tvStatus?.text = getString(R.string.status_approved)
                TtsManager.speak(getString(R.string.status_approved))
            }

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
                val cardInfo = pendingCardInfo
                pendingCardInfo = null
                TransactionRepository.updatePaymentStatus(
                    this@MainActivity, pendingEcrRefNum, "PAID", entryMode,
                    paymentMethod = cardInfo?.let { CardTypeClassifier.paymentMethod(it.scheme, it.aid) },
                    cardAid = cardInfo?.aid,
                    cardBin = cardInfo?.bin,
                    cardBrand = cardInfo?.brand
                )
            } else {
                TransactionRepository.recordTransaction(
                    this@MainActivity,
                    TransactionRecord(
                        device_sn = deviceSn,
                        amount = amountCents,
                        payment_status = "PAID",
                        ecr_ref_num = ecrRefNum,
                        payment_method = paymentMethod,
                        product_id = productId,
                        entry_mode = entryMode,
                        vip_card_uid = vipCardUid
                    )
                )
            }

            // Same card-vs-other split as the "Approved" skip above: PAYWizard
            // already showed its own success screen, so these two waiting
            // stages ("Approved" .. "Paid") are redundant on top of it for
            // card payments -- go straight to "Activating Wash Equipment".
            // QR/VIP/free-wash keep the staged readout since they have
            // nothing else confirming payment to the customer.
            if (pendingEcrRefNum == null) {
                delay(1200)
                tvStatus?.text = getString(R.string.status_paid)
                delay(1000)
            }

            tvStatus?.text = getString(R.string.status_sending_command)
            layoutWashStepper?.visibility = View.VISIBLE
            pbWashStages?.progress = 0
            tvStageLabel?.text = stageLabels.firstOrNull()

            // 2. Delegate to the protocol/ack-strategy
            val outcome = DispenseEngine.dispense(
                DispenseJob(pulseAmountCents, startHex, deviceSn, ecrRefNum),
                isSimulationMode,
                HardwareFactory.getSerialProvider(this@MainActivity, hardwareVendor),
                HardwareFactory.getGpioProvider(this@MainActivity, hardwareVendor)
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
                    // Some pulses refused by the hardware: the customer got a
                    // shortened wash for the full price -- someone must look.
                    val partial = outcome.detail?.startsWith(com.goldsky.ssp.dispense.adapter.DigitIoAdapter.PARTIAL_PREFIX) == true
                    DiagnosticManager.reportError(
                        deviceSn,
                        if (partial) "HARDWARE_PARTIAL_DISPENSE" else "HARDWARE_ACK_UNAVAILABLE",
                        severity = if (partial) "CRITICAL" else "INFO",
                        trace = outcome.detail
                    )
                }

                // Receipt printing is cloud-configurable (KioskSettings.print_receipt_enabled) --
                // never blocks or fails the payment flow either way.
                if (ConfigManager.getConfig()?.settings?.print_receipt_enabled == true) {
                    withContext(Dispatchers.IO) {
                        ReceiptPrinterManager.printReceipt(
                            this@MainActivity,
                            ReceiptPrinterManager.ReceiptData(
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
                // Say what is actually happening: the reversal hasn't run yet
                // (this used to announce "Refund fully processed" right here).
                tvStatus?.text = getString(if (refNum.isNotEmpty()) R.string.status_error_reversing else R.string.status_error_contact)
                TtsManager.speak(getString(if (refNum.isNotEmpty()) R.string.tts_error_reversing else R.string.status_error_contact))

                // Industrial Audit: Report hardware failure and trigger VOID
                // (falling back to REFUND automatically if VOID is declined,
                // e.g. because the batch already settled). Only DispenseOutcome.Failed
                // reaches this branch -- DeliveredUnconfirmed is handled above and
                // deliberately does NOT auto-void (see DispenseOutcome's doc comment).
                val failReason = (outcome as? DispenseOutcome.Failed)?.reason ?: "unknown"
                DiagnosticManager.reportError(deviceSn, "HARDWARE_PULSE_FAIL", severity = "CRITICAL", trace = failReason)
                TransactionRepository.updateHardwareStatus(this@MainActivity, ecrRefNum, "HARDWARE_ERROR")

                if (refNum.isNotEmpty()) {
                    val provider = PaymentProviderFactory.getPaymentProvider(this@MainActivity, hardwareVendor)
                    // Wait for the real outcome before telling the customer. A
                    // VOID answers in about a second; the REFUND fallback may
                    // put PAYWizard's card screen up, hence the long ceiling.
                    val result = withTimeoutOrNull(REVERSAL_WAIT_MS) {
                        suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                            provider.voidOrRefund(refNum, amountCents) { success, method ->
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
                                if (cont.isActive) cont.resumeWith(Result.success(success to method))
                            }
                        }
                    }
                    val reversedText = when {
                        result?.first == true && result.second == "REFUND" -> getString(R.string.status_error_refunded)
                        result?.first == true -> getString(R.string.status_error_reversed)
                        else -> getString(R.string.status_error_contact)
                    }
                    if (result == null) Log.w("SSP_HARDWARE", "Reversal still pending after ${REVERSAL_WAIT_MS}ms for $ecrRefNum")
                    tvStatus?.text = reversedText
                    TtsManager.speak(reversedText)
                }
                paymentInFlight = false
                delay(8000)
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
        scanFeedbackHideRunnable?.let { scanFeedbackHandler.removeCallbacks(it) }
        scanTimeoutRunnable?.let { scanTimeoutHandler.removeCallbacks(it) }

        HardwareFactory.getHardwareProvider(hardwareVendor).release()
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
        val isSerialOk = isSimulationMode || HardwareFactory.getSerialProvider(this, hardwareVendor).isOpened()
        val isDbOk = isSimulationMode || ConfigManager.isDatabaseOnline()
        val isLocked = DeviceAccessManager.isLocked()
        val isKeyOk = KeyHealthMonitor.isPaymentAllowed()
        // Previously this light only reflected serial/DB/key health -- a card
        // reader silently going offline (unplugged NEO2, USB fault) left the
        // indicator green with no signal that card payments were dead until
        // a customer actually tried and it failed. isOperational() is cheap
        // (no I/O, just a connection-state check), safe to call every
        // performHealthCheck() tick.
        val isReaderOk = isSimulationMode ||
            com.goldsky.ssp.payment.hardware.HardwareFactory.getHardwareProvider(hardwareVendor).isOperational()

        // Black: admin/remote locked. Red: hardware/DB/key fault. Yellow: sim mode. Green: operational.
        // Uses the app's own palette (not raw Color.RED/GREEN/YELLOW primaries)
        // so this status dot doesn't clash with everything else on screen.
        when {
            isLocked -> indicator.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_dark))
            !isSerialOk || !isDbOk || !isKeyOk || !isReaderOk -> indicator.setBackgroundColor(ContextCompat.getColor(this, R.color.coral_red))
            isSimulationMode -> indicator.setBackgroundColor(ContextCompat.getColor(this, R.color.gold_accent))
            else -> indicator.setBackgroundColor(ContextCompat.getColor(this, R.color.emerald_green))
        }
    }

    /**
     * Hidden trigger for technician menu (7 clicks on logo within 2 seconds).
     */
    private fun setupMaintenanceTrigger() {
        // Moved off img_logo, see tv_app_version's own layout comment for why.
        findViewById<View>(R.id.tv_app_version).setOnClickListener {
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
        dialog.setContentView(R.layout.dialog_maintenance_v2)
        
        val hardware = HardwareFactory.getHardwareProvider(hardwareVendor)

        // 1. Header & Exit
        dialog.findViewById<View>(R.id.btn_close_dash).setOnClickListener { dialog.dismiss() }
        val tvHeaderSn = dialog.findViewById<TextView>(R.id.tv_header_sn)

        // 2. Status Indicators & Telemetry
        val tvNet = dialog.findViewById<TextView>(R.id.tv_net_val)
        val dotNet = dialog.findViewById<View>(R.id.dot_net_status)
        val tvDb = dialog.findViewById<TextView>(R.id.tv_db_val)
        val dotDb = dialog.findViewById<View>(R.id.dot_db_status)
        val tvSerial = dialog.findViewById<TextView>(R.id.tv_serial_val)
        val dotSerial = dialog.findViewById<View>(R.id.dot_serial_status)
        val tvId = dialog.findViewById<TextView>(R.id.tv_id_val)
        val tvSecurity = dialog.findViewById<TextView>(R.id.tv_security_val)
        val dotSecurity = dialog.findViewById<View>(R.id.dot_security_status)
        val tvFw = dialog.findViewById<TextView>(R.id.tv_fw_val)
        val tvHals = dialog.findViewById<TextView>(R.id.tv_uptime_val)
        
        val sn = if (isSimulationMode) "MOCK_SN" else hardware.getSerialNumber(this)
        val fw = if (isSimulationMode) "MOCK_FW" else hardware.getFirmwareVersion()
        tvId.text = "SN: $sn"
        tvHeaderSn.text = "SN: $sn"
        tvFw.text = "FIRMWARE: $fw"
        tvHals.text = "HAL STATUS: ${if (hardware.isOperational()) "OPERATIONAL" else "FAULT"}"

        // Initial health sweep
        val updateHealthUI = {
            val isOnline = isNetworkAvailable(this)
            tvNet.text = if (isOnline) "ONLINE" else "OFFLINE"
            dotNet.backgroundTintList = android.content.res.ColorStateList.valueOf(if (isOnline) getColor(R.color.tech_neon_green) else getColor(R.color.alert_red_bg))

            val dbOk = ConfigManager.isDatabaseOnline()
            tvDb.text = if (dbOk) "SYNCED" else "DISCONN"
            dotDb.backgroundTintList = android.content.res.ColorStateList.valueOf(if (dbOk) getColor(R.color.tech_neon_green) else getColor(R.color.tech_pill_bg))

            val serialOk = HardwareFactory.getSerialProvider(this, hardwareVendor).isOpened()
            tvSerial.text = if (isSimulationMode) "MOCK" else if (serialOk) "ACTIVE" else "STANDBY"
            dotSerial.backgroundTintList = android.content.res.ColorStateList.valueOf(if (serialOk) getColor(R.color.tech_cyan) else getColor(R.color.tech_pill_bg))
            
            val tampered = hardware.getTamperStatus()
            tvSecurity.text = if (tampered) "TAMPERED" else "SECURE"
            tvSecurity.setTextColor(if (tampered) getColor(R.color.tech_tamper_red) else getColor(R.color.text_light))
            dotSecurity.backgroundTintList = android.content.res.ColorStateList.valueOf(if (tampered) getColor(R.color.tech_tamper_red) else getColor(R.color.tech_neon_green))
        }
        updateHealthUI()

        // 3. Command Grid
        // Was sendTestCmd() (Console-serial hex frames) unconditionally for
        // all three buttons -- on Q3mini/IM30 UPT that's a completely
        // different physical circuit from the Digit IO Pulse line (PIN1)
        // DigitIoAdapter actually dispenses on (see the 2026-09-22 Relay/Pulse
        // circuit fix), so on real UPT wash hardware these buttons tested
        // wiring nothing downstream was listening to. sendTestPulses() below
        // routes through whichever circuit DispenseEngine.dispense() itself
        // would pick, so this tool can't drift out of sync with the real
        // dispense path again.
        dialog.findViewById<Button>(R.id.btn_op_relay_4).setOnClickListener {
            applyClickFeedback(it)
            sendTestPulses(4)
        }
        dialog.findViewById<Button>(R.id.btn_op_relay_8).setOnClickListener {
            applyClickFeedback(it)
            sendTestPulses(8)
        }
        dialog.findViewById<Button>(R.id.btn_op_stop).setOnClickListener {
            applyClickFeedback(it)
            if (isUptDigitIoMachine()) {
                // No persistent ON state to stop on the Pulse circuit --
                // triggerLogicPulse is a single self-timed native call, not a
                // held-open relay (see DigitIoAdapter's exception-path comment).
                Toast.makeText(this, "N/A on Digit IO Pulse circuit: each test pulse is a single self-timed call, nothing stays ON to stop", Toast.LENGTH_LONG).show()
            } else {
                sendTestCmd("AA 00 00 55")
            }
        }

        // 4. Peripherals
        dialog.findViewById<Button>(R.id.btn_op_scan).setOnClickListener {
            applyClickFeedback(it)
            initCouponScan()
            dialog.dismiss()
        }

        dialog.findViewById<Button>(R.id.btn_op_nfc).setOnClickListener {
            applyClickFeedback(it)
            Toast.makeText(this, "NFC Probing...", Toast.LENGTH_SHORT).show()
            PaymentProviderFactory.getPaymentProvider(this, hardwareVendor).startCardDetection(0, object : com.goldsky.ssp.payment.hardware.IPaymentProvider.PaymentCallback {
                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "NFC OK: $entryMode ($refNum)", Toast.LENGTH_LONG).show() }
                }
                override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "NFC ERR: $errorMsg", Toast.LENGTH_SHORT).show() }
                }
                override fun onProgress(message: String) {}
            })
        }

        dialog.findViewById<Button>(R.id.btn_op_sync).setOnClickListener {
            applyClickFeedback(it)
            CoroutineScope(Dispatchers.Main).launch {
                it.isEnabled = false
                (it as Button).text = "SYNCING..."

                KeyHealthMonitor.reset()
                val identity = DeviceRepository.syncDeviceIdentity(deviceSn)
                // Was DeviceAccessManager.setRemoteLock(false) unconditionally,
                // called BEFORE the sync even ran -- that silently cleared any
                // remote lock (fraud hold, admin maintenance lock, non-payment)
                // the instant someone got past the technician PIN, with no
                // server round-trip and no check of why it was set. remoteLocked
                // is a persisted command-channel flag (see DeviceAccessManager's
                // class doc) meant to be cleared only by a real UNLOCK command
                // from RemoteCommandManager, not by a generic sync button --
                // left untouched here. The one thing a sync legitimately
                // refreshes is devices.is_active, same pattern this file
                // already uses elsewhere (see the RemoteCommandManager wiring).
                DeviceAccessManager.applyActiveState(identity?.is_active)
                val config = ConfigManager.loadConfig(this@MainActivity, identity?.org_id)
                refreshProductsUI(config.products.forVertical(WASH_VERTICAL))
                DiagnosticManager.recordMaintenance(deviceSn, "DASH_FORCE_SYNC")

                updateHealthUI()
                it.isEnabled = true
                it.text = "SYNC"
                Toast.makeText(this@MainActivity, "Cloud Sync OK", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.findViewById<Button>(R.id.btn_op_logs).setOnClickListener {
            applyClickFeedback(it)
            val btn = it as Button
            btn.isEnabled = false
            btn.text = "UPLOADING..."
            CoroutineScope(Dispatchers.Main).launch {
                val result = DiagnosticManager.uploadLogs(deviceSn)
                btn.isEnabled = true
                btn.text = "EXPORT LOGS"
                // Was a bare success/fail Toast that couldn't distinguish
                // "actually uploaded something useful" from "technically
                // succeeded but the log was suspiciously empty" -- on this
                // app's targetSdk (34, Android 12+ consent-dialog territory)
                // the latter is the real observed failure mode, and telling a
                // technician "Logs Uploaded" for empty content is worse than
                // telling them nothing.
                val message = when (result) {
                    is com.goldsky.ssp.payment.LogUploadResult.Success ->
                        "Logs Uploaded (${result.lineCount} lines)"
                    is com.goldsky.ssp.payment.LogUploadResult.Empty ->
                        "Log capture empty -- on this device's screen, approve the system \"Allow log access\" prompt, then retry"
                    is com.goldsky.ssp.payment.LogUploadResult.Failed ->
                        "Upload Failed: ${result.reason}"
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        dialog.findViewById<Button>(R.id.btn_op_batch).setOnClickListener {
            applyClickFeedback(it)
            val btn = it as Button
            btn.isEnabled = false
            btn.text = "SETTLING..."
            
            val provider = PaymentProviderFactory.getPaymentProvider(this, hardwareVendor)
            provider.closeBatch(object : com.goldsky.ssp.payment.hardware.IPaymentProvider.PaymentCallback {
                override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                    runOnUiThread {
                        btn.isEnabled = true
                        btn.text = "CLOSE BATCH"
                        Toast.makeText(this@MainActivity, "Batch Success: $authCode", Toast.LENGTH_LONG).show()
                        DiagnosticManager.recordMaintenance(deviceSn, "MANUAL_BATCH_CLOSE")
                    }
                }

                override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                    runOnUiThread {
                        btn.isEnabled = true
                        btn.text = "CLOSE BATCH"
                        Toast.makeText(this@MainActivity, "Batch Fail: $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onProgress(message: String) {}
            })
        }

        dialog.findViewById<Button>(R.id.btn_op_check).setOnClickListener {
            applyClickFeedback(it)
            CoroutineScope(Dispatchers.Main).launch {
                it.isEnabled = false
                val btn = it as Button
                val originalText = btn.text

                // Was pure theater until 2026-09-23: looped through these six
                // step names with a fixed delay() and then unconditionally
                // wrote "ALL SYSTEMS NOMINAL" regardless of updateHealthUI()'s
                // actual result -- a technician relying on this to clear a
                // truly faulty unit (network down, DB unreachable, tamper
                // triggered) would have been told everything was fine. Each
                // step below now runs a real check and the final verdict is
                // derived from what they actually found.
                val failures = mutableListOf<String>()

                btn.text = "CHECKING: NET..."
                delay(400)
                if (!isNetworkAvailable(this@MainActivity)) failures += "NET"

                btn.text = "CHECKING: DB..."
                delay(400)
                if (!ConfigManager.isDatabaseOnline()) failures += "DB"

                btn.text = "CHECKING: SERIAL..."
                delay(400)
                if (!isSimulationMode && !HardwareFactory.getSerialProvider(this@MainActivity, hardwareVendor).isOpened()) {
                    failures += "SERIAL"
                }

                btn.text = "CHECKING: READER..."
                delay(400)
                if (!isSimulationMode && !hardware.isOperational()) failures += "READER"

                // PRINTER: init() + hasPaper() only -- a real hardware
                // round-trip, but no paper burned on every diagnostic run
                // (startPrint() is deliberately not called here).
                btn.text = "CHECKING: PRINTER..."
                delay(400)
                if (!isSimulationMode) {
                    val printer = HardwareFactory.getPrinterProvider(this@MainActivity, hardwareVendor)
                    if (!printer.init() || !printer.hasPaper()) failures += "PRINTER"
                }

                // VOICE: a real, audible utterance -- the technician is
                // standing at the unit pressing this button, so unlike an
                // unattended background check, actually speaking is the
                // most honest self-test (silence = fail is obvious to them).
                btn.text = "CHECKING: VOICE..."
                if (!isSimulationMode) {
                    TtsManager.speak("Diagnostic check complete")
                    if (!TtsManager.isReady()) failures += "VOICE"
                }
                delay(400)

                updateHealthUI()

                if (failures.isEmpty()) {
                    btn.text = "DIAGNOSTIC COMPLETE - ALL SYSTEMS NOMINAL"
                    btn.setBackgroundColor(getColor(R.color.tech_neon_green))
                    btn.setTextColor(getColor(R.color.tech_deep_bg))
                } else {
                    btn.text = "FAULT: ${failures.joinToString(", ")}"
                    btn.setBackgroundColor(getColor(R.color.tech_tamper_red))
                    btn.setTextColor(getColor(R.color.text_light))
                    DiagnosticManager.recordMaintenance(deviceSn, "DIAGNOSTIC_FAULT")
                }

                delay(3000)
                btn.text = originalText
                btn.setBackgroundColor(getColor(R.color.tech_pill_bg))
                btn.setTextColor(getColor(R.color.tech_cyan))
                it.isEnabled = true
            }
        }

        // 5. Config
        val swSim = dialog.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_sim_v2)
        swSim.isChecked = isSimulationMode
        swSim.setOnCheckedChangeListener { _, isChecked ->
            isSimulationMode = isChecked
            val serial = HardwareFactory.getSerialProvider(this, hardwareVendor)
            if (!isSimulationMode) serial.open(this) else serial.close()
            updateHealthUI()
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
                Log.i("SSP_WATCHDOG", "Hardware Watchdog Loop Started")
                val hardware = HardwareFactory.getHardwareProvider(hardwareVendor)
                
                while (isActive) {
                    // Feed the dog every 15 seconds. 
                    hardware.feedWatchdog()
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
        Log.i("SSP_WATCHDOG", "Hardware Watchdog CLOSED")
    }

    private fun sendTestCmd(hex: String) {
        if (isSimulationMode) {
            Toast.makeText(this, "Simulating: $hex", Toast.LENGTH_SHORT).show()
        } else {
            val sent = HardwareFactory.getSerialProvider(this, hardwareVendor).sendHexString(hex)
            Toast.makeText(this, if (sent) "Sent: $hex" else "Send FAILED", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * True for exactly the hardware DispenseEngine.dispense() routes onto
     * DigitIoAdapter/the Digit IO Pulse circuit for -- kept as a literal copy
     * of that method's own check (Q3mini/IM30 UPT models) rather than a
     * shared constant, since DispenseEngine lives in core/data and has no
     * Context/Activity dependency to share one from; if that selection logic
     * ever changes, this must change with it.
     */
    private fun isUptDigitIoMachine(): Boolean {
        val modelStr = DeviceAdapter.getModel().toString()
        return modelStr.contains("Q3MINI") || modelStr.contains("IM30")
    }

    /**
     * Fires [count] real test pulses on whichever circuit is actually live
     * for this hardware -- Digit IO's Pulse circuit (PIN1, native
     * triggerLogicPulse, same constants DigitIoAdapter's real dispense uses)
     * on Q3mini/IM30 UPT, or the legacy Console-serial hex path
     * (sendTestCmd) on older relay-board hardware where that's still the
     * real dispense path. Added 2026-09-23 to replace unconditional
     * sendTestCmd() calls that tested dead wiring on UPT hardware once wash
     * dispense moved to Digit IO (see docs/wizarpos_upt_integration_spec.md
     * §1.1).
     */
    private fun sendTestPulses(count: Int) {
        if (!isUptDigitIoMachine()) {
            sendTestCmd(if (count >= 8) "AA 01 08 55" else "AA 01 04 55")
            return
        }
        if (isSimulationMode) {
            Toast.makeText(this, "Simulating: $count pulses on PIN1", Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val gpio = HardwareFactory.getGpioProvider(this@MainActivity, hardwareVendor)
            var okCount = 0
            repeat(count) {
                if (gpio.triggerLogicPulse(
                        com.goldsky.ssp.dispense.adapter.DigitIoAdapter.PULSE_PORT,
                        com.goldsky.ssp.dispense.adapter.DigitIoAdapter.PULSE_VOLTAGE,
                        com.goldsky.ssp.dispense.adapter.DigitIoAdapter.PULSE_WIDTH_MS,
                        com.goldsky.ssp.dispense.adapter.DigitIoAdapter.PULSE_INTERVAL_MS
                    )
                ) {
                    okCount++
                }
            }
            Toast.makeText(this@MainActivity, "Pulse test: $okCount/$count sent on PIN1", Toast.LENGTH_SHORT).show()
            DiagnosticManager.recordMaintenance(deviceSn, "DASH_RELAY_TEST")
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
