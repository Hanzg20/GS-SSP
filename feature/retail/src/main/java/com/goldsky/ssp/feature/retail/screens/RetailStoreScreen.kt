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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.feature.retail.ui.ModifierSelectionSheet
import com.goldsky.ssp.feature.retail.RetailRepository
import kotlinx.coroutines.delay

import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.feature.retail.ui.apexPulseGlow
import com.goldsky.ssp.feature.retail.ui.GoldSkyBlue
import com.goldsky.ssp.feature.retail.ui.GoldSkyCyan
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
    
    // Dynamically re-check theme style so settings changes reflect instantly
    val themeStyle = DeviceRepository.getThemeStyle()

    var visibleScan by remember { mutableStateOf<RetailViewModel.ScanFeedback?>(null) }
    LaunchedEffect(lastScan) {
        visibleScan = lastScan
        if (lastScan != null) {
            delay(4000)
            visibleScan = null
        }
    }

    if (themeStyle == DeviceRepository.UiThemeStyle.CYBER) {
        // CYBER GLASSMORPHIC THEME
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F19))
        ) {
            // 1. AI Speed Suggest Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .apexPulseGlow(shape = RoundedCornerShape(12.dp), strokeWidth = 1.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF151D30)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = GoldSkyCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "AI SPEED SUGGEST",
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.labelSmall,
                            color = GoldSkyCyan
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        catalog.take(3).forEach { p ->
                            Surface(
                                modifier = Modifier
                                    .clickable { viewModel.addItem(p) },
                                shape = CircleShape,
                                color = GoldSkyBlue.copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GoldSkyCyan.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "+ ${p.name}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Product Catalog List (Cyber Cards)
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(catalog) { product ->
                    val qty = cart.filter { it.product.id == product.id }.sumOf { it.quantity }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF162032),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF26354F))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(product.name, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("$%.2f".format(product.price_cents / 100.0), color = GoldSkyCyan, fontWeight = FontWeight.SemiBold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (qty > 0) {
                                    IconButton(onClick = {
                                        cart.lastOrNull { it.product.id == product.id }?.let { viewModel.removeItem(it.id) }
                                    }) {
                                        Icon(Icons.Default.Remove, "Remove", tint = Color.LightGray)
                                    }
                                    Text("×$qty", fontWeight = FontWeight.Bold, color = GoldSkyCyan)
                                }
                                IconButton(onClick = { viewModel.addItem(product) }) {
                                    Icon(Icons.Default.Add, "Add", tint = GoldSkyBlue)
                                }
                            }
                        }
                    }
                }
            }

            // 3. Cyber Action Footer Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF0F172A),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (onScan != null) {
                        IconButton(onClick = onScan) {
                            Icon(
                                if (isScanning) Icons.Default.Close else Icons.Default.QrCodeScanner,
                                contentDescription = "Scan",
                                tint = if (isScanning) Color.Red else GoldSkyCyan
                            )
                        }
                    }
                    Text("Total: $%.2f".format(total / 100.0), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { if (total > 0) onCheckout(total) },
                        enabled = total > 0,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoldSkyBlue)
                    ) {
                        Text("PAY $%.2f →".format(total / 100.0), fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    } else {
        // CLASSIC MINIMALIST THEME
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(catalog) { product ->
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
}
