package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.payment.TransactionRepository
import com.goldsky.ssp.feature.retail.ReceiptPrinterManager
import com.goldsky.ssp.feature.retail.ui.ReceiptDetailDialog

@Composable
fun TransactionHistoryScreen() {
    val context = LocalContext.current
    var transactions by remember { mutableStateOf(emptyList<com.goldsky.ssp.db.OrderEntity>()) }
    
    LaunchedEffect(Unit) {
        transactions = TransactionRepository.getAllLocal(context)
    }

    LazyColumn {
        items(transactions) { tx ->
            ListItem(
                headlineContent = { Text("Ref: ${tx.ecrRefNum}") },
                supportingContent = { Text("Amount: $${tx.amountCents / 100.0}") },
                leadingContent = { Icon(Icons.Default.Receipt, null) }
            )
        }
    }
}
