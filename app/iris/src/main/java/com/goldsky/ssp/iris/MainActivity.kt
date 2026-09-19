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
import com.goldsky.ssp.common.CoreConfig
import com.goldsky.ssp.common.FeedbackManager
import com.goldsky.ssp.common.HardwareConfig
import com.goldsky.ssp.db.ExpenseEntity
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.payment.*
import com.goldsky.ssp.payment.hardware.*
import com.goldsky.ssp.iris.hardware.idtech.IdTechHardwareProvider
import com.goldsky.ssp.iris.hardware.pax.PaxHardwareProvider
import com.goldsky.ssp.iris.hardware.wizarpos.WizarPosHardwareProvider
import com.goldsky.ssp.payment.wizarpos.WizarPosPaymentProvider
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

        val detectedModel = com.goldsky.ssp.DeviceAdapter.getModel()
        val recommendedVendor = com.goldsky.ssp.DeviceAdapter.getRecommendedVendor()
        android.util.Log.i("SSP_INIT", "Detected Model: $detectedModel, Recommended Vendor: $recommendedVendor")

        CoreConfig.isMock = BuildConfig.IS_MOCK
        HardwareConfig.isMock = BuildConfig.IS_MOCK
        // Must run before anything touches SupabaseClientProvider (DeviceRepository.init /
        // RetailRepository.init below) -- its `client` is a `val` built eagerly from
        // SupabaseConfig.URL/KEY on first access, which read these CoreConfig fields.
        CoreConfig.supabaseUrl = BuildConfig.SUPABASE_URL
        CoreConfig.supabaseKey = BuildConfig.SUPABASE_KEY

        // Every vendor is registered into both factories up front (not just PAX)
        // so any screen defaulting to HardwareFactory/PaymentProviderFactory's
        // "IDTECH" default, or explicitly asking for WIZARPOS, doesn't hit the
        // "No {hardware,payment} provider registered for vendor" throw at first
        // real use. init() runs before getPaymentProvider() on purpose: Pax's
        // getPaymentProvider() needs its context set by init() first, and
        // WizarPos's payment provider captures `terminal` by value the first
        // time getPaymentProvider() is called, which is null until init() runs.
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

                if (!isUnlocked) {
                    StaffPinLockScreen(onUnlock = { if (it == "1234") isUnlocked = true })
                } else {
                    LaunchedEffect(Unit) {
                        startScannerLoop(retailViewModel)
                    }

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
                                        onCheckout = { subtotal ->
                                            val taxRate = DeviceRepository.getTaxRate()
                                            val tax = (subtotal * taxRate / 100.0).toInt()
                                            activeSaleData = subtotal to tax
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
                                    triggerPaymentFlow(subtotal, tax, tip) {
                                        paymentStatus = it
                                        if (it is PaymentUiState.Success) {
                                            retailViewModel.clearCart()
                                            FeedbackManager.success(this@MainActivity)
                                        }
                                    }
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

    private fun startScannerLoop(viewModel: RetailViewModel) {
        scannerProvider.startScan(object : IScannerProvider.ScanCallback {
            override fun onScanSuccess(barcode: String) {
                viewModel.addByBarcode(this@MainActivity, barcode)
                startScannerLoop(viewModel)
            }
            override fun onScanFailure(error: String) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(2000)
                    startScannerLoop(viewModel)
                }
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
fun CheckoutContainer(retailViewModel: RetailViewModel, onCheckout: (Int) -> Unit) {
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
                    if (cents > 0) onCheckout(cents)
                })
            } else {
                when (retailMode) {
                    com.goldsky.ssp.model.RetailMode.RESTAURANT -> DineInScreen(onTableSelected = { onCheckout(5000) })
                    com.goldsky.ssp.model.RetailMode.DELIVERY -> DeliveryScreen(onCollect = { onCheckout(it) })
                    else -> RetailStoreScreen(onCheckout = onCheckout, viewModel = retailViewModel)
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
