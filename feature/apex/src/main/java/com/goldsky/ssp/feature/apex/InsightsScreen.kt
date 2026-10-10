package com.goldsky.ssp.feature.apex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.TransactionRepository

val GoldSkyBlue = Color(0xFF007AFF)
val GoldSkyCyan = Color(0xFF00E5FF)

@Composable
fun InsightsScreen(titleColor: Color = GoldSkyBlue) {
    val context = LocalContext.current
    var revenueCents by remember { mutableStateOf(0) }
    var transactionCount by remember { mutableStateOf(0) }
    val isCyber = DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER

    LaunchedEffect(Unit) {
        val orders = TransactionRepository.getAllLocal(context)
        val paid = orders.filter { it.status == "PAID" }
        revenueCents = paid.sumOf { it.amountCents }
        transactionCount = paid.size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isCyber) Color(0xFF0B0F19) else MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = if (isCyber) GoldSkyCyan else titleColor, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "APEX AI INSIGHTS",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (isCyber) GoldSkyCyan else titleColor
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = if (isCyber) Color(0xFF162032) else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, if (isCyber) GoldSkyCyan.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TrendingUp, null, tint = if (isCyber) GoldSkyCyan else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("REVENUE SUMMARY", style = MaterialTheme.typography.labelSmall, color = if (isCyber) GoldSkyCyan else MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Total Revenue: $${"%.2f".format(revenueCents / 100.0)}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCyber) Color.White else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Paid Transactions: $transactionCount",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCyber) Color.LightGray else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = if (isCyber) Color(0xFF151D30) else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "AI RECOMMENDATIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCyber) GoldSkyCyan else MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "• Peak hours detected between 11:30 AM and 1:30 PM.\n• Top seller: Espresso Coffee (+24% volume).\n• Recommended action: Enable quick-select preset buttons.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCyber) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

