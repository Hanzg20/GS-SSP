package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.db.OrderEntity
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.ReceiptPrinterManager
import com.goldsky.ssp.payment.TransactionRepository
import com.goldsky.ssp.ui.components.ReceiptDetailDialog
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryScreen() {
    val context = LocalContext.current
    var orders by remember { mutableStateOf<List<OrderEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedOrder by remember { mutableStateOf<OrderEntity?>(null) }

    LaunchedEffect(Unit) {
        orders = TransactionRepository.getAllLocal(context)
        isLoading = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("TRANSACTIONS", fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (orders.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Receipt, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(16.dp))
                Text("No transactions yet today.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(orders) { order ->
                    OrderHistoryCard(order, onClick = { selectedOrder = order })
                }
            }
        }
    }

    // Detail Modal
    selectedOrder?.let { order ->
        ReceiptDetailDialog(
            order = order,
            onReprint = {
                ReceiptPrinterManager.printReceipt(
                    context,
                    ReceiptPrinterManager.ReceiptData(
                        subtotalCents = order.subtotalCents,
                        taxCents = order.taxCents,
                        tipCents = order.tipCents,
                        amountCents = order.amountCents,
                        refNum = order.ecrRefNum,
                        deviceSn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN"
                    ),
                    vendor = DeviceRepository.getPersistedHardwareVendor()
                )
            },
            onDismiss = { selectedOrder = null }
        )
    }
}

@Composable
fun OrderHistoryCard(order: OrderEntity, onClick: () -> Unit) {
    val dateStr = remember(order.createdAt) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(order.createdAt))
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = order.ecrRefNum.take(12) + "...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "$${"%.2f".format(order.amountCents / 100.0)}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (order.taxCents > 0 || order.tipCents > 0) {
                    Text(
                        text = "Sub: $${"%.2f".format(order.subtotalCents / 100.0)} • Tax: $${"%.2f".format(order.taxCents / 100.0)} • Tip: $${"%.2f".format(order.tipCents / 100.0)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
                Text(
                    text = "$dateStr • ${order.paymentMethod}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            StatusBadge(order.status)
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val color = when (status) {
        "PAID" -> Color(0xFF4CAF50)
        "FAILED" -> Color(0xFFF44336)
        else -> MaterialTheme.colorScheme.primary
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color)
    ) {
        Text(
            text = status,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            color = color
        )
    }
}
