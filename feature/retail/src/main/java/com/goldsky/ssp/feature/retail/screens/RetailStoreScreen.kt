package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.feature.retail.ui.ModifierSelectionSheet
import com.goldsky.ssp.feature.retail.RetailRepository
import kotlinx.coroutines.delay

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
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(catalog) { product ->
                // Summed across modifier variants of the same product.
                val qty = cart.filter { it.product.id == product.id }.sumOf { it.quantity }
                ListItem(
                    headlineContent = { Text(product.name) },
                    supportingContent = { Text("$%.2f".format(product.price_cents / 100.0)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (qty > 0) {
                                IconButton(onClick = {
                                    cart.lastOrNull { it.product.id == product.id }?.let { viewModel.removeItem(it.id) }
                                }) {
                                    Icon(Icons.Default.Remove, contentDescription = "Remove one")
                                }
                                Text("×$qty", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { viewModel.addItem(product) }) {
                                Icon(Icons.Default.Add, contentDescription = "Add one")
                            }
                        }
                    }
                )
            }
        }

        visibleScan?.let { scan ->
            val found = scan.productName != null
            Surface(
                color = if (found) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (found) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (found) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (found) "Added: ${scan.productName}" else "Unknown barcode", fontWeight = FontWeight.Bold)
                        Text(scan.barcode, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        BottomAppBar {
            if (onScan != null) {
                IconButton(onClick = onScan) {
                    if (isScanning) Icon(Icons.Default.Close, contentDescription = "Stop scanning", tint = MaterialTheme.colorScheme.error)
                    else Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan")
                }
            }
            Text("Total: $%.2f".format(total / 100.0), modifier = Modifier.padding(16.dp))
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { if (total > 0) onCheckout(total) }, enabled = total > 0) {
                Text("CHECKOUT")
            }
        }
    }
}
