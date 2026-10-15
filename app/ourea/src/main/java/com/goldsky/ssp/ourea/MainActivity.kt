package com.goldsky.ssp.ourea

import android.graphics.Bitmap
import com.goldsky.ssp.ourea.R
import androidx.compose.ui.res.stringResource
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldsky.ssp.common.FeedbackManager
import com.goldsky.ssp.common.QrUtils
import com.goldsky.ssp.payment.*
import com.goldsky.ssp.payment.hardware.*
import com.goldsky.ssp.feature.retail.RetailRepository
import com.goldsky.ssp.feature.retail.screens.SettingsScreen
import com.goldsky.ssp.feature.retail.screens.StaffPinLockScreen
import com.goldsky.ssp.feature.retail.screens.TransactionHistoryScreen
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import kotlinx.coroutines.*

/**
 * Desktop POS -- the correctly-branded "GoldSky Ourea" per the taxonomy
 * confirmed 2026-08-27 (see docs/system_architecture.md v2.30/v2.31). Same
 * hardware/payment/auth wiring as app/iris's MainActivity (proven pattern,
 * copied not re-derived); the UI tree is new, styled after the reference
 * screenshots in C:\goldsky\Requirment\Ourea (a design reference, not a
 * spec to clone verbatim -- see the per-screen comments in OureaScreens.kt
 * for what was and wasn't carried over).
 */
class MainActivity : ComponentActivity() {

    private val paymentProvider: IPaymentProvider
        get() = PaymentProviderFactory.getPaymentProvider(this, DeviceRepository.getPersistedHardwareVendor())

    /** Only one QR session can be in flight; cancelled if the staff backs out of the dialog. */
    private var qrPollJob: Job? = null

    /**
     * ecr_ref_num of the in-flight QR session, so a manual cancel can mark
     * the PENDING record CANCELLED instead of abandoning it -- see the
     * cancel handling in onCreate's setContent block.
     */
    private var activeQrTxId: String? = null

    // Interface language from Settings (UiLanguage): strings and, for Arabic /
    // Persian, a mirrored layout. Switching recreates the activity.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.goldsky.ssp.payment.UiLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Config and hardware/payment providers: OureaApplication.
        DeviceRepository.init(this)
        StaffRepository.init(this)
        DiscountPresetRepository.init(this)
        RetailRepository.init(this)

        val deviceSn = try {
            HardwareFactory.getHardwareProvider(DeviceRepository.getPersistedHardwareVendor()).getSerialNumber(this)
        } catch (e: Exception) {
            "MOCK_SN_${System.currentTimeMillis()}"
        }
        DeviceRepository.persistDeviceSn(deviceSn)
        // Heartbeat (now, on every reconnect, and every 15 min), offline
        // replay, daily batch close -- Ourea scheduled none of these before. No ad screen.
        AdManager.init(this, syncAds = false)
        CoroutineScope(Dispatchers.IO).launch {
            SupabaseClientProvider.ensureAuthenticated()
            DeviceRepository.registerDevice(deviceSn, BuildConfig.VERSION_NAME)
            DeviceRepository.syncDeviceIdentity(deviceSn)
            // Keep the customer display the way Settings says (it also remembers on its own).
            paymentProvider.showEyeRotation(DeviceRepository.getEyeRotation())
            // Expenses saved offline go up; this terminal's reminders get their notifications back.
            ExpenseRepository.syncPending(this@MainActivity)
            ReminderRepository.rescheduleOpen(this@MainActivity)
            RetailRepository.syncWithCloud(this@MainActivity)
        }

        setContent {
            // Shared GoldSkyTheme, dark or light as picked in Settings; repaints on a switch.
            val themeVersion by DeviceRepository.themeVersion.collectAsState()
            val darkTheme = remember(themeVersion) { DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER }
            OureaTheme(dark = darkTheme) { key(themeVersion) {
                com.goldsky.ssp.feature.retail.screens.StoreChangeWatcher(deviceSn)
                var staff by remember { mutableStateOf(StaffRepository.current) }

                val signedIn = staff
                if (signedIn == null) {
                    StaffPinLockScreen(onSignedIn = { staff = it })
                } else {
                    val retailViewModel: RetailViewModel = viewModel()
                    // The cart in progress comes back after a restart.
                    LaunchedEffect(Unit) { retailViewModel.restoreCart(this@MainActivity) }
                    // Dine-in: the floor and open check, and the round being taken -- its own
                    // cart, so a take-out sale half rung up never lands on a table's ticket.
                    val dine: com.goldsky.ssp.feature.retail.dine.DineViewModel = viewModel()
                    val roundViewModel: RetailViewModel = viewModel(key = "dine-round")
                    var destination by remember { mutableStateOf(if (com.goldsky.ssp.feature.retail.ui.isDineIn()) OureaDestination.TABLES else OureaDestination.CHECKOUT) }
                    // A payment on a table's check (whole, by items or a share), between the split dialog and the money.
                    var checkPay by remember { mutableStateOf<com.goldsky.ssp.feature.retail.dine.CheckPay?>(null) }
                    var choosingCheckPay by remember { mutableStateOf(false) }
                    var leavingWithRound by remember { mutableStateOf(false) }
                    var paymentStatus by remember { mutableStateOf<OureaPaymentUiState>(OureaPaymentUiState.Idle) }
                    var activeSaleData by remember { mutableStateOf<Pair<Int, Int>?>(null) }
                    var confirmingPayment by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
                    // The payment method chosen, waiting for the customer's tip on the Eye.
                    var tipFor by remember { mutableStateOf<String?>(null) }
                    var showCashDialog by remember { mutableStateOf(false) }
                    var showMemberDialog by remember { mutableStateOf(false) }
                    // Lines of the sale being paid (taken at checkout), for transaction_items and the receipt.
                    var saleLines by remember { mutableStateOf<List<com.goldsky.ssp.model.SaleLine>>(emptyList()) }
                    // Quick-service counter: the order (call number, dine-in/take-out, note, dishes) being paid.
                    var orderTicket by remember { mutableStateOf<com.goldsky.ssp.model.KitchenTicket?>(null) }
                    // Refunds (shared RefundFlow): records reload after one; NeedsCard asks how to refund a settled card sale.
                    var recordsReload by remember { mutableStateOf(0) }
                    var refundNeedsCard by remember { mutableStateOf<com.goldsky.ssp.feature.retail.RefundFlow.RefundState.NeedsCard?>(null) }
                    fun onRefundState(r: com.goldsky.ssp.feature.retail.RefundFlow.RefundState) {
                        when (r) {
                            is com.goldsky.ssp.feature.retail.RefundFlow.RefundState.Processing -> paymentStatus = OureaPaymentUiState.Processing(r.message)
                            is com.goldsky.ssp.feature.retail.RefundFlow.RefundState.Done -> { paymentStatus = OureaPaymentUiState.Success(r.message); recordsReload++ }
                            is com.goldsky.ssp.feature.retail.RefundFlow.RefundState.Failed -> { paymentStatus = OureaPaymentUiState.Error(r.message); recordsReload++ }
                            is com.goldsky.ssp.feature.retail.RefundFlow.RefundState.NeedsCard -> { paymentStatus = OureaPaymentUiState.Idle; refundNeedsCard = r }
                        }
                    }
                    var qrState by remember { mutableStateOf<OureaQrUiState>(OureaQrUiState.Hidden) }

                    // Sync Customer Facing Display (CFD) on Q3mini as cart items change
                    val cartList = retailViewModel.cart
                    // Keyed on every line and quantity: size + total qty missed changes that kept both.
                    // And on the total after discounts, so a discount shows on the Eye too.
                    val cartNet by retailViewModel.totalCents.collectAsState()
                    // Bumped when a payment is backed out of: the Eye left the cart for the tip / amount due.
                    var eyeCartAgain by remember { mutableStateOf(0) }
                    LaunchedEffect(cartList.map { Triple(it.id, it.quantity, it.unitPriceCents) }, cartNet, eyeCartAgain) {
                        if (cartList.isEmpty()) {
                            paymentProvider.showIdleScreen()
                        } else {
                            val sale = retailViewModel.checkoutSale()
                            val cfdItems = sale.lines.map { line ->
                                IPaymentProvider.CfdItem(line.name, line.quantity, line.unitPriceCents)
                            }
                            paymentProvider.updateLiveCart(cfdItems, sale.subtotalCents, sale.taxCents, sale.discountCents)
                        }
                    }

                    fun onPaymentSettled(state: OureaPaymentUiState) {
                        val number = orderTicket?.number
                        paymentStatus = if (state is OureaPaymentUiState.Success) state.copy(orderNumber = number) else state
                        if (state is OureaPaymentUiState.Success) {
                            paymentProvider.showThankYouScreen(number)
                            orderTicket = null
                            if (checkPay != null) checkPay = null else retailViewModel.clearCart()
                            FeedbackManager.success(this@MainActivity)
                        }
                    }
                    fun payWith(method: String, subtotal: Int, tax: Int, tip: Int) {
                        val extras = checkExtras(checkPay, dine, signedIn.name)
                        when (method) {
                            "CARD" -> { confirmingPayment = null; triggerPaymentFlow(subtotal, tax, tip, saleLines, orderTicket, ::onPaymentSettled, extras) }
                            "QR" -> { confirmingPayment = null; startQrPayment(subtotal, tax, tip, saleLines, orderTicket, onQrStateChange = { qrState = it }, onSettled = ::onPaymentSettled, extras = extras) }
                            "CASH" -> { confirmingPayment = Triple(subtotal, tax, tip); showCashDialog = true }
                            "MEMBER" -> { confirmingPayment = Triple(subtotal, tax, tip); showMemberDialog = true }
                        }
                    }

                    Row(modifier = Modifier.fillMaxSize()) {
                        OureaSidebar(
                            current = destination,
                            onSelect = { destination = it },
                            onLock = { StaffRepository.signOut(); staff = null; destination = OureaDestination.CHECKOUT },
                            isManager = signedIn.isManager
                        )
                        Box(modifier = Modifier.weight(1f).background(OureaBackground)) {
                            when (destination) {
                                OureaDestination.TABLES, OureaDestination.CHECKOUT -> {
                                    val confirming = confirmingPayment
                                    val openCheck = dine.check
                                    if (confirming != null) {
                                        val (subtotal, tax, tip) = confirming
                                        // Staff choose how the customer pays; with tips on, the customer
                                        // then picks the tip on the Eye and that payment starts at once
                                        // (card: straight to "tap your card"), not back to this screen.
                                        fun choose(method: String) {
                                            if (DeviceRepository.isTipsEnabled()) { tipFor = method; activeSaleData = subtotal to tax }
                                            else payWith(method, subtotal, tax, tip)
                                        }
                                        OureaPaymentScreen(
                                            subtotalCents = subtotal,
                                            taxCents = tax,
                                            tipCents = tip,
                                            tipByCustomer = DeviceRepository.isTipsEnabled(),
                                            onChargeCard = { choose("CARD") },
                                            onCash = { choose("CASH") },
                                            onQr = { choose("QR") },
                                            // Member-card tier discounts would leave a check part-paid: counter sales only.
                                            onMember = {
                                                if (checkPay != null) paymentStatus = OureaPaymentUiState.Error(getString(R.string.ourea_member_not_for_checks))
                                                else choose("MEMBER")
                                            },
                                            onBack = { confirmingPayment = null; checkPay = null; eyeCartAgain++ }
                                        )
                                    } else if (destination == OureaDestination.TABLES && openCheck == null) {
                                        com.goldsky.ssp.feature.retail.dine.DineFloor(dine, signedIn.id, signedIn.name, minCell = 150.dp)
                                    } else if (destination == OureaDestination.TABLES && openCheck != null) {
                                        com.goldsky.ssp.feature.retail.dine.DineRefresh(dine)
                                        OureaCheckoutScreen(
                                            viewModel = roundViewModel,
                                            onCheckout = {},
                                            checkMode = OureaCheckMode(
                                                detail = openCheck,
                                                busy = dine.busy,
                                                onSend = { dine.sendRound(this@MainActivity, roundViewModel, signedIn.name) },
                                                onBill = { dine.printBill(this@MainActivity) },
                                                onPay = { choosingCheckPay = true },
                                                onBack = { if (roundViewModel.cart.isNotEmpty()) leavingWithRound = true else dine.closeCheck() },
                                                onVoid = { item, approver -> dine.voidItem(item, approver) },
                                                onCancelTable = { dine.cancelEmptyCheck() }
                                            )
                                        )
                                    } else {
                                        OureaCheckoutScreen(
                                            viewModel = retailViewModel,
                                            onCheckout = {
                                                // Priced here, not the cart's running total: the sale is what gets charged.
                                                val sale = retailViewModel.checkoutSale()
                                                val subtotal = sale.subtotalCents
                                                saleLines = sale.lines
                                                discountSale = sale
                                                val tax = sale.taxCents
                                                orderTicket = if (DeviceRepository.getBusinessType() == DeviceRepository.BusinessType.QUICK_SERVICE)
                                                    retailViewModel.kitchenTicket(this@MainActivity, signedIn.name) else null
                                                // How to pay first; the tip (if the store asks for one) comes with it.
                                                confirmingPayment = Triple(subtotal, tax, 0)
                                            }
                                        )
                                    }
                                }
                                // Insights, products and Settings: managers only (their rail icons are hidden too).
                                OureaDestination.INSIGHTS -> if (signedIn.isManager) OureaDashboardScreen()
                                OureaDestination.APEX -> if (signedIn.isManager) com.goldsky.ssp.feature.apex.ApexScreen(signedIn)
                                OureaDestination.PRODUCTS -> if (signedIn.isManager) OureaProductsScreen()
                                OureaDestination.TRANSACTIONS -> TransactionHistoryScreen(
                                    onlyStaffId = if (signedIn.isManager) null else signedIn.id,
                                    onRefund = { order, approver ->
                                        com.goldsky.ssp.feature.retail.RefundFlow.start(this@MainActivity, paymentProvider, order, approver, ::onRefundState)
                                    },
                                    onOpenDayReport = { destination = OureaDestination.DAY_REPORT },
                                    reloadKey = recordsReload
                                )
                                OureaDestination.DAY_REPORT -> if (signedIn.isManager) com.goldsky.ssp.feature.retail.screens.DayReportScreen(onBack = { destination = OureaDestination.TRANSACTIONS })
                                OureaDestination.SETTINGS -> if (signedIn.isManager) SettingsScreen(showBusinessType = true, manageTables = true)
                            }
                        }
                    }

                    activeSaleData?.let { (subtotal, tax) ->
                        // Keyed per sale: a new checkout starts a fresh request to the Eye.
                        key(activeSaleData) {
                            OureaTipStep(
                                provider = paymentProvider,
                                basisCents = subtotal,
                                totalCents = subtotal + tax,
                                cardNext = tipFor == "CARD",
                                // Dine-in: the customer never saw the check being taken -- the bill
                                // comes first. A counter sale was watched on the display already.
                                review = checkPay?.let { cp ->
                                    cp.billReview(dine.check, getString(com.goldsky.ssp.feature.retail.R.string.dine_review_title, cp.tableName, cp.checkNo))
                                },
                                onTip = { tip ->
                                    val method = tipFor
                                    activeSaleData = null
                                    tipFor = null
                                    if (method != null) payWith(method, subtotal, tax, tip)
                                },
                                // Back to choosing how to pay.
                                onCancel = { activeSaleData = null; tipFor = null; eyeCartAgain++ }
                            )
                        }
                    }

                    refundNeedsCard?.let { need ->
                        com.goldsky.ssp.feature.retail.RefundNeedsCardDialog(
                            amountCents = need.order.amountCents,
                            onCardTapped = {
                                refundNeedsCard = null
                                com.goldsky.ssp.feature.retail.RefundFlow.withCard(this@MainActivity, paymentProvider, need.order, need.approver, ::onRefundState)
                            },
                            onRefundedInPortal = {
                                refundNeedsCard = null
                                com.goldsky.ssp.feature.retail.RefundFlow.refundedInPortal(this@MainActivity, need.order, need.approver, ::onRefundState)
                            },
                            onCancel = { refundNeedsCard = null }
                        )
                    }

                    // Dine-in: how this payment splits the check, then the usual tip / method / money.
                    val payingCheck = dine.check
                    if (choosingCheckPay && payingCheck != null) {
                        com.goldsky.ssp.feature.retail.dine.CheckPayDialog(
                            detail = payingCheck,
                            onPay = { pay ->
                                choosingCheckPay = false
                                checkPay = pay
                                saleLines = pay.lines
                                orderTicket = null
                                val tax = pay.taxPart(payingCheck)
                                val sub = pay.amountCents - tax
                                confirmingPayment = Triple(sub, tax, 0)
                            },
                            onDismiss = { choosingCheckPay = false }
                        )
                    }
                    if (leavingWithRound) {
                        AlertDialog(
                            onDismissRequest = { leavingWithRound = false },
                            title = { Text(stringResource(R.string.ourea_round_not_sent_title, roundViewModel.cart.sumOf { it.quantity })) },
                            text = { Text(stringResource(R.string.ourea_round_not_sent_desc)) },
                            confirmButton = {
                                Button(onClick = { leavingWithRound = false; dine.sendRound(this@MainActivity, roundViewModel, signedIn.name) }) {
                                    Text(stringResource(R.string.ourea_round_send))
                                }
                            },
                            dismissButton = {
                                Row {
                                    TextButton(onClick = { leavingWithRound = false; roundViewModel.clearCart(); dine.closeCheck() }) {
                                        Text(stringResource(R.string.ourea_round_discard), color = MaterialTheme.colorScheme.error)
                                    }
                                    TextButton(onClick = { leavingWithRound = false }) { Text(stringResource(R.string.ourea_round_stay)) }
                                }
                            }
                        )
                    }
                    dine.message?.let { code ->
                        AlertDialog(
                            onDismissRequest = { dine.clearMessage() },
                            text = { Text(com.goldsky.ssp.feature.retail.dine.dineMessage(code)) },
                            confirmButton = { Button(onClick = { dine.clearMessage() }) { Text(stringResource(R.string.ourea_ok)) } }
                        )
                    }

                    when (val state = paymentStatus) {
                        is OureaPaymentUiState.Processing -> OureaPaymentProcessingDialog(state.message, state.waitingForCard)
                        // A sale: the celebration, with what this sale actually took (the cart is
                        // already cleared by onPaymentSettled, and a dine-in check never was the
                        // cart), the payment's own message (approval code, change due) and the
                        // call number. A refund (no amount) keeps the plain result dialog.
                        is OureaPaymentUiState.Success -> if (state.amountCents != null) {
                            com.goldsky.ssp.feature.retail.ui.CheckoutSuccessCelebration(
                                amountCents = state.amountCents,
                                detail = state.message,
                                orderNumber = state.orderNumber,
                                title = stringResource(R.string.ourea_payment_success),
                                orderNumberLabel = stringResource(R.string.ourea_order_number),
                                dismissLabel = stringResource(R.string.ourea_ok),
                                onDismiss = { paymentStatus = OureaPaymentUiState.Idle }
                            )
                        } else {
                            OureaPaymentResultDialog(true, state.message, state.orderNumber) { paymentStatus = OureaPaymentUiState.Idle }
                        }
                        is OureaPaymentUiState.Error -> OureaPaymentResultDialog(false, state.message) { paymentStatus = OureaPaymentUiState.Idle }
                        else -> {}
                    }

                    if (showCashDialog) {
                        val (subtotal, tax, tip) = confirmingPayment ?: Triple(0, 0, 0)
                        OureaCashDialog(
                            totalCents = subtotal + tax + tip,
                            onConfirm = { cashReceivedCents ->
                                showCashDialog = false
                                confirmingPayment = null
                                triggerCashPayment(subtotal, tax, tip, cashReceivedCents, saleLines, orderTicket, ::onPaymentSettled, checkExtras(checkPay, dine, signedIn.name))
                            },
                            onDismiss = { showCashDialog = false }
                        )
                    }

                    if (showMemberDialog) {
                        val (subtotal, tax, tip) = confirmingPayment ?: Triple(0, 0, 0)
                        OureaMemberDialog(
                            onSubmit = { code ->
                                showMemberDialog = false
                                confirmingPayment = null
                                triggerVipPayment(subtotal, tax, tip, code, orderTicket, ::onPaymentSettled)
                            },
                            onDismiss = { showMemberDialog = false }
                        )
                    }

                    val currentQrState = qrState
                    if (currentQrState !is OureaQrUiState.Hidden) {
                        OureaQrPaymentDialog(
                            state = currentQrState,
                            onCancel = {
                                qrPollJob?.cancel()
                                qrPollJob = null
                                qrState = OureaQrUiState.Hidden
                                // Without this, the PENDING record created at the top of
                                // startQrPayment is abandoned forever: cancel() unwinds the
                                // poll loop via CancellationException before it ever reaches
                                // updatePaymentStatus, so nothing else marks the row settled.
                                activeQrTxId?.let { txId ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        TransactionRepository.updatePaymentStatus(this@MainActivity, txId, "CANCELLED")
                                    }
                                }
                                activeQrTxId = null
                            }
                        )
                    }
                }
            } }
        }
    }

    /**
     * Identical flow to app/iris's triggerPaymentFlow (real
     * PaymentProviderFactory call, TransactionRepository writes, receipt
     * print) -- duplicated rather than shared because PaymentUiState/the
     * payment dialogs aren't factored into a shared module in this codebase
     * yet (same as app/iris, which doesn't share them with anyone either).
     */
    /**
     * The counter sale being paid, for its discounts on the transaction row. Only used when
     * a payment carries exactly its lines: a table's check (other lines) never picks up a
     * counter sale's discounts.
     */
    private var discountSale: com.goldsky.ssp.model.CheckoutSale? = null

    // Equal, not identical: Compose state keeps the old list when an equal one is set (the same
    // cart checked out again after "Back to Order"), and that lost the discounts. Lines carry
    // their discount share, so lines equal to the sale's are that sale.
    private fun discountSaleFor(lines: List<com.goldsky.ssp.model.SaleLine>) = discountSale?.takeIf { it.lines == lines }

    private fun triggerPaymentFlow(subtotal: Int, tax: Int, tip: Int, lines: List<com.goldsky.ssp.model.SaleLine>, ticket: com.goldsky.ssp.model.KitchenTicket?, onStateChange: (OureaPaymentUiState) -> Unit, extras: PayExtras? = null) {
        val total = subtotal + tax + tip
        val ref = "OUR-" + System.currentTimeMillis()
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
        ).withOrder(ticket).withDiscounts(discountSaleFor(lines))

        CoroutineScope(Dispatchers.IO).launch {
            TransactionRepository.recordTransaction(this@MainActivity, record)
            // After the parent row: transaction_items references it.
            TransactionRepository.recordItems(this@MainActivity, ref, sn, lines)
        }

        onStateChange(OureaPaymentUiState.Processing("Processing $${"%.2f".format(total / 100.0)}...", waitingForCard = true))

        var card: IPaymentProvider.CardInfo? = null
        var cancelled = false
        paymentProvider.startSale(total, ref, object : IPaymentProvider.PaymentCallback {
            override fun onCardInfo(info: IPaymentProvider.CardInfo) { card = info }
            override fun onCancelled() { cancelled = true }

            override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    val c = card
                    TransactionRepository.updatePaymentStatus(
                        this@MainActivity, ref, "PAID", entryMode,
                        paymentMethod = c?.let { com.goldsky.ssp.payment.CardTypeClassifier.paymentMethod(it.scheme, it.aid, it.brand) },
                        cardAid = c?.aid, cardBin = c?.bin, cardBrand = c?.brand,
                        authCode = authCode
                    )
                    extras?.onPaid?.invoke(ref, "CARD", tip)
                    ReceiptPrinterManager.printOrder(this@MainActivity, ticket, ReceiptPrinterManager.ReceiptData(
                        subtotalCents = subtotal,
                        taxCents = tax,
                        tipCents = tip,
                        amountCents = total,
                        refNum = ref,
                        deviceSn = sn,
                        lines = lines,
                        banner = extras?.banner
                    ), DeviceRepository.getPersistedHardwareVendor())
                }
                onStateChange(OureaPaymentUiState.Success(getString(R.string.ourea_approved_code, authCode), amountCents = total))
            }
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                CoroutineScope(Dispatchers.IO).launch {
                    // CANCELLED / DECLINED: "FAILED" isn't an allowed payment_status
                    // and left the row PENDING (same bug as Iris had).
                    TransactionRepository.recordFailedCardSale(this@MainActivity, ref, card, cancelled)
                }
                onStateChange(OureaPaymentUiState.Error(errorMsg))
            }
            // No result from the card terminal: the card may have been charged. The row
            // stays PENDING (not DECLINED) so PendingResolver settles it from the
            // terminal's own record, and staff are told not to charge again.
            override fun onResultUnknown(message: String) {
                onStateChange(OureaPaymentUiState.Error(message))
            }
            override fun onProgress(message: String) {
                onStateChange(OureaPaymentUiState.Processing(message, waitingForCard = true))
            }
        })
    }

    /**
     * Cash has no gateway/hardware round trip -- staff is attesting the cash
     * was physically received -- so this records PAID directly (unlike the
     * card/QR paths, which start PENDING and flip to PAID once a backend
     * confirms). [cashReceivedCents] is only used to report change due; the
     * caller (OureaCashDialog) already guards against cashReceivedCents < total.
     */
    private fun triggerCashPayment(subtotal: Int, tax: Int, tip: Int, cashReceivedCents: Int, lines: List<com.goldsky.ssp.model.SaleLine>, ticket: com.goldsky.ssp.model.KitchenTicket?, onStateChange: (OureaPaymentUiState) -> Unit, extras: PayExtras? = null) {
        val total = subtotal + tax + tip
        val ref = "OUR-CASH-" + System.currentTimeMillis()
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "MOCK_SN"

        // The cashier has the cash in hand: done now. Upload (queued offline if the
        // network is down) and printing follow in the background -- waiting on two
        // cloud writes kept the dialog away 6-9 s.
        val change = cashReceivedCents - total
        onStateChange(OureaPaymentUiState.Success("Cash received. Change due: $${"%.2f".format(change / 100.0)}", amountCents = total))

        CoroutineScope(Dispatchers.IO).launch {
            TransactionRepository.recordTransaction(
                this@MainActivity,
                TransactionRecord(
                    device_sn = sn,
                    amount = total,
                    subtotal_cents = subtotal,
                    tax_cents = tax,
                    tip_cents = tip,
                    payment_status = "PAID",
                    ecr_ref_num = ref,
                    payment_method = "CASH",
                    entry_mode = "CASH",
                    staff_id = StaffRepository.current?.id,
                    staff_name = StaffRepository.current?.name
                ).withOrder(ticket).withDiscounts(discountSaleFor(lines))
            )
            TransactionRepository.recordItems(this@MainActivity, ref, sn, lines)
            extras?.onPaid?.invoke(ref, "CASH", tip)
            ReceiptPrinterManager.printOrder(this@MainActivity, ticket, ReceiptPrinterManager.ReceiptData(
                subtotalCents = subtotal,
                taxCents = tax,
                tipCents = tip,
                amountCents = total,
                refNum = ref,
                deviceSn = sn,
                lines = lines,
                banner = extras?.banner
            ), DeviceRepository.getPersistedHardwareVendor())
        }
    }

    /**
     * Scan-to-pay via the same Supabase-backed QrPaymentRepository the wash
     * flow uses (feature/wash/MainActivity's initQrPayment) -- real session
     * creation + polling, not a client-side fake timer. [onQrStateChange]
     * drives the dedicated QR dialog (shows the code while waiting);
     * [onSettled] drives the shared success/error dialog once resolved.
     */
    private fun startQrPayment(
        subtotal: Int,
        tax: Int,
        tip: Int,
        lines: List<com.goldsky.ssp.model.SaleLine>,
        ticket: com.goldsky.ssp.model.KitchenTicket?,
        onQrStateChange: (OureaQrUiState) -> Unit,
        onSettled: (OureaPaymentUiState) -> Unit,
        extras: PayExtras? = null
    ) {
        val total = subtotal + tax + tip
        val txId = "OUR-QR-" + System.currentTimeMillis()
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "MOCK_SN"

        onQrStateChange(OureaQrUiState.Creating)
        activeQrTxId = txId

        qrPollJob = CoroutineScope(Dispatchers.IO).launch {
            TransactionRepository.recordTransaction(
                this@MainActivity,
                TransactionRecord(
                    device_sn = sn,
                    amount = total,
                    subtotal_cents = subtotal,
                    tax_cents = tax,
                    tip_cents = tip,
                    payment_status = "PENDING",
                    ecr_ref_num = txId,
                    payment_method = "QR_CODE",
                    staff_id = StaffRepository.current?.id,
                    staff_name = StaffRepository.current?.name
                ).withOrder(ticket).withDiscounts(discountSaleFor(lines))
            )
            TransactionRepository.recordItems(this@MainActivity, txId, sn, lines)

            val codeUrl = QrPaymentRepository.createSession(txId, sn, total)
            if (codeUrl == null) {
                activeQrTxId = null
                withContext(Dispatchers.Main) { onQrStateChange(OureaQrUiState.Hidden) }
                // Allowed statuses only (was "FAILED", which left the row PENDING).
                TransactionRepository.updatePaymentStatus(this@MainActivity, txId, "DECLINED")
                withContext(Dispatchers.Main) { onSettled(OureaPaymentUiState.Error("Failed to create QR session, try again")) }
                return@launch
            }
            val bitmap = QrUtils.generateQrCode(codeUrl, 512, 512)
            if (bitmap == null) {
                activeQrTxId = null
                withContext(Dispatchers.Main) { onQrStateChange(OureaQrUiState.Hidden) }
                TransactionRepository.updatePaymentStatus(this@MainActivity, txId, "DECLINED")
                withContext(Dispatchers.Main) { onSettled(OureaPaymentUiState.Error("Failed to render QR code")) }
                return@launch
            }
            withContext(Dispatchers.Main) { onQrStateChange(OureaQrUiState.Ready(bitmap, total)) }

            val paid = QrPaymentRepository.pollUntilPaid(txId)
            activeQrTxId = null
            withContext(Dispatchers.Main) { onQrStateChange(OureaQrUiState.Hidden) }
            if (paid) {
                TransactionRepository.updatePaymentStatus(this@MainActivity, txId, "PAID")
                extras?.onPaid?.invoke(txId, "QR_CODE", tip)
                withContext(Dispatchers.Main) { onSettled(OureaPaymentUiState.Success("QR payment received", amountCents = total)) }
                ReceiptPrinterManager.printOrder(this@MainActivity, ticket, ReceiptPrinterManager.ReceiptData(
                    subtotalCents = subtotal,
                    taxCents = tax,
                    tipCents = tip,
                    amountCents = total,
                    refNum = txId,
                    deviceSn = sn,
                    lines = lines,
                    banner = extras?.banner
                ), DeviceRepository.getPersistedHardwareVendor())
            } else {
                TransactionRepository.updatePaymentStatus(this@MainActivity, txId, "CANCELLED")
                withContext(Dispatchers.Main) { onSettled(OureaPaymentUiState.Error("QR payment was not completed in time")) }
            }
        }
    }

    /**
     * Member-card payment by manually-entered 6-character code (same
     * format/regex VipRepository.resolveCardUidByQrCode expects -- see
     * feature/wash/MainActivity's member-QR-scan path) rather than an NFC
     * tap: Ourea's wired hardware providers are card-payment terminals, not
     * membership-card NFC readers, and there's no scanner hardware wired
     * into this app shell yet -- a barcode-scanner-as-keyboard-input or
     * staff-typed code both work fine against a plain text field. Tiered
     * discount logic (PLATINUM/GOLD) mirrors feature/wash's initVipPayment
     * verbatim, applied to the cart total rather than a single wash price.
     */
    private fun triggerVipPayment(subtotal: Int, tax: Int, tip: Int, memberCode: String, ticket: com.goldsky.ssp.model.KitchenTicket?, onStateChange: (OureaPaymentUiState) -> Unit) {
        val total = subtotal + tax + tip
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "MOCK_SN"

        onStateChange(OureaPaymentUiState.Processing(getString(R.string.ourea_looking_up_member)))

        CoroutineScope(Dispatchers.IO).launch {
            val cardUid = VipRepository.resolveCardUidByQrCode(memberCode)
            if (cardUid == null) {
                withContext(Dispatchers.Main) { onStateChange(OureaPaymentUiState.Error("Member code not recognized")) }
                return@launch
            }

            val card = VipRepository.getVipCard(cardUid)
            if (card == null || !card.is_active) {
                withContext(Dispatchers.Main) { onStateChange(OureaPaymentUiState.Error("This member card has been deactivated")) }
                return@launch
            }

            val discountFactor = when (card.tier) {
                "PLATINUM" -> 0.85
                "GOLD" -> 0.90
                else -> 1.0
            }
            val finalTotal = (total * discountFactor).toInt()
            // Scale the line items by the same factor so subtotal+tax+tip reconciles
            // exactly with amount/finalTotal on both the transaction record and the
            // printed receipt -- previously these stayed at the undiscounted subtotal/
            // tax/tip while amount dropped to the discounted finalTotal, so a receipt
            // for e.g. a PLATINUM member would show "Subtotal $10.00 + Tax $1.00" but
            // "Total Due $9.35". discTip absorbs the rounding remainder so the three
            // lines always sum to exactly finalTotal.
            val discSubtotal = (subtotal * discountFactor).toInt()
            val discTax = (tax * discountFactor).toInt()
            val discTip = finalTotal - discSubtotal - discTax

            withContext(Dispatchers.Main) { onStateChange(OureaPaymentUiState.Processing("Authorizing member payment...")) }

            when (val result = VipRepository.deductBalance(cardUid, finalTotal)) {
                is VipDeductResult.Success -> {
                    val ref = "OUR-VIP-${cardUid}-" + System.currentTimeMillis()
                    TransactionRepository.recordTransaction(
                        this@MainActivity,
                        TransactionRecord(
                            device_sn = sn,
                            amount = finalTotal,
                            subtotal_cents = discSubtotal,
                            tax_cents = discTax,
                            tip_cents = discTip,
                            payment_status = "PAID",
                            ecr_ref_num = ref,
                            payment_method = "VIP_CARD",
                            entry_mode = "MANUAL_CODE",
                            // No transaction_items here: the tier discount scales the
                            // totals, so undiscounted lines wouldn't add up to them.
                            staff_id = StaffRepository.current?.id,
                            staff_name = StaffRepository.current?.name
                        ).withOrder(ticket)
                    )
                    val discountMsg = if (finalTotal < total) getString(R.string.ourea_discount_applied, card.tier) else ""
                    withContext(Dispatchers.Main) { onStateChange(OureaPaymentUiState.Success("Member payment approved$discountMsg", amountCents = finalTotal)) }
                    ReceiptPrinterManager.printOrder(this@MainActivity, ticket, ReceiptPrinterManager.ReceiptData(
                        subtotalCents = discSubtotal,
                        taxCents = discTax,
                        tipCents = discTip,
                        amountCents = finalTotal,
                        refNum = ref,
                        deviceSn = sn
                    ), DeviceRepository.getPersistedHardwareVendor())
                }
                is VipDeductResult.Rejected -> {
                    val message = when (result.reason) {
                        "card_inactive" -> getString(R.string.ourea_card_inactive)
                        "card_not_found" -> getString(R.string.ourea_card_not_found)
                        else -> getString(R.string.ourea_card_insufficient)
                    }
                    withContext(Dispatchers.Main) { onStateChange(OureaPaymentUiState.Error(message)) }
                }
                VipDeductResult.NetworkError -> {
                    withContext(Dispatchers.Main) { onStateChange(OureaPaymentUiState.Error("Network error -- please try again")) }
                }
            }
        }
    }
}

/** What a payment on a table's check adds: the receipt's banner, and recording it on the check. */
class PayExtras(val banner: String?, val onPaid: suspend (ref: String, method: String, tip: Int) -> Unit)

private fun MainActivity.checkExtras(pay: com.goldsky.ssp.feature.retail.dine.CheckPay?, dine: com.goldsky.ssp.feature.retail.dine.DineViewModel, staffName: String?): PayExtras? =
    pay?.let {
        PayExtras(
            banner = "TABLE ${it.tableName}  #${it.checkNo}" + (if (it.label.isNotEmpty()) "  ${it.label}" else ""),
            onPaid = { ref, method, tip -> dine.paymentDone(this, it, ref, tip, method, staffName) }
        )
    }

sealed class OureaPaymentUiState {
    object Idle : OureaPaymentUiState()
    /** [amountCents]: what this SALE took, set only by the payment flows -- a refund leaves it null. */
    data class Success(val message: String, val orderNumber: Int? = null, val amountCents: Int? = null) : OureaPaymentUiState()
    data class Error(val message: String) : OureaPaymentUiState()
    /** [waitingForCard]: the card terminal is waiting for the customer (radar instead of a spinner). */
    data class Processing(val message: String, val waitingForCard: Boolean = false) : OureaPaymentUiState()
}

/** Drives OureaQrPaymentDialog; totalCents is only needed for the on-screen amount label. */
sealed class OureaQrUiState {
    object Hidden : OureaQrUiState()
    object Creating : OureaQrUiState()
    data class Ready(val bitmap: Bitmap, val totalCents: Int) : OureaQrUiState()
}

@Composable
fun OureaPaymentProcessingDialog(message: String, waitingForCard: Boolean = false) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.medium, color = OureaSurface, tonalElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, com.goldsky.ssp.feature.retail.ui.hairlineColor())) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                // Waiting on the customer's card: rings spreading out (tap here), not a spinner.
                if (waitingForCard) {
                    com.goldsky.ssp.feature.retail.ui.PaymentRadar(OureaGold, size = 112.dp) {
                        Icon(androidx.compose.material.icons.Icons.Default.Contactless, null, tint = OureaGold, modifier = Modifier.size(36.dp))
                    }
                } else CircularProgressIndicator(color = OureaGold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(message, color = OureaTextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OureaPaymentResultDialog(isSuccess: Boolean, message: String, orderNumber: Int? = null, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isSuccess) R.string.ourea_payment_success else R.string.ourea_payment_failed)) },
        text = {
            Column {
                // Quick-service: the call number, big, to tell the customer.
                if (orderNumber != null) {
                    Text(stringResource(R.string.ourea_order_number), fontSize = 16.sp)
                    Text("%03d".format(orderNumber), fontSize = 56.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                }
                Text(message)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.ourea_ok)) } },
        containerColor = if (isSuccess) com.goldsky.ssp.ui.theme.SuccessSlab else com.goldsky.ssp.ui.theme.DangerSlab,
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
