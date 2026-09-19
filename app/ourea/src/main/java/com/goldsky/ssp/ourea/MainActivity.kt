package com.goldsky.ssp.ourea

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.common.FeedbackManager
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.common.QrUtils
import com.goldsky.ssp.payment.*
import com.goldsky.ssp.payment.hardware.*
import com.goldsky.ssp.iris.hardware.idtech.IdTechHardwareProvider
import com.goldsky.ssp.iris.hardware.pax.PaxHardwareProvider
import com.goldsky.ssp.iris.hardware.wizarpos.WizarPosHardwareProvider
import com.goldsky.ssp.feature.retail.RetailRepository
import com.goldsky.ssp.feature.retail.screens.SettingsScreen
import com.goldsky.ssp.feature.retail.screens.StaffPinLockScreen
import com.goldsky.ssp.feature.retail.screens.TransactionHistoryScreen
import com.goldsky.ssp.feature.retail.ui.TipSelectionDialog
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

    private val paymentProvider by lazy {
        PaymentProviderFactory.getPaymentProvider(this, DeviceRepository.getPersistedHardwareVendor())
    }

    /** Only one QR session can be in flight; cancelled if the staff backs out of the dialog. */
    private var qrPollJob: Job? = null

    /**
     * ecr_ref_num of the in-flight QR session, so a manual cancel can mark
     * the PENDING record CANCELLED instead of abandoning it -- see the
     * cancel handling in onCreate's setContent block.
     */
    private var activeQrTxId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CoreConfig.isMock = BuildConfig.IS_MOCK
        HardwareConfig.isMock = BuildConfig.IS_MOCK
        CoreConfig.supabaseUrl = BuildConfig.SUPABASE_URL
        CoreConfig.supabaseKey = BuildConfig.SUPABASE_KEY

        val idTechProvider = IdTechHardwareProvider()
        val paxProvider = PaxHardwareProvider()
        val wizarPosProvider = WizarPosHardwareProvider()

        idTechProvider.init(this)
        paxProvider.init(this)
        wizarPosProvider.init(this)

        HardwareFactory.registerHardwareProvider("IDTECH", idTechProvider)
        HardwareFactory.registerHardwareProvider("PAX", paxProvider)
        HardwareFactory.registerHardwareProvider("WIZARPOS", wizarPosProvider)

        PaymentProviderFactory.registerPaymentProvider("IDTECH", idTechProvider.getPaymentProvider())
        PaymentProviderFactory.registerPaymentProvider("PAX", paxProvider.getPaymentProvider())
        PaymentProviderFactory.registerPaymentProvider("WIZARPOS", wizarPosProvider.getPaymentProvider())

        DeviceRepository.init(this)
        RetailRepository.init(this)

        val deviceSn = try {
            HardwareFactory.getHardwareProvider(DeviceRepository.getPersistedHardwareVendor()).getSerialNumber(this)
        } catch (e: Exception) {
            "MOCK_SN_${System.currentTimeMillis()}"
        }
        DeviceRepository.persistDeviceSn(deviceSn)
        CoroutineScope(Dispatchers.IO).launch {
            SupabaseClientProvider.ensureAuthenticated()
            DeviceRepository.registerDevice(deviceSn, BuildConfig.VERSION_NAME)
            DeviceRepository.syncDeviceIdentity(deviceSn)
            RetailRepository.syncWithCloud(this@MainActivity)
        }

        setContent {
            OureaTheme {
                var isUnlocked by remember { mutableStateOf(false) }

                if (!isUnlocked) {
                    StaffPinLockScreen(onUnlock = { if (it == "1234") isUnlocked = true })
                } else {
                    val retailViewModel: RetailViewModel = viewModel()
                    var destination by remember { mutableStateOf(OureaDestination.CHECKOUT) }
                    var paymentStatus by remember { mutableStateOf<OureaPaymentUiState>(OureaPaymentUiState.Idle) }
                    var activeSaleData by remember { mutableStateOf<Pair<Int, Int>?>(null) }
                    var confirmingPayment by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
                    var showCashDialog by remember { mutableStateOf(false) }
                    var showMemberDialog by remember { mutableStateOf(false) }
                    var qrState by remember { mutableStateOf<OureaQrUiState>(OureaQrUiState.Hidden) }

                    fun onPaymentSettled(state: OureaPaymentUiState) {
                        paymentStatus = state
                        if (state is OureaPaymentUiState.Success) {
                            retailViewModel.clearCart()
                            FeedbackManager.success(this@MainActivity)
                        }
                    }

                    Row(modifier = Modifier.fillMaxSize()) {
                        OureaSidebar(
                            current = destination,
                            onSelect = { destination = it },
                            onLock = { isUnlocked = false }
                        )
                        Box(modifier = Modifier.weight(1f).background(OureaBackground)) {
                            when (destination) {
                                OureaDestination.CHECKOUT -> {
                                    val confirming = confirmingPayment
                                    if (confirming != null) {
                                        val (subtotal, tax, tip) = confirming
                                        OureaPaymentScreen(
                                            subtotalCents = subtotal,
                                            taxCents = tax,
                                            tipCents = tip,
                                            onChargeCard = {
                                                confirmingPayment = null
                                                triggerPaymentFlow(subtotal, tax, tip, ::onPaymentSettled)
                                            },
                                            onCash = { showCashDialog = true },
                                            onQr = {
                                                confirmingPayment = null
                                                startQrPayment(subtotal, tax, tip, onQrStateChange = { qrState = it }, onSettled = ::onPaymentSettled)
                                            },
                                            onMember = { showMemberDialog = true },
                                            onBack = { confirmingPayment = null }
                                        )
                                    } else {
                                        OureaCheckoutScreen(
                                            viewModel = retailViewModel,
                                            onCheckout = { subtotal ->
                                                val taxRate = DeviceRepository.getTaxRate()
                                                val tax = (subtotal * taxRate / 100.0).toInt()
                                                activeSaleData = subtotal to tax
                                            }
                                        )
                                    }
                                }
                                OureaDestination.INSIGHTS -> OureaDashboardScreen()
                                OureaDestination.PRODUCTS -> OureaProductsScreen()
                                OureaDestination.TRANSACTIONS -> TransactionHistoryScreen()
                                OureaDestination.SETTINGS -> SettingsScreen()
                            }
                        }
                    }

                    activeSaleData?.let { (subtotal, tax) ->
                        TipSelectionDialog(
                            subtotalCents = subtotal + tax,
                            onConfirm = { totalWithTip ->
                                val tip = totalWithTip - (subtotal + tax)
                                activeSaleData = null
                                confirmingPayment = Triple(subtotal, tax, tip)
                            },
                            onDismiss = { activeSaleData = null }
                        )
                    }

                    when (val state = paymentStatus) {
                        is OureaPaymentUiState.Processing -> OureaPaymentProcessingDialog(state.message)
                        is OureaPaymentUiState.Success -> OureaPaymentResultDialog(true, state.message) { paymentStatus = OureaPaymentUiState.Idle }
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
                                triggerCashPayment(subtotal, tax, tip, cashReceivedCents, ::onPaymentSettled)
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
                                triggerVipPayment(subtotal, tax, tip, code, ::onPaymentSettled)
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
            }
        }
    }

    /**
     * Identical flow to app/iris's triggerPaymentFlow (real
     * PaymentProviderFactory call, TransactionRepository writes, receipt
     * print) -- duplicated rather than shared because PaymentUiState/the
     * payment dialogs aren't factored into a shared module in this codebase
     * yet (same as app/iris, which doesn't share them with anyone either).
     */
    private fun triggerPaymentFlow(subtotal: Int, tax: Int, tip: Int, onStateChange: (OureaPaymentUiState) -> Unit) {
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
            payment_method = "CREDIT_CARD"
        )

        CoroutineScope(Dispatchers.IO).launch {
            TransactionRepository.recordTransaction(this@MainActivity, record)
        }

        onStateChange(OureaPaymentUiState.Processing("Processing $${"%.2f".format(total / 100.0)}..."))

        paymentProvider.startSale(total, ref, object : IPaymentProvider.PaymentCallback {
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    TransactionRepository.updatePaymentStatus(this@MainActivity, ref, "PAID", entryMode)
                    ReceiptPrinterManager.printReceipt(
                        this@MainActivity,
                        ReceiptPrinterManager.ReceiptData(
                            subtotalCents = subtotal,
                            taxCents = tax,
                            tipCents = tip,
                            amountCents = total,
                            refNum = ref,
                            deviceSn = sn
                        ),
                        vendor = DeviceRepository.getPersistedHardwareVendor()
                    )
                }
                onStateChange(OureaPaymentUiState.Success("Approved: $authCode"))
            }
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                CoroutineScope(Dispatchers.IO).launch {
                    TransactionRepository.updatePaymentStatus(this@MainActivity, ref, "FAILED")
                }
                onStateChange(OureaPaymentUiState.Error(errorMsg))
            }
            override fun onProgress(message: String) {
                onStateChange(OureaPaymentUiState.Processing(message))
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
    private fun triggerCashPayment(subtotal: Int, tax: Int, tip: Int, cashReceivedCents: Int, onStateChange: (OureaPaymentUiState) -> Unit) {
        val total = subtotal + tax + tip
        val ref = "OUR-CASH-" + System.currentTimeMillis()
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "MOCK_SN"

        onStateChange(OureaPaymentUiState.Processing("Recording cash payment..."))

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
                    entry_mode = "CASH"
                )
            )
            ReceiptPrinterManager.printReceipt(
                this@MainActivity,
                ReceiptPrinterManager.ReceiptData(
                    subtotalCents = subtotal,
                    taxCents = tax,
                    tipCents = tip,
                    amountCents = total,
                    refNum = ref,
                    deviceSn = sn
                ),
                vendor = DeviceRepository.getPersistedHardwareVendor()
            )
            val change = cashReceivedCents - total
            withContext(Dispatchers.Main) {
                onStateChange(OureaPaymentUiState.Success("Cash received. Change due: $${"%.2f".format(change / 100.0)}"))
            }
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
        onQrStateChange: (OureaQrUiState) -> Unit,
        onSettled: (OureaPaymentUiState) -> Unit
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
                    payment_method = "QR_CODE"
                )
            )

            val codeUrl = QrPaymentRepository.createSession(txId, sn, total)
            if (codeUrl == null) {
                activeQrTxId = null
                withContext(Dispatchers.Main) { onQrStateChange(OureaQrUiState.Hidden) }
                TransactionRepository.updatePaymentStatus(this@MainActivity, txId, "FAILED")
                withContext(Dispatchers.Main) { onSettled(OureaPaymentUiState.Error("Failed to create QR session, try again")) }
                return@launch
            }
            val bitmap = QrUtils.generateQrCode(codeUrl, 512, 512)
            if (bitmap == null) {
                activeQrTxId = null
                withContext(Dispatchers.Main) { onQrStateChange(OureaQrUiState.Hidden) }
                TransactionRepository.updatePaymentStatus(this@MainActivity, txId, "FAILED")
                withContext(Dispatchers.Main) { onSettled(OureaPaymentUiState.Error("Failed to render QR code")) }
                return@launch
            }
            withContext(Dispatchers.Main) { onQrStateChange(OureaQrUiState.Ready(bitmap, total)) }

            val paid = QrPaymentRepository.pollUntilPaid(txId)
            activeQrTxId = null
            withContext(Dispatchers.Main) { onQrStateChange(OureaQrUiState.Hidden) }
            if (paid) {
                TransactionRepository.updatePaymentStatus(this@MainActivity, txId, "PAID")
                ReceiptPrinterManager.printReceipt(
                    this@MainActivity,
                    ReceiptPrinterManager.ReceiptData(
                        subtotalCents = subtotal,
                        taxCents = tax,
                        tipCents = tip,
                        amountCents = total,
                        refNum = txId,
                        deviceSn = sn
                    ),
                    vendor = DeviceRepository.getPersistedHardwareVendor()
                )
                withContext(Dispatchers.Main) { onSettled(OureaPaymentUiState.Success("QR payment received")) }
            } else {
                TransactionRepository.updatePaymentStatus(this@MainActivity, txId, "EXPIRED")
                withContext(Dispatchers.Main) { onSettled(OureaPaymentUiState.Error("QR payment was not completed in time")) }
            }
        }
    }

    /**
     * Member-card payment by manually-entered 12-character code (same
     * format/regex VipRepository.resolveCardUidByQrCode expects -- see
     * feature/wash/MainActivity's member-QR-scan path) rather than an NFC
     * tap: Ourea's wired hardware providers are card-payment terminals, not
     * membership-card NFC readers, and there's no scanner hardware wired
     * into this app shell yet -- a barcode-scanner-as-keyboard-input or
     * staff-typed code both work fine against a plain text field. Tiered
     * discount logic (PLATINUM/GOLD) mirrors feature/wash's initVipPayment
     * verbatim, applied to the cart total rather than a single wash price.
     */
    private fun triggerVipPayment(subtotal: Int, tax: Int, tip: Int, memberCode: String, onStateChange: (OureaPaymentUiState) -> Unit) {
        val total = subtotal + tax + tip
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "MOCK_SN"

        onStateChange(OureaPaymentUiState.Processing("Looking up member..."))

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
                            entry_mode = "MANUAL_CODE"
                        )
                    )
                    ReceiptPrinterManager.printReceipt(
                        this@MainActivity,
                        ReceiptPrinterManager.ReceiptData(
                            subtotalCents = discSubtotal,
                            taxCents = discTax,
                            tipCents = discTip,
                            amountCents = finalTotal,
                            refNum = ref,
                            deviceSn = sn
                        ),
                        vendor = DeviceRepository.getPersistedHardwareVendor()
                    )
                    val discountMsg = if (finalTotal < total) " (${card.tier} discount applied)" else ""
                    withContext(Dispatchers.Main) { onStateChange(OureaPaymentUiState.Success("Member payment approved$discountMsg")) }
                }
                is VipDeductResult.Rejected -> {
                    val message = when (result.reason) {
                        "card_inactive" -> "This member card has been deactivated"
                        "card_not_found" -> "Member card not recognized"
                        else -> "Member card balance insufficient"
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

sealed class OureaPaymentUiState {
    object Idle : OureaPaymentUiState()
    data class Success(val message: String) : OureaPaymentUiState()
    data class Error(val message: String) : OureaPaymentUiState()
    data class Processing(val message: String) : OureaPaymentUiState()
}

/** Drives OureaQrPaymentDialog; totalCents is only needed for the on-screen amount label. */
sealed class OureaQrUiState {
    object Hidden : OureaQrUiState()
    object Creating : OureaQrUiState()
    data class Ready(val bitmap: Bitmap, val totalCents: Int) : OureaQrUiState()
}

@Composable
fun OureaPaymentProcessingDialog(message: String) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.medium, color = OureaSurface, tonalElevation = 8.dp) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = OureaGold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(message, color = OureaTextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OureaPaymentResultDialog(isSuccess: Boolean, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSuccess) "Payment Success" else "Payment Failed") },
        text = { Text(message) },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
        containerColor = if (isSuccess) Color(0xFF1B5E20) else Color(0xFFB71C1C),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
