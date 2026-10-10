package com.goldsky.ssp.iris

import android.os.Bundle
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
import com.goldsky.ssp.payment.*
import com.goldsky.ssp.payment.hardware.*
import com.goldsky.ssp.feature.retail.screens.*
import com.goldsky.ssp.feature.retail.RetailRepository
import com.goldsky.ssp.feature.retail.ui.TipSelectionDialog
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.feature.apex.GoldSkyBlue
import com.goldsky.ssp.feature.apex.InsightsScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Config + hardware/payment provider registration live in
        // IrisApplication so WorkManager jobs (batch close, offline replay)
        // started in a cold process without this Activity still find them.
        DeviceRepository.init(this)

        RetailRepository.init(this)

        // Heartbeat, offline transaction replay, daily batch close, storage
        // cleaning -- never scheduled here before, so a sale whose insert
        // failed sat in the offline queue forever and the batch was never
        // auto-settled (WizarPOS Q2, 2026-10-09). No ad screen in Iris.
        AdManager.init(this, syncAds = false)

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
        CoroutineScope(Dispatchers.IO).launch {
            SupabaseClientProvider.ensureAuthenticated()
            DeviceRepository.registerDevice(deviceSn, BuildConfig.VERSION_NAME)
            DeviceRepository.syncDeviceIdentity(deviceSn)
            RetailRepository.syncWithCloud(this@MainActivity)
        }

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val retailViewModel: RetailViewModel = viewModel()
                var paymentStatus by remember { mutableStateOf<PaymentUiState>(PaymentUiState.Idle) }
                // subtotal to tax, staged while the tip dialog is up; the real
                // sale only fires once the customer picks (or skips) a tip.
                var activeSaleData by remember { mutableStateOf<Pair<Int, Int>?>(null) }
                var isUnlocked by remember { mutableStateOf(false) }

                fun pay(subtotal: Int, tax: Int, tip: Int) {
                    triggerPaymentFlow(subtotal, tax, tip) {
                        paymentStatus = it
                        if (it is PaymentUiState.Success) {
                            retailViewModel.clearCart()
                            FeedbackManager.success(this@MainActivity)
                        }
                    }
                }

                if (!isUnlocked) {
                    StaffPinLockScreen(onUnlock = { if (it == "1234") isUnlocked = true })
                } else {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Scaffold(
                            bottomBar = { MainBottomNavigation(navController) }
                        ) { padding ->
                            NavHost(
                                navController = navController,
                                startDestination = "checkout",
                                modifier = Modifier.padding(padding)
                            ) {
                                composable("checkout") {
                                    CheckoutContainer(
                                        retailViewModel = retailViewModel,
                                        onScan = { scanOnce(retailViewModel) },
                                        onCheckout = { subtotal, tax ->
                                            // No tip step unless the store asks for tips (off for retail).
                                            if (DeviceRepository.isTipsEnabled()) activeSaleData = subtotal to tax
                                            else pay(subtotal, tax, 0)
                                        }
                                    )
                                }
                                composable("insights") { InsightsScreen() }
                                composable("transactions") { TransactionHistoryScreen() }
                                composable("expenses") {
                                    ExpenseScreen(onScanReceipt = { navController.navigate("scan_receipt") })
                                }
                                composable("scan_receipt") {
                                    ReceiptScannerScreen(
                                        onReceiptCaptured = { vendor, amount ->
                                            CoroutineScope(Dispatchers.IO).launch {
                                                LocalDatabase.getInstance(this@MainActivity).expenseDao().insert(
                                                    ExpenseEntity(vendor = vendor, amountCents = amount, category = "SUPPLY")
                                                )
                                                withContext(Dispatchers.Main) { navController.popBackStack() }
                                            }
                                        },
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                                composable("settings") { SettingsScreen() }
                            }
                        }

                        // Detail overlays (drawn above the NavHost content, not routes of their own)
                        activeSaleData?.let { (subtotal, tax) ->
                            TipSelectionDialog(
                                subtotalCents = subtotal + tax,
                                onConfirm = { totalWithTip ->
                                    val tip = totalWithTip - (subtotal + tax)
                                    activeSaleData = null
                                    pay(subtotal, tax, tip)
                                },
                                onDismiss = { activeSaleData = null }
                            )
                        }

                        when (val state = paymentStatus) {
                            is PaymentUiState.Processing -> PaymentProcessingDialog(state.message)
                            is PaymentUiState.Success -> PaymentResultDialog(true, "Approved: ${state.authCode}") { paymentStatus = PaymentUiState.Idle }
                            is PaymentUiState.Error -> PaymentResultDialog(false, state.message) { paymentStatus = PaymentUiState.Idle }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    // One scan per tap, not a continuous loop: on WizarPOS the scanner is the
    // camera with a full-screen preview, so restarting it after every result
    // kept that preview over the whole UI (seen on a real Q2 2026-10-09).
    // Failures (incl. the user backing out of the preview) are silent.
    private fun scanOnce(viewModel: RetailViewModel) {
        scannerProvider.startScan(object : IScannerProvider.ScanCallback {
            override fun onScanSuccess(barcode: String) {
                viewModel.addByBarcode(this@MainActivity, barcode)
            }
            override fun onScanFailure(error: String) {
                android.util.Log.d("IrisScan", "Scan ended without a code: $error")
            }
        })
    }

    private fun triggerPaymentFlow(subtotal: Int, tax: Int, tip: Int, onStateChange: (PaymentUiState) -> Unit) {
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
            payment_method = "CREDIT_CARD"
        )

        CoroutineScope(Dispatchers.IO).launch {
            TransactionRepository.recordTransaction(this@MainActivity, record)
        }

        onStateChange(PaymentUiState.Processing("Processing $${"%.2f".format(total / 100.0)}..."))

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
                onStateChange(PaymentUiState.Success(authCode))
            }
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                CoroutineScope(Dispatchers.IO).launch {
                    TransactionRepository.updatePaymentStatus(this@MainActivity, ref, "FAILED")
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
 * KEYPAD/LIBRARY tab switcher hosting whichever "quick" flow actually
 * collects the sale -- restored from the old retail flavor's CheckoutScreen
 * (a container, not a screen of its own; unrelated to the standalone
 * feature.retail.screens.CheckoutScreen file, which nothing currently
 * navigates to). Without this, DineInScreen/DeliveryScreen/ManualAmountScreen
 * were fully-written but unreachable -- RetailStoreScreen was the only thing
 * ever wired to the "checkout" route.
 */
@Composable
fun CheckoutContainer(retailViewModel: RetailViewModel, onScan: () -> Unit, onCheckout: (subtotal: Int, tax: Int) -> Unit) {
    // Amounts with no product behind them (keypad, table, delivery) use the device rate.
    val atDeviceRate = { cents: Int -> onCheckout(cents, SalesTax.cents(cents, SalesTax.deviceRateBps())) }
    var selectedTab by remember { mutableStateOf(0) }
    val retailMode = remember { DeviceRepository.getPersistedRetailMode() }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("KEYPAD", fontWeight = FontWeight.Bold) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("LIBRARY", fontWeight = FontWeight.Bold) })
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                ManualAmountScreen(onInitiatePayment = { amountStr ->
                    val cents = amountStr.replace(".", "").toIntOrNull() ?: 0
                    if (cents > 0) atDeviceRate(cents)
                })
            } else {
                when (retailMode) {
                    com.goldsky.ssp.model.RetailMode.RESTAURANT -> DineInScreen(onTableSelected = { atDeviceRate(5000) })
                    com.goldsky.ssp.model.RetailMode.DELIVERY -> DeliveryScreen(onCollect = { atDeviceRate(it) })
                    else -> RetailStoreScreen(
                        onCheckout = { subtotal -> onCheckout(subtotal, retailViewModel.cartTaxCents()) },
                        viewModel = retailViewModel,
                        onScan = onScan
                    )
                }
            }
        }
    }
}

@Composable
fun MainBottomNavigation(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == "checkout",
            onClick = { navController.navigate("checkout") { popUpTo(navController.graph.startDestinationId); launchSingleTop = true } },
            icon = { Icon(Icons.Default.PointOfSale, null) },
            label = { Text("Quick") }
        )
        NavigationBarItem(
            selected = currentRoute == "transactions",
            onClick = { navController.navigate("transactions") { launchSingleTop = true } },
            icon = { Icon(Icons.Default.ReceiptLong, null) },
            label = { Text("Records") }
        )
        NavigationBarItem(
            selected = currentRoute == "insights",
            onClick = { navController.navigate("insights") { launchSingleTop = true } },
            icon = {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (currentRoute == "insights") GoldSkyBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            label = {
                Text(
                    text = "AI",
                    color = if (currentRoute == "insights") GoldSkyBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontWeight = if (currentRoute == "insights") FontWeight.Bold else FontWeight.Normal
                )
            }
        )
        NavigationBarItem(
            selected = currentRoute == "expenses",
            onClick = { navController.navigate("expenses") { launchSingleTop = true } },
            icon = { Icon(Icons.Default.Receipt, null) },
            label = { Text("Expenses") }
        )
        NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = { navController.navigate("settings") { launchSingleTop = true } },
            icon = { Icon(Icons.Default.Settings, null) },
            label = { Text("Settings") }
        )
    }
}

sealed class PaymentUiState {
    object Idle : PaymentUiState()
    data class Success(val authCode: String) : PaymentUiState()
    data class Error(val message: String) : PaymentUiState()
    data class Processing(val message: String) : PaymentUiState()
}

@Composable
fun PaymentProcessingDialog(message: String) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(message, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PaymentResultDialog(isSuccess: Boolean, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSuccess) "Payment Success" else "Payment Failed") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onDismiss) { Text("OK") }
        },
        containerColor = if (isSuccess) Color(0xFF1B5E20) else Color(0xFFB71C1C),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
