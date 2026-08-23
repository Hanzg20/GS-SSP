package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.payment.RetailRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetailStoreScreen(onCheckout: (Int) -> Unit) {
    val catalog by RetailRepository.catalog.collectAsState()
    val cart = remember { mutableStateMapOf<String, Int>() } // productId -> quantity
    
    val totalCents = catalog.sumOf { (cart[it.id] ?: 0) * it.price_cents }
    val itemCount = cart.values.sum()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MODERN RETAIL", fontWeight = FontWeight.Black) },
                actions = {
                    BadgedBox(
                        badge = { if (itemCount > 0) Badge { Text("$itemCount") } }
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = "Cart")
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
            )
        },
        bottomBar = {
            if (itemCount > 0) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TOTAL: $${"%.2f".format(totalCents / 100.0)}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Button(onClick = { onCheckout(totalCents) }) {
                            Text("CHECKOUT")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = padding,
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = product.name,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "$${"%.2f".format(product.price_cents / 100.0)}",
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (quantity > 0) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("x$quantity") }
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }
        }
    }
}
