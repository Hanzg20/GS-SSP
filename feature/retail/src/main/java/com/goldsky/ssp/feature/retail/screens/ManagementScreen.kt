package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.background
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
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

    LaunchedEffect(Unit) {
        val orders = TransactionRepository.getAllLocal(context).filter { it.status == "PAID" }
        totalSales = orders.sumOf { it.subtotalCents }
        totalTax = orders.sumOf { it.taxCents }
        totalTips = orders.sumOf { it.tipCents }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.retail_management), fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
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
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.retail_today_s_overview), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    AnalyticsRow(stringResource(R.string.retail_gross_sales), totalSales + totalTax + totalTips)
                    AnalyticsRow(stringResource(R.string.retail_net_sales), totalSales)
                    AnalyticsRow(stringResource(R.string.retail_total_tax), totalTax)
                    AnalyticsRow(stringResource(R.string.retail_total_tips), totalTips)
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.retail_data_synchronization),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(stringResource(R.string.retail_last_synced_just_now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    colors = ButtonDefaults.buttonColors()
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Sync, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.retail_sync_cloud_catalog), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsRow(label: String, cents: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$${"%.2f".format(cents / 100.0)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

