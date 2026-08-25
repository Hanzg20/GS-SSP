package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.goldsky.ssp.R
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.RetailRepository
import com.goldsky.ssp.ui.components.ModifierSelectionSheet
import com.goldsky.ssp.viewmodel.RetailViewModel

/**
 * Modern Retail Store screen with Obsidian & Gold glass-morphism cards.
 * Integrated with RetailViewModel for global cart & scanner support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetailStoreScreen(
    onCheckout: (Int) -> Unit,
    viewModel: RetailViewModel = viewModel()
) {
    val catalog by RetailRepository.catalog.collectAsState()
    val totalCents by viewModel.totalCents.collectAsState()
    val itemCount by viewModel.itemCount.collectAsState()
    val lastScanned by viewModel.lastScannedProductName.collectAsState()
    val parkedOrders by viewModel.parkedOrders.collectAsState()
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val cart = viewModel.cart
    val currency = remember { DeviceRepository.getCurrencySymbol() }

    var productForModifiers by remember { mutableStateOf<Product?>(null) }
    var showParkedOrders by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LUXURY RETAIL", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                        lastScanned?.let { 
                            Text("SCAN: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) 
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showParkedOrders = true }) {
                        BadgedBox(
                            badge = { if (parkedOrders.isNotEmpty()) Badge { Text("${parkedOrders.size}") } }
                        ) {
                            Icon(Icons.Default.ListAlt, contentDescription = "Parked Orders", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                ),
                actions = {
                    BadgedBox(
                        badge = { if (itemCount > 0) Badge { Text("$itemCount") } }
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = "Cart", tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(20.dp))
                }
            )
        },
        bottomBar = {
            if (itemCount > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 12.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("TOTAL ($currency)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f))
                            Text(
                                text = "$${"%.2f".format(totalCents / 100.0)}",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Row {
                            TextButton(onClick = { viewModel.parkCurrentOrder(context) }) {
                                Text("PARK", color = MaterialTheme.colorScheme.onPrimary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onCheckout(totalCents) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("PAY NOW", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        // ... grid content ...
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(catalog) { product ->
                val totalQty = cart.filter { it.product.id == product.id }.sumOf { it.quantity }
                ProductItemCard(
                    product = product,
                    quantity = totalQty,
                    onAdd = { 
                        if (product.modifier_groups.isNullOrEmpty()) {
                            viewModel.addItem(product)
                        } else {
                            productForModifiers = product
                        }
                    },
                    onRemove = { 
                        cart.find { it.product.id == product.id }?.let {
                            viewModel.removeItem(it.id)
                        }
                    }
                )
            }
        }
    }

    // Modifier Sheet
    productForModifiers?.let { product ->
        ModifierSelectionSheet(
            product = product,
            onConfirm = { modifiers ->
                viewModel.addItem(product, modifiers)
                productForModifiers = null
            },
            onDismiss = { productForModifiers = null }
        )
    }

    if (showParkedOrders) {
        AlertDialog(
            onDismissRequest = { showParkedOrders = false },
            title = { Text("PARKED ORDERS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (parkedOrders.isEmpty()) {
                        Text("No parked orders found.", color = MaterialTheme.colorScheme.outline)
                    }
                    parkedOrders.forEach { parked ->
                        ListItem(
                            headlineContent = { Text(parked.tableName ?: "Quick Order") },
                            supportingContent = { Text("$${"%.2f".format(parked.subtotalCents / 100.0)}") },
                            trailingContent = {
                                Button(onClick = {
                                    viewModel.resumeOrder(context, parked)
                                    showParkedOrders = false
                                }) { Text("RESUME") }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showParkedOrders = false }) { Text("CLOSE") }
            }
        )
    }
}

@Composable
fun ProductItemCard(product: Product, quantity: Int, onAdd: () -> Unit, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (product.image_url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(product.image_url)
                        .crossfade(true)
                        .build(),
                    placeholder = painterResource(R.drawable.ad_placeholder),
                    contentDescription = product.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text(
                text = product.name.uppercase(),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$${"%.2f".format(product.price_cents / 100.0)}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (quantity > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Remove, contentDescription = "Remove", tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            "$quantity", 
                            modifier = Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = onAdd, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                    FilledIconButton(
                        onClick = onAdd,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add, 
                            contentDescription = "Add", 
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
