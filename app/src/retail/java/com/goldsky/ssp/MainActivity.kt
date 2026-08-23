package com.goldsky.ssp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.RetailRepository
import com.goldsky.ssp.payment.TtsManager
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import com.goldsky.ssp.ui.components.TipSelectionDialog
import com.goldsky.ssp.ui.screens.*
import com.goldsky.ssp.ui.theme.RetailTheme

class MainActivity : ComponentActivity() {

    private val paymentProvider by lazy { 
        HardwareFactory.getPaymentProvider(this, "WIZARPOS") 
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        DeviceRepository.init(this)
        RetailRepository.init(this)
        TtsManager.registerLifecycle(this, this)
        
        setContent {
            RetailTheme {
                val navController = rememberNavController()
                var paymentStatus by remember { mutableStateOf<PaymentUiState>(PaymentUiState.Idle) }
                var showTipDialog by remember { mutableStateOf<Int?>(null) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        bottomBar = {
                            MainBottomNavigation(navController)
                        }
                    ) { padding ->
                        NavHost(
                            navController = navController,
                            startDestination = "checkout",
                            modifier = Modifier.padding(padding)
                        ) {
                            composable("checkout") {
                                CheckoutScreen(onCheckout = { amount -> showTipDialog = amount })
                            }
                            composable("transactions") {
                                TransactionHistoryScreen()
                            }
                            composable("management") {
                                ManagementScreen()
                            }
                            composable("settings") {
                                SettingsScreen()
                            }
                        }
                    }

                    // Tip Dialog Overlay
                    showTipDialog?.let { subtotal ->
                        TipSelectionDialog(
                            subtotalCents = subtotal,
                            onConfirm = { total ->
                                showTipDialog = null
                                triggerPaymentFlow(total) { paymentStatus = it }
                            },
                            onDismiss = { showTipDialog = null }
                        )
                    }

                    // Payment Overlays
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

    private fun triggerPaymentFlow(cents: Int, onStateChange: (PaymentUiState) -> Unit) {
        val ref = "RET-" + System.currentTimeMillis()
        onStateChange(PaymentUiState.Processing("Processing $${"%.2f".format(cents/100.0)}..."))
        paymentProvider.startSale(cents, ref, object : IPaymentProvider.PaymentCallback {
            override fun onSuccess(authCode: String, refNum: String, entryMode: String) {
                onStateChange(PaymentUiState.Success(authCode))
            }
            override fun onFailure(errorMsg: String, isHardwareFault: Boolean) {
                onStateChange(PaymentUiState.Error(errorMsg))
            }
            override fun onProgress(message: String) {
                onStateChange(PaymentUiState.Processing(message))
            }
        })
    }
}

@Composable
fun MainBottomNavigation(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == "checkout",
            onClick = { 
                navController.navigate("checkout") {
                    popUpTo(navController.graph.startDestinationId)
                    launchSingleTop = true
                }
            },
            icon = { Icon(Icons.Default.PointOfSale, null) },
            label = { Text("Quick Pay") }
        )
        NavigationBarItem(
            selected = currentRoute == "transactions",
            onClick = { 
                navController.navigate("transactions") {
                    launchSingleTop = true
                }
            },
            icon = { Icon(Icons.Default.ReceiptLong, null) },
            label = { Text("Records") }
        )
        NavigationBarItem(
            selected = currentRoute == "management",
            onClick = { 
                navController.navigate("management") {
                    launchSingleTop = true
                }
            },
            icon = { Icon(Icons.Default.Inventory, null) },
            label = { Text("Manage") }
        )
        NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = { 
                navController.navigate("settings") {
                    launchSingleTop = true
                }
            },
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
