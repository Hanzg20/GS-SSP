package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldsky.ssp.feature.retail.RetailRepository
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.ui.theme.GsTheme
import kotlinx.coroutines.delay

private fun money(cents: Int) = "$%.2f".format(cents / 100.0)

/**
 * LIBRARY tab: the catalog with per-item quantity, the last scan's result,
 * and the scan / total / checkout bar. One layout for both themes -- colours
 * come from GoldSkyTheme (it used to be two different layouts, and the dark
 * one had lost the scan-result banner).
 */
@Composable
fun RetailStoreScreen(
    onCheckout: (Int) -> Unit = {},
    viewModel: RetailViewModel = viewModel(),
    // Starts a scan run, or stops it while [isScanning]. Null hides the button.
    onScan: (() -> Unit)? = null,
    isScanning: Boolean = false
) {
    val catalog by RetailRepository.catalog.collectAsState()
    val cart = viewModel.cart
    val total by viewModel.totalCents.collectAsState()
    val lastScan by viewModel.lastScan.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val gs = GsTheme.colors

    // Show each scan's result for a few seconds; a new scan (new seq) restarts it.
    var visibleScan by remember { mutableStateOf<RetailViewModel.ScanFeedback?>(null) }
    LaunchedEffect(lastScan) {
        visibleScan = lastScan
        if (lastScan != null) {
            delay(4000)
            visibleScan = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(catalog) { product ->
                // Summed across modifier variants of the same product.
                val qty = cart.filter { it.product.id == product.id }.sumOf { it.quantity }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceVariant,
                    border = BorderStroke(if (qty > 0) 1.dp else 0.5.dp, if (qty > 0) scheme.primary else scheme.outline)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(product.name, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                            Text(money(product.price_cents), color = gs.emphasis, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (qty > 0) {
                            IconButton(onClick = {
                                cart.lastOrNull { it.product.id == product.id }?.let { viewModel.removeItem(it.id) }
                            }) {
                                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.retail_remove_one), tint = scheme.onSurfaceVariant)
                            }
                            Text("×$qty", fontWeight = FontWeight.Bold, color = scheme.primary)
                        }
                        IconButton(onClick = { viewModel.addItem(product) }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.retail_add_one), tint = scheme.primary)
                        }
                    }
                }
            }
        }

        visibleScan?.let { scan ->
            val found = scan.productName != null
            Surface(
                color = if (found) scheme.primaryContainer else scheme.errorContainer,
                contentColor = if (found) scheme.onPrimaryContainer else scheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (found) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (found) gs.success else gs.danger
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (found) stringResource(R.string.retail_scan_added, scan.productName ?: "") else stringResource(R.string.retail_unknown_barcode), fontWeight = FontWeight.Bold)
                        Text(scan.barcode, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Surface(color = scheme.surface, shadowElevation = 6.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onScan != null) {
                    IconButton(onClick = onScan) {
                        if (isScanning) Icon(Icons.Default.Close, contentDescription = stringResource(R.string.retail_stop_scanning), tint = gs.danger)
                        else Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.retail_scan), tint = scheme.primary)
                    }
                }
                Text("Total: ${money(total)}", color = scheme.onSurface, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { if (total > 0) onCheckout(total) }, enabled = total > 0, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.retail_checkout), fontWeight = FontWeight.Black)
                }
            }
        }
    }
}
