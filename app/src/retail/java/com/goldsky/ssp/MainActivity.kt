package com.goldsky.ssp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.RetailRepository
import com.goldsky.ssp.payment.TtsManager
import com.goldsky.ssp.payment.hardware.HardwareFactory
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import com.goldsky.ssp.ui.components.NumericKeypad
import com.goldsky.ssp.ui.components.TipSelectionDialog
import com.goldsky.ssp.ui.screens.DeliveryScreen
import com.goldsky.ssp.ui.screens.DineInScreen
import com.goldsky.ssp.ui.screens.RetailStoreScreen
import com.goldsky.ssp.ui.theme.RetailTheme

/**
 * Entry point for Retail / Attended POS product flavor.
 * Full Compose-based UI for WizarPOS Q2.
 */
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
                var currentMode by remember { mutableStateOf(RetailMode.STORE) }
                var paymentStatus by remember { mutableStateOf<PaymentUiState>(PaymentUiState.Idle) }
                var showTipDialog by remember { mutableStateOf<Int?>(null) } // subtotal

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = currentMode == RetailMode.STORE,
                                    onClick = { currentMode = RetailMode.STORE },
                                    icon = { Icon(Icons.Default.Storefront, null) },
                                    label = { Text("Retail") }
                                )
                                NavigationBarItem(
                                    selected = currentMode == RetailMode.RESTAURANT,
                                    onClick = { currentMode = RetailMode.RESTAURANT },
                                    icon = { Icon(Icons.Default.Restaurant, null) },
                                    label = { Text("Dining") }
                                )
                                NavigationBarItem(
                                    selected = currentMode == RetailMode.DELIVERY,
                                    onClick = { currentMode = RetailMode.DELIVERY },
                                    icon = { Icon(Icons.Default.DeliveryDining, null) },
                                    label = { Text("Delivery") }
                                )
                                NavigationBarItem(
                                    selected = currentMode == RetailMode.QUICK_PAY,
                                    onClick = { currentMode = RetailMode.QUICK_PAY },
                                    icon = { Icon(Icons.Default.PointOfSale, null) },
                                    label = { Text("Quick") }
                                )
                            }
                        }
                    ) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            when (currentMode) {
                                RetailMode.STORE -> {
                                    RetailStoreScreen(
                                        onCheckout = { total ->
                                            triggerPaymentFlow(total) { paymentStatus = it }
                                        }
                                    )
                                }
                                RetailMode.RESTAURANT -> {
                                    DineInScreen(
                                        onTableSelected = { table ->
                                            if (table.isOccupied) {
                                                // Assume fixed charge for prototype
                                                showTipDialog = 5000 
                                            } else {
                                                Toast.makeText(this@MainActivity, "Table is empty", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                                RetailMode.DELIVERY -> {
                                    DeliveryScreen(
                                        onCollect = { amount ->
                                            showTipDialog = amount
                                        }
                                    )
                                }
                                RetailMode.QUICK_PAY -> {
                                    ManualAmountScreen(
                                        onInitiatePayment = { amountStr ->
                                            val cents = amountStr.replace(".", "").toIntOrNull() ?: 0
                                            if (cents > 0) {
                                                triggerPaymentFlow(cents) { paymentStatus = it }
                                            }
                                        }
                                    )
                                }
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

enum class RetailMode {
    STORE, RESTAURANT, DELIVERY, QUICK_PAY
}

sealed class PaymentUiState {
    object Idle : PaymentUiState()
    data class Processing(val message: String) : PaymentUiState()
    data class Success(val authCode: String) : PaymentUiState()
    data class Error(val message: String) : PaymentUiState()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAmountScreen(onInitiatePayment: (String) -> Unit) {
    var amount by remember { mutableStateOf("0.00") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Amount Display
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "ENTER AMOUNT",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "$$amount",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Numeric Keypad
        NumericKeypad(
            onNumberClick = { num ->
                val raw = amount.replace(".", "").replace(Regex("^0+"), "") + num
                val padded = raw.padStart(3, '0')
                amount = "${padded.dropLast(2)}.${padded.takeLast(2)}"
            },
            onBackSpace = {
                val raw = amount.replace(".", "").dropLast(1)
                val padded = raw.padStart(3, '0')
                amount = "${padded.dropLast(2)}.${padded.takeLast(2)}"
            },
            onConfirm = {
                onInitiatePayment(amount)
            }
        )
    }
}
