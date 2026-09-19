package com.goldsky.ssp.feature.apex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.payment.TransactionRepository

val GoldSkyBlue = Color(0xFF007AFF)

/**
 * Revenue/transaction count computed from the same local order history
 * TransactionHistoryScreen reads (TransactionRepository.getAllLocal) --
 * previously this screen showed two numbers hardcoded in source
 * ("Revenue: $1,250.00", "Transactions: 42"), unrelated to any real activity
 * on the device.
 */
@Composable
fun InsightsScreen(titleColor: Color = GoldSkyBlue) {
    val context = LocalContext.current
    var revenueCents by remember { mutableStateOf(0) }
    var transactionCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val orders = TransactionRepository.getAllLocal(context)
        val paid = orders.filter { it.status == "PAID" }
        revenueCents = paid.sumOf { it.amountCents }
        transactionCount = paid.size
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Insights", style = MaterialTheme.typography.headlineMedium, color = titleColor)
        Spacer(modifier = Modifier.height(16.dp))
        // Explicit onBackground color -- these two lines previously had none,
        // which meant relying on whatever LocalContentColor happened to be
        // inherited from the caller's Surface (or lack thereof). Fine against
        // app/iris's plain white background; unreadable (near-black-on-near-
        // black) against app/ourea's dark theme, found while restyling Ourea.
        val textColor = MaterialTheme.colorScheme.onBackground
        Text("Revenue: $${"%.2f".format(revenueCents / 100.0)}", color = textColor)
        Text("Transactions: $transactionCount", color = textColor)
    }
}
