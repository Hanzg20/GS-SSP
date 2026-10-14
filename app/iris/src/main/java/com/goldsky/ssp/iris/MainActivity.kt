package com.goldsky.ssp.iris

import android.os.Bundle
import com.goldsky.ssp.iris.R
import androidx.compose.ui.res.stringResource
import com.goldsky.ssp.ui.theme.GsTheme
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.goldsky.ssp.common.FeedbackManager
import com.goldsky.ssp.db.ExpenseEntity
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.model.CheckoutSale
import com.goldsky.ssp.model.SaleLine
import com.goldsky.ssp.payment.*
import com.goldsky.ssp.payment.hardware.*
import com.goldsky.ssp.feature.retail.screens.*
import com.goldsky.ssp.feature.retail.RefundFlow
import com.goldsky.ssp.feature.retail.RetailRepository
import com.goldsky.ssp.feature.retail.ui.TipSelectionDialog
import com.goldsky.ssp.feature.retail.ui.apexPulseGlow
import com.goldsky.ssp.feature.retail.ui.GoldSkyCyan
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.feature.apex.ApexScreen
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    // Vendor is read from the persisted device config rather than hardcoded
    // (the old retail flavor's MainActivity hardcoded "WIZARPOS") so the same
    // build works across whichever terminal it's actually deployed on --
    // matches app/aegis's registration pattern below.
    private val paymentProvider by lazy {
        PaymentProviderFactory.getPaymentProvider(this, DeviceRepository.getPersistedHardwareVendor())
    }
    private val scannerProvider by lazy {
        HardwareFactory.getScannerProvider(this, DeviceRepository.getPersistedHardwareVendor())
    }

    // Interface language from Settings (UiLanguage): strings and, for Arabic /
    // Persian, a mirrored layout. Switching recreates the activity.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.goldsky.ssp.payment.UiLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Config + hardware/payment provider registration live in
        // IrisApplication so WorkManager jobs (batch close, offline replay)
        // started in a cold process without this Activity still find them.
        DeviceRepository.init(this)
        StaffRepository.init(this)
        DeliveryOrderRepository.init(this)
        onBackPressedDispatcher.addCallback(this, stopScanOnBack)

        RetailRepository.init(this)

        // Every products/transactions/etc. RLS policy is scoped `TO authenticated`
        // AND to this device's org (via device_auth_map) -- see
        // docs/supabase_full_schema.sql. Nothing in this Activity previously
        // established either: SupabaseClientProvider.client ran permanently as
        // the anon role with no device_auth_map row, so RetailRepository.
        // syncWithCloud()'s catalog query always came back empty (silently
        // swallowed by its own try/catch, not a visible crash). Same
        // authenticate -> registerDevice -> syncDeviceIdentity sequence
        // feature:wash's MainActivity.extractDeviceIdentity() uses -- and, like
        // that one, run asynchronously rather than with runBlocking: this is a
        // handful of network round trips, and blocking onCreate on them delays
        // first paint by however long that takes (multi-second, on a bad
        // connection). RetailRepository.init() above already showed whatever
        // was cached locally immediately; re-sync once auth actually lands so
        // the org-scoped catalog replaces that once it's available.
        val deviceSn = try {
            HardwareFactory.getHardwareProvider(DeviceRepository.getPersistedHardwareVendor()).getSerialNumber(this)
        } catch (e: Exception) {
            "MOCK_SN_${System.currentTimeMillis()}"
        }
        DeviceRepository.persistDeviceSn(deviceSn)

        // Heartbeat, offline transaction replay, daily batch close, storage
        // cleaning -- never scheduled here before, so a sale whose insert
        // failed sat in the offline queue forever and the batch was never
        // auto-settled (WizarPOS Q2, 2026-10-09). No ad screen in Iris.
        // After persistDeviceSn: the first heartbeat goes out right away and must carry this SN.
        AdManager.init(this, syncAds = false)
        CoroutineScope(Dispatchers.IO).launch {
            SupabaseClientProvider.ensureAuthenticated()
            DeviceRepository.registerDevice(deviceSn, BuildConfig.VERSION_NAME)
            DeviceRepository.syncDeviceIdentity(deviceSn)
            // Expenses saved offline go up; this terminal's reminders get their notifications back.
            ExpenseRepository.syncPending(this@MainActivity)
            ReminderRepository.rescheduleOpen(this@MainActivity)
            RetailRepository.syncWithCloud(this@MainActivity)
        }

        setContent {
            // Dark (CYBER) / light (CLASSIC), picked in Settings; themeVersion
            // changes on every switch so the whole tree repaints at once.
            val themeVersion by DeviceRepository.themeVersion.collectAsState()
            val darkTheme = remember(themeVersion) { DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER }
            com.goldsky.ssp.ui.theme.GoldSkyTheme(dark = darkTheme) {
                com.goldsky.ssp.feature.retail.screens.StoreChangeWatcher(deviceSn)
                val navController = rememberNavController()
                val retailViewModel: RetailViewModel = viewModel()
                // The cart in progress comes back after a restart.
                LaunchedEffect(Unit) { retailViewModel.restoreCart(this@MainActivity) }
                // Dine-in: the floor / open check, and the round being taken in its own cart.
                val dine: com.goldsky.ssp.feature.retail.dine.DineViewModel = viewModel()
                val roundViewModel: RetailViewModel = viewModel(key = "dine-round")
                // A payment on a table's check, between the split dialog and the money.
                var checkPay by remember { mutableStateOf<com.goldsky.ssp.feature.retail.dine.CheckPay?>(null) }
                var choosingCheckPay by remember { mutableStateOf(false) }
                var paymentStatus by remember { mutableStateOf<PaymentUiState>(PaymentUiState.Idle) }
                // Staged while the tip dialog is up; the real sale only fires
                // once the customer picks (or skips) a tip.
                var activeSaleData by remember { mutableStateOf<CheckoutSale?>(null) }
                // Quick-service: the order (call number, dine-in/take-out, note) behind the sale being paid.
                var orderTicket by remember { mutableStateOf<com.goldsky.ssp.model.KitchenTicket?>(null) }
                var staff by remember { mutableStateOf(StaffRepository.current) }
                fun lock() {
                    StaffRepository.signOut()
                    staff = null
                    navController.navigate("checkout") { popUpTo(navController.graph.startDestinationId); launchSingleTop = true }
                }
                // Idle lock: no touch for IDLE_LOCK_MS -> back to the PIN screen, so
                // the next person signs in as themselves. Not while a payment or a
                // scan run is going on.
                LaunchedEffect(staff) {
                    while (staff != null) {
                        kotlinx.coroutines.delay(15_000)
                        val idle = android.os.SystemClock.elapsedRealtime() - lastInteractionAt
                        if (idle > IDLE_LOCK_MS && paymentStatus !is PaymentUiState.Processing && !scanning.value) lock()
                    }
                }
                // Bumped after a delivery order is collected so the list reloads without it.
                var deliveryReload by remember { mutableStateOf(0) }
                // Bumped after a refund so Records shows the new status.
                var recordsReload by remember { mutableStateOf(0) }
                val scope = rememberCoroutineScope()

                fun pay(sale: CheckoutSale, tip: Int) {
                    val ticket = orderTicket
                    val forCheck = checkPay
                    val extras = forCheck?.let { cp ->
                        PayExtras(
                            banner = "TABLE ${cp.tableName}  #${cp.checkNo}" + (if (cp.label.isNotEmpty()) "  ${cp.label}" else ""),
                            onPaid = { ref, method, t -> dine.paymentDone(this@MainActivity, cp, ref, t, method, signedInName()) }
                        )
                    }
                    triggerPaymentFlow(sale, tip, ticket, extras) {
                        paymentStatus = if (it is PaymentUiState.Success) it.copy(orderNumber = ticket?.number) else it
                        if (it is PaymentUiState.Success && forCheck != null) {
                            checkPay = null
                            FeedbackManager.success(this@MainActivity)
                        } else if (it is PaymentUiState.Success) {
                            orderTicket = null
                            // A delivery collection leaves whatever is in the cart alone.
                            val orderId = sale.deliveryOrderId
                            if (orderId == null) retailViewModel.clearCart()
                            else { DeliveryOrderRepository.markCollectedLocally(orderId); deliveryReload++ }
                            FeedbackManager.success(this@MainActivity)
                        }
                    }
                }

                fun checkout(sale: CheckoutSale, ticket: com.goldsky.ssp.model.KitchenTicket? = null) {
                    orderTicket = ticket
                    // No tip step unless the store asks for tips (off for retail).
                    if (DeviceRepository.isTipsEnabled()) activeSaleData = sale else pay(sale, 0)
                }

                // Re-check the order right before taking money: another terminal may
                // have collected it, or the store cancelled it, since the list loaded.
                fun withOpenOrder(order: DeliveryOrder, action: () -> Unit) {
                    paymentStatus = PaymentUiState.Processing(getString(R.string.iris_checking_order, order.order_no.toString()))
                    scope.launch {
                        when (val status = DeliveryOrderRepository.fetchStatus(order.id)) {
                            "OPEN" -> { paymentStatus = PaymentUiState.Idle; action() }
                            null -> paymentStatus = PaymentUiState.Error(getString(R.string.iris_order_unreachable, order.order_no.toString()))
                            else -> {
                                paymentStatus = PaymentUiState.Error(
                                    if (status == "COLLECTED") getString(R.string.iris_order_already_collected, order.order_no.toString())
                                    else getString(R.string.iris_order_not_open, order.order_no.toString(), status.lowercase())
                                )
                                deliveryReload++
                            }
                        }
                    }
                }

                fun saleOf(order: DeliveryOrder) =
                    CheckoutSale(order.subtotalCents, order.taxCents, order.lines, deliveryOrderId = order.id)

                val signedIn = staff
                val surfaceBg = MaterialTheme.colorScheme.background

                if (signedIn == null) {
                    StaffPinLockScreen(onSignedIn = { staff = it; lastInteractionAt = android.os.SystemClock.elapsedRealtime() })
                } else {
                    key(themeVersion) { Surface(modifier = Modifier.fillMaxSize(), color = surfaceBg) {
                        Scaffold(
                            topBar = { StaffBar(signedIn, onLock = { lock() }) },
                            bottomBar = { MainBottomNavigation(navController, isManager = signedIn.isManager) }
                        ) { padding ->
                            NavHost(
                                navController = navController,
                                startDestination = "checkout",
                                modifier = Modifier.padding(padding)
                            ) {
                                composable("checkout") {
                                    CheckoutContainer(
                                        retailViewModel = retailViewModel,
                                        isScanning = scanning.value,
                                        deliveryOnly = signedIn.isDriver,
                                        onScan = { if (scanning.value) stopScanSession() else startScanSession(retailViewModel) },
                                        onCheckout = { checkout(it) },
                                        // The cart (library tab): on a quick-service counter it's an order for the kitchen.
                                        onCartCheckout = {
                                            val ticket = if (com.goldsky.ssp.feature.retail.ui.isQuickService())
                                                retailViewModel.kitchenTicket(this@MainActivity, signedIn.name) else null
                                            checkout(retailViewModel.checkoutSale(), ticket)
                                        },
                                        deliveryReload = deliveryReload,
                                        onCollectCard = { order -> withOpenOrder(order) { checkout(saleOf(order)) } },
                                        dine = dine,
                                        roundViewModel = roundViewModel,
                                        staffId = signedIn.id,
                                        staffName = signedIn.name,
                                        onCheckPay = { choosingCheckPay = true },
                                        onCollectCash = { order ->
                                            withOpenOrder(order) {
                                                DeliveryOrderRepository.markCollectedLocally(order.id)
                                                recordCashCollection(saleOf(order), order.order_no)
                                                paymentStatus = PaymentUiState.Success(getString(R.string.iris_cash_received_for, order.order_no.toString()))
                                                deliveryReload++
                                                FeedbackManager.success(this@MainActivity)
                                            }
                                        }
                                    )
                                }
                                composable("insights") { ApexScreen(signedIn) }
                                composable("transactions") {
                                    TransactionHistoryScreen(
                                        onlyStaffId = if (signedIn.isManager) null else signedIn.id,
                                        onRefund = { order, approver ->
                                            RefundFlow.start(this@MainActivity, paymentProvider, order, approver) { r ->
                                                val state = r.toUi()
                                                paymentStatus = state
                                                if (state !is PaymentUiState.Processing) recordsReload++
                                            }
                                        },
                                        onOpenDayReport = { navController.navigate("day_report") },
                                        reloadKey = recordsReload
                                    )
                                }
                                composable("day_report") {
                                    DayReportScreen(onBack = { navController.popBackStack() })
                                }
                                composable("expenses") {
                                    ExpenseScreen(onScanReceipt = { navController.navigate("scan_receipt") })
                                }
                                composable("scan_receipt") {
                                    ReceiptScannerScreen(
                                        onReceiptCaptured = { vendor, amount ->
                                            CoroutineScope(Dispatchers.IO).launch {
                                                // Same cloud record as manual and voice entries; a supplier
                                                // receipt is most often stock, the manager can change it in CMP.
                                                ExpenseRepository.record(
                                                    this@MainActivity, vendor, "INVENTORY", amount, ExpenseRepository.today(),
                                                    null, "receipt", StaffRepository.current
                                                )
                                                withContext(Dispatchers.Main) { navController.popBackStack() }
                                            }
                                        },
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                                // Managers only (the nav item is hidden for cashiers too).
                                composable("settings") { if (signedIn.isManager) SettingsScreen(showBusinessType = true) }
                            }
                        }

                        // Detail overlays (drawn above the NavHost content, not routes of their own)
                        activeSaleData?.let { sale ->
                            TipSelectionDialog(
                                subtotalCents = sale.subtotalCents + sale.taxCents,
                                onConfirm = { totalWithTip ->
                                    val tip = totalWithTip - (sale.subtotalCents + sale.taxCents)
                                    activeSaleData = null
                                    pay(sale, tip)
                                },
                                onDismiss = { activeSaleData = null }
                            )
                        }

                        val payingCheck = dine.check
                        if (choosingCheckPay && payingCheck != null) {
                            com.goldsky.ssp.feature.retail.dine.CheckPayDialog(
                                detail = payingCheck,
                                onPay = { cp ->
                                    choosingCheckPay = false
                                    checkPay = cp
                                    checkout(CheckoutSale(cp.amountCents - cp.taxCents, cp.taxCents, cp.lines))
                                },
                                onDismiss = { choosingCheckPay = false }
                            )
                        }
                        dine.message?.let { code ->
                            AlertDialog(
                                onDismissRequest = { dine.clearMessage() },
                                text = { Text(com.goldsky.ssp.feature.retail.dine.dineMessage(code)) },
                                confirmButton = { Button(onClick = { dine.clearMessage() }) { Text(stringResource(R.string.iris_ok)) } }
                            )
                        }

                        when (val state = paymentStatus) {
                            is PaymentUiState.Processing -> PaymentProcessingDialog(state.message)
                            is PaymentUiState.Success -> PaymentResultDialog(true, stringResource(R.string.iris_approved_code, state.authCode), state.orderNumber) { paymentStatus = PaymentUiState.Idle }
                            is PaymentUiState.Done -> PaymentResultDialog(true, state.message) { paymentStatus = PaymentUiState.Idle }
                            is PaymentUiState.RefundNeedsCard -> com.goldsky.ssp.feature.retail.RefundNeedsCardDialog(
                                amountCents = state.order.amountCents,
                                onCardTapped = {
                                    RefundFlow.withCard(this@MainActivity, paymentProvider, state.order, state.approver) {
                                        paymentStatus = it.toUi(); if (it !is RefundFlow.RefundState.Processing) recordsReload++
                                    }
                                },
                                onRefundedInPortal = {
                                    RefundFlow.refundedInPortal(this@MainActivity, state.order, state.approver) {
                                        paymentStatus = it.toUi(); recordsReload++
                                    }
                                },
                                onCancel = { paymentStatus = PaymentUiState.Idle }
                            )
                            is PaymentUiState.Error -> PaymentResultDialog(false, state.message) { paymentStatus = PaymentUiState.Idle }
                            else -> {}
                        }
                    } }
                }
            }
        }
    }

    // Scan session, started by the scan button: keeps scanning item after item
    // until the user backs out of the preview (that ends the scan with a
    // failure, which ends the session). The Q2 has no scan engine or trigger
    // key -- the camera is the scanner -- so the preview is confined to the top
    // of the screen, leaving the cart and the "Added: ..." banner visible.
    // Not started on unlock: an always-on loop kept a full-screen preview over
    // the whole UI (2026-10-09).
    private val scanHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // All touched on the main thread only (scanner callbacks are posted there).
    private val scanning = mutableStateOf(false)
    private var lastScan: Pair<String, Long>? = null

    private fun startScanSession(viewModel: RetailViewModel) {
        if (scanning.value) return
        scanning.value = true
        stopScanOnBack.isEnabled = true
        val dm = resources.displayMetrics
        scannerProvider.setPreviewWindow(android.graphics.Rect(0, 0, dm.widthPixels, (dm.heightPixels * 0.42f).toInt()))
        scanNext(viewModel)
    }

    private fun scanNext(viewModel: RetailViewModel) {
        scannerProvider.startScan(object : IScannerProvider.ScanCallback {
            override fun onScanSuccess(barcode: String) {
                // The item is usually still in front of the camera when it reopens:
                // the same code again within SAME_CODE_MS is that, not a second item.
                val now = android.os.SystemClock.elapsedRealtime()
                val prev = lastScan
                if (prev == null || prev.first != barcode || now - prev.second > SAME_CODE_MS) {
                    viewModel.addByBarcode(this@MainActivity, barcode)
                }
                lastScan = barcode to now
                if (scanning.value) scanHandler.postDelayed({ if (scanning.value) scanNext(viewModel) }, 300)
            }
            override fun onScanFailure(error: String) {
                scanning.value = false
                stopScanOnBack.isEnabled = false
                android.util.Log.d("IrisScan", "Scan session ended: $error")
            }
        })
    }

    // Ends the run: the stop button, or Back. Back goes back to normal right
    // away -- a stopped overlay scan never calls back (so waiting for that kept
    // Back stuck on "stop" and the user couldn't leave, 2026-10-10), and the
    // provider drops any start queued before the stop, so the camera can't
    // reopen behind it.
    private fun stopScanSession() {
        android.util.Log.d("IrisScan", "Scan session stopped")
        scanning.value = false
        stopScanOnBack.isEnabled = false
        scanHandler.removeCallbacksAndMessages(null)
        scannerProvider.stopScan()
    }

    // The overlay preview doesn't take Back; while scanning, Back ends the run
    // instead of navigating.
    private val stopScanOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = stopScanSession()
    }

    @Volatile private var lastInteractionAt = android.os.SystemClock.elapsedRealtime()

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        lastInteractionAt = android.os.SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        const val SAME_CODE_MS = 1500L
        const val IDLE_LOCK_MS = 5 * 60_000L
    }

    /**
     * Cash taken at the door for a delivery order: a PAID CASH transaction with
     * its lines, the order marked paid, and a receipt. Every write goes through
     * the offline queue, in that order, if the cloud can't be reached.
     */
    private fun recordCashCollection(sale: CheckoutSale, orderNo: Long) {
        val ref = "RET-" + System.currentTimeMillis()
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "MOCK_SN"
        val total = sale.subtotalCents + sale.taxCents
        CoroutineScope(Dispatchers.IO).launch {
            TransactionRepository.recordTransaction(
                this@MainActivity,
                TransactionRecord(
                    device_sn = sn, amount = total, subtotal_cents = sale.subtotalCents, tax_cents = sale.taxCents,
                    tip_cents = 0, payment_status = "PAID", ecr_ref_num = ref, payment_method = "CASH",
                    staff_id = StaffRepository.current?.id, staff_name = StaffRepository.current?.name
                )
            )
            TransactionRepository.recordItems(this@MainActivity, ref, sn, sale.lines)
            sale.deliveryOrderId?.let { DeliveryOrderRepository.markPaid(this@MainActivity, it, ref, "CASH") }
            ReceiptPrinterManager.printReceipt(
                this@MainActivity,
                ReceiptPrinterManager.ReceiptData(
                    subtotalCents = sale.subtotalCents, taxCents = sale.taxCents, tipCents = 0, amountCents = total,
                    refNum = ref, deviceSn = sn, lines = sale.lines
                ),
                vendor = DeviceRepository.getPersistedHardwareVendor()
            )
            android.util.Log.i("IrisDelivery", "Cash collected for D$orderNo: $ref")
        }
    }

    private fun triggerPaymentFlow(sale: CheckoutSale, tip: Int, ticket: com.goldsky.ssp.model.KitchenTicket?, extras: PayExtras?, onStateChange: (PaymentUiState) -> Unit) {
        val subtotal = sale.subtotalCents
        val tax = sale.taxCents
        val total = subtotal + tax + tip
        val ref = "RET-" + System.currentTimeMillis()
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "MOCK_SN"

        val record = TransactionRecord(
            device_sn = sn,
            amount = total,
            subtotal_cents = subtotal,
            tax_cents = tax,
            tip_cents = tip,
            payment_status = "PENDING",
            ecr_ref_num = ref,
            payment_method = "CREDIT_CARD",
            staff_id = StaffRepository.current?.id,
            staff_name = StaffRepository.current?.name
        ).withOrder(ticket)

        CoroutineScope(Dispatchers.IO).launch {
            TransactionRepository.recordTransaction(this@MainActivity, record)
            // After the parent row: transaction_items references it.
            TransactionRepository.recordItems(this@MainActivity, ref, sn, sale.lines)
        }

        onStateChange(PaymentUiState.Processing("Processing $${"%.2f".format(total / 100.0)}..."))

        var card: IPaymentProvider.CardInfo? = null
        var cancelled = false
        paymentProvider.startSale(total, ref, object : IPaymentProvider.PaymentCallback {
            override fun onCardInfo(info: IPaymentProvider.CardInfo) { card = info }
            override fun onCancelled() { cancelled = true }

            override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    // Same as Wash/Timer: the real card type, AID/BIN/brand and auth
                    // code replace the provisional CREDIT_CARD.
                    val c = card
                    TransactionRepository.updatePaymentStatus(
                        this@MainActivity, ref, "PAID", entryMode,
                        paymentMethod = c?.let { CardTypeClassifier.paymentMethod(it.scheme, it.aid, it.brand) },
                        cardAid = c?.aid, cardBin = c?.bin, cardBrand = c?.brand,
                        authCode = authCode
                    )
                    sale.deliveryOrderId?.let { DeliveryOrderRepository.markPaid(this@MainActivity, it, ref, "CARD") }
                    extras?.onPaid?.invoke(ref, "CARD", tip)
                    // Quick-service: the kitchen ticket first, then the receipt with the call number.
                    ReceiptPrinterManager.printOrder(this@MainActivity, ticket, ReceiptPrinterManager.ReceiptData(
                        subtotalCents = subtotal,
                        taxCents = tax,
                        tipCents = tip,
                        amountCents = total,
                        refNum = ref,
                        deviceSn = sn,
                        lines = sale.lines,
                        banner = extras?.banner
                    ), DeviceRepository.getPersistedHardwareVendor())
                }
                onStateChange(PaymentUiState.Success(authCode))
            }
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                CoroutineScope(Dispatchers.IO).launch {
                    // CANCELLED / DECLINED with the card that was read. This wrote
                    // "FAILED", which payment_status doesn't allow: the row stayed
                    // PENDING in the cloud and the write sat in the offline queue.
                    TransactionRepository.recordFailedCardSale(this@MainActivity, ref, card, cancelled)
                }
                onStateChange(PaymentUiState.Error(errorMsg))
            }
            override fun onProgress(message: String) {
                onStateChange(PaymentUiState.Processing(message))
            }
        })
    }
}

/**
 * KEYPAD/LIBRARY/DELIVERY tab switcher hosting whichever "quick" flow actually
 * collects the sale -- restored from the old retail flavor's CheckoutScreen
 * (a container, not a screen of its own; unrelated to the standalone
 * feature.retail.screens.CheckoutScreen file, which nothing currently
 * navigates to). Without this, DeliveryScreen/ManualAmountScreen
 * were fully-written but unreachable -- RetailStoreScreen was the only thing
 * ever wired to the "checkout" route.
 */
@Composable
fun CheckoutContainer(
    retailViewModel: RetailViewModel,
    onScan: () -> Unit,
    isScanning: Boolean,
    // Drivers: the DELIVERY tab only -- no keypad (arbitrary amounts) or library.
    deliveryOnly: Boolean,
    onCheckout: (CheckoutSale) -> Unit,
    onCartCheckout: () -> Unit,
    deliveryReload: Int,
    dine: com.goldsky.ssp.feature.retail.dine.DineViewModel,
    roundViewModel: RetailViewModel,
    staffId: String?,
    staffName: String?,
    onCheckPay: () -> Unit,
    onCollectCard: (DeliveryOrder) -> Unit,
    onCollectCash: (DeliveryOrder) -> Unit
) {
    // Amounts with no product behind them (keypad, table): one line at the device rate.
    fun atDeviceRate(name: String, cents: Int) {
        val bps = SalesTax.deviceRateBps()
        onCheckout(CheckoutSale(cents, SalesTax.cents(cents, bps), listOf(SaleLine(name, 1, cents, bps))))
    }
    // Dine-in: a Tables tab (3), first and selected.
    val dineIn = !deliveryOnly && com.goldsky.ssp.feature.retail.ui.isDineIn()
    var selectedTab by remember { mutableStateOf(if (deliveryOnly) 2 else if (dineIn) 3 else 0) }
    if (deliveryOnly && selectedTab != 2) selectedTab = 2


    Column(modifier = Modifier.fillMaxSize()) {
        if (!deliveryOnly) TabRow(
            // Row positions: Tables (if any) comes first, ahead of keypad / library / delivery.
            selectedTabIndex = if (dineIn) (if (selectedTab == 3) 0 else selectedTab + 1) else selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            if (dineIn) Tab(
                selected = selectedTab == 3, onClick = { selectedTab = 3 },
                text = { Text(stringResource(com.goldsky.ssp.feature.retail.R.string.dine_tables), fontWeight = FontWeight.Bold, color = if (selectedTab == 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            )
            Tab(
                selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.iris_keypad), fontWeight = FontWeight.Bold, color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            )
            Tab(
                selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.iris_library), fontWeight = FontWeight.Bold, color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            )
            Tab(
                selected = selectedTab == 2, onClick = { selectedTab = 2 },
                text = { Text(stringResource(R.string.iris_delivery), fontWeight = FontWeight.Bold, color = if (selectedTab == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 3) {
                if (dine.check == null) com.goldsky.ssp.feature.retail.dine.DineFloor(dine, staffId, staffName, minCell = 100.dp)
                else com.goldsky.ssp.feature.retail.dine.HandheldCheck(dine, roundViewModel, staffName, onPay = onCheckPay, onScan = onScan, isScanning = isScanning)
            } else if (selectedTab == 2) {
                DeliveryScreen(reloadKey = deliveryReload, onCollectCard = onCollectCard, onCollectCash = onCollectCash)
            } else if (selectedTab == 0) {
                ManualAmountScreen(onInitiatePayment = { amountStr ->
                    val cents = amountStr.replace(".", "").toIntOrNull() ?: 0
                    if (cents > 0) atDeviceRate("Keypad amount", cents)
                })
            } else {
                // Restaurants use the same list (Settings > Business type = quick-service adds
                // dish options, dine-in/take-out, note and the kitchen ticket). The old
                // RESTAURANT mode here was a fixed $50.00 "table" with nothing behind it.
                RetailStoreScreen(
                    onCheckout = { onCartCheckout() },
                    viewModel = retailViewModel,
                    onScan = onScan,
                    isScanning = isScanning
                )
            }
        }
    }
}

@Composable
// Cashiers and drivers: Quick + Records (their own). Managers also get AI
// insights, expenses and Settings.
fun MainBottomNavigation(navController: NavHostController, isManager: Boolean) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        NavigationBarItem(
            selected = currentRoute == "checkout",
            onClick = { navController.navigate("checkout") { popUpTo(navController.graph.startDestinationId); launchSingleTop = true } },
            icon = { Icon(Icons.Default.PointOfSale, null) },
            label = { Text(stringResource(R.string.iris_quick)) }
        )
        NavigationBarItem(
            selected = currentRoute == "transactions",
            onClick = { navController.navigate("transactions") { launchSingleTop = true } },
            icon = { Icon(Icons.Default.ReceiptLong, null) },
            label = { Text(stringResource(R.string.iris_records)) }
        )
        if (isManager) NavigationBarItem(
            selected = currentRoute == "insights",
            onClick = { navController.navigate("insights") { launchSingleTop = true } },
            icon = {
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .apexPulseGlow(
                            enabled = currentRoute == "insights",
                            shape = androidx.compose.foundation.shape.CircleShape,
                            strokeWidth = 1.5.dp
                        )
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (currentRoute == "insights") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            },
            label = {
                Text(
                    text = "AI",
                    color = if (currentRoute == "insights") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontWeight = if (currentRoute == "insights") FontWeight.Bold else FontWeight.Normal
                )
            }
        )
        if (isManager) NavigationBarItem(
            selected = currentRoute == "expenses",
            onClick = { navController.navigate("expenses") { launchSingleTop = true } },
            icon = { Icon(Icons.Default.Receipt, null) },
            label = { Text(stringResource(R.string.iris_expenses)) }
        )
        if (isManager) NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = { navController.navigate("settings") { launchSingleTop = true } },
            icon = { Icon(Icons.Default.Settings, null) },
            label = { Text(stringResource(R.string.iris_settings)) }
        )
    }
}

/** Hardware-aware Iris Top Bar with Staff info, quick Lock and Hardware Health Badge */
@Composable
fun StaffBar(staff: StaffMember, onLock: () -> Unit) {
    IrisSystemHeader(staff = staff, onLock = onLock)
}

@Composable
fun IrisSystemHeader(staff: StaffMember, onLock: () -> Unit) {
    val headerBg = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface

    Surface(
        color = headerBg,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            // Left: Staff Identity & Quick Lock
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(staff.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = textColor)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(when { staff.isManager -> R.string.iris_role_manager; staff.isDriver -> R.string.iris_role_driver; else -> R.string.iris_role_cashier }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onLock, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.iris_lock), modifier = Modifier.size(16.dp), tint = GsTheme.colors.danger)
                }
            }

            Spacer(Modifier.weight(1f))

            // Right: battery level (read from the system, not decorative). Printer
            // and key-health icons were removed: they showed a fixed "OK" whatever
            // the real state, and key health is PAX-only.
            val context = androidx.compose.ui.platform.LocalContext.current
            var battery by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(Unit) {
                val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
                while (true) {
                    battery = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
                    kotlinx.coroutines.delay(60_000)
                }
            }
            battery?.let { level ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(
                        if (level <= 15) Icons.Default.BatteryAlert else Icons.Default.BatteryStd,
                        contentDescription = stringResource(R.string.iris_battery),
                        modifier = Modifier.size(16.dp),
                        tint = if (level <= 15) GsTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("$level%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun RefundFlow.RefundState.toUi(): PaymentUiState = when (this) {
    is RefundFlow.RefundState.Processing -> PaymentUiState.Processing(message)
    is RefundFlow.RefundState.Done -> PaymentUiState.Done(message)
    is RefundFlow.RefundState.Failed -> PaymentUiState.Error(message)
    is RefundFlow.RefundState.NeedsCard -> PaymentUiState.RefundNeedsCard(order, approver)
}

/** What a payment on a table's check adds: the receipt banner, and recording it on the check. */
class PayExtras(val banner: String?, val onPaid: suspend (ref: String, method: String, tip: Int) -> Unit)

private fun signedInName(): String? = StaffRepository.current?.name

sealed class PaymentUiState {
    object Idle : PaymentUiState()
    data class Success(val authCode: String, val orderNumber: Int? = null) : PaymentUiState()
    data class Error(val message: String) : PaymentUiState()
    data class Processing(val message: String) : PaymentUiState()
    // A non-sale outcome to confirm (e.g. a refund), shown as-is.
    data class Done(val message: String) : PaymentUiState()
    // The card sale couldn't be reversed (usually: already settled). Socket-mode
    // Refund needs the customer's card; otherwise it's refunded in the WizarPOS
    // merchant portal and only recorded here.
    data class RefundNeedsCard(val order: com.goldsky.ssp.db.OrderEntity, val approver: StaffMember) : PaymentUiState()
}

@Composable
fun PaymentProcessingDialog(message: String) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(message, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun PaymentResultDialog(isSuccess: Boolean, message: String, orderNumber: Int? = null, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isSuccess) R.string.iris_payment_success else R.string.iris_payment_failed)) },
        text = {
            Column {
                // Quick-service: the call number to tell the customer.
                orderNumber?.let { com.goldsky.ssp.feature.retail.ui.OrderNumberBlock(it); Spacer(Modifier.height(8.dp)) }
                Text(message)
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) { Text(stringResource(R.string.iris_ok), fontWeight = FontWeight.Bold) }
        },
        containerColor = if (isSuccess) com.goldsky.ssp.ui.theme.SuccessSlab else com.goldsky.ssp.ui.theme.DangerSlab,
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

