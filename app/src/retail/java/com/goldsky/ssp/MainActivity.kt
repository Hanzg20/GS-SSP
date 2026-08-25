package com.goldsky.ssp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.goldsky.ssp.db.LocalDatabase
import com.goldsky.ssp.payment.*
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import com.goldsky.ssp.payment.hardware.IScannerProvider
import com.goldsky.ssp.ui.components.TipSelectionDialog
import com.goldsky.ssp.ui.screens.*
import com.goldsky.ssp.ui.theme.GoldSkyBlue
import com.goldsky.ssp.ui.theme.RetailTheme
import com.goldsky.ssp.viewmodel.RetailViewModel
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private val paymentProvider by lazy { 
        HardwareFactory.getPaymentProvider(this, "WIZARPOS") 
    }

    private val scannerProvider by lazy {
        HardwareFactory.getScannerProvider(this, "WIZARPOS")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        DeviceRepository.init(this)
        RetailRepository.init(this)
        TtsManager.registerLifecycle(this, this)
        FeedbackManager.init(this)
        
        setContent {
            RetailTheme {
                val navController = rememberNavController()
                val retailViewModel: RetailViewModel = viewModel()
                var paymentStatus by remember { mutableStateOf<PaymentUiState>(PaymentUiState.Idle) }
                var activeSaleData by remember { mutableStateOf<Pair<Int, Int>?>(null) }
                
                var isUnlocked by remember { mutableStateOf(false) }
                var showSplash by remember { mutableStateOf(true) }

                if (showSplash) {
                    SplashScreen { showSplash = false }
                } else if (!isUnlocked) {
                    StaffPinLockScreen(onUnlock = { pin ->
                        if (pin == "1234") {
                            isUnlocked = true
                        }
                    })
                } else {
                    LaunchedEffect(Unit) {
                        startScannerLoop(retailViewModel)
                        retailViewModel.loadParkedOrders(this@MainActivity)
                    }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Scaffold(
                            bottomBar = { MainBottomNavigation(navController) }
                        ) { padding ->
                            NavHost(
                            navController = navController,
                            startDestination = "checkout",
                            modifier = Modifier.padding(padding)
                        ) {
                            composable("checkout") {
                                CheckoutScreen(
                                    onCheckout = { subtotal -> 
                                        val taxRate = DeviceRepository.getTaxRate()
                                        val tax = (subtotal * taxRate / 100.0).toInt()
                                        activeSaleData = subtotal to tax 
                                    },
                                    retailViewModel = retailViewModel
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
                                                com.goldsky.ssp.db.ExpenseEntity(vendor = vendor, amountCents = amount, category = "SUPPLY")
                                            )
                                            withContext(Dispatchers.Main) {
                                                navController.popBackStack()
                                            }
                                        }
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("settings") { SettingsScreen() }
                        }
                        }

                        // Detail Overlays
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
                                            FeedbackManager.emitPaymentSuccessFeedback(this@MainActivity)
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

        onStateChange(PaymentUiState.Processing("Processing $${"%.2f".format(total/100.0)}..."))
        
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

@Composable
fun SplashScreen(onFinish: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2000)
        onFinish()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.goldsky_logo),
                contentDescription = "GoldSky Logo",
                modifier = Modifier.size(180.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
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
                horizontalAlignment = Alignment.CenterHorizontally
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
