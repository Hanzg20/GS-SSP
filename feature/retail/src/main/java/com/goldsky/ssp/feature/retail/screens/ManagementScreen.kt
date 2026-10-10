package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.RetailRepository
import com.goldsky.ssp.feature.retail.ui.GoldSkyCyan
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.TransactionRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }
    
    var totalSales by remember { mutableStateOf(0) }
    var totalTax by remember { mutableStateOf(0) }
    var totalTips by remember { mutableStateOf(0) }
    val isCyber = DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER

    LaunchedEffect(Unit) {
        val orders = TransactionRepository.getAllLocal(context).filter { it.status == "PAID" }
        totalSales = orders.sumOf { it.subtotalCents }
        totalTax = orders.sumOf { it.taxCents }
        totalTips = orders.sumOf { it.tipCents }
    }

    Scaffold(
        containerColor = if (isCyber) Color(0xFF0B0F19) else MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("MANAGEMENT", fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = if (isCyber) Color(0xFF151D30) else MaterialTheme.colorScheme.background,
                    titleContentColor = if (isCyber) GoldSkyCyan else MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = if (isCyber) Color(0xFF162032) else MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, if (isCyber) Color(0xFF26354F) else MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("TODAY'S OVERVIEW", style = MaterialTheme.typography.labelSmall, color = if (isCyber) GoldSkyCyan else MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    AnalyticsRow("Gross Sales", totalSales + totalTax + totalTips, isCyber = isCyber)
                    AnalyticsRow("Net Sales", totalSales, isCyber = isCyber)
                    AnalyticsRow("Total Tax", totalTax, isCyber = isCyber)
                    AnalyticsRow("Total Tips", totalTips, isCyber = isCyber)
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "DATA SYNCHRONIZATION",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isCyber) Color.White else MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "Last synced: Just now",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCyber) Color.LightGray else MaterialTheme.colorScheme.secondary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = {
                        scope.launch {
                            isSyncing = true
                            RetailRepository.syncWithCloud(context)
                            isSyncing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isSyncing,
                    colors = if (isCyber) ButtonDefaults.buttonColors(containerColor = GoldSkyCyan, contentColor = Color.Black) else ButtonDefaults.buttonColors()
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = if (isCyber) Color.Black else MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Sync, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SYNC CLOUD CATALOG", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsRow(label: String, cents: Int, isCyber: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = if (isCyber) Color.LightGray else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text("$${"%.2f".format(cents / 100.0)}", fontWeight = FontWeight.Bold, color = if (isCyber) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

