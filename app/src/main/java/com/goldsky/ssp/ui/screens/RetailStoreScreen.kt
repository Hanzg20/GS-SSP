package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.payment.RetailRepository

/**
 * Modern Retail Store screen with Obsidian & Gold glass-morphism cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetailStoreScreen(onCheckout: (Int) -> Unit) {
    val catalog by RetailRepository.catalog.collectAsState()
    val cart = remember { mutableStateMapOf<String, Int>() } 
    
    val totalCents = catalog.sumOf { (cart[it.id] ?: 0) * it.price_cents }
    val itemCount = cart.values.sum()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("LUXURY RETAIL", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp) },
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
                            Text("TOTAL AMOUNT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f))
                            Text(
                                text = "$${"%.2f".format(totalCents / 100.0)}",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
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
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(catalog) { product ->
                ProductItemCard(
                    product = product,
                    quantity = cart[product.id] ?: 0,
                    onAdd = { cart[product.id] = (cart[product.id] ?: 0) + 1 }
                )
            }
        }
    }
}

@Composable
fun ProductItemCard(product: Product, quantity: Int, onAdd: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                    Text(
                        "QTY: $quantity", 
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                
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
