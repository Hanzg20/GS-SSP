package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.feature.retail.ui.ModifierSelectionSheet
import com.goldsky.ssp.feature.retail.RetailRepository

@Composable
fun RetailStoreScreen(onCheckout: (Int) -> Unit = {}, viewModel: RetailViewModel = viewModel()) {
    val catalog by RetailRepository.catalog.collectAsState()
    val cart = viewModel.cart
    val total by viewModel.totalCents.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(catalog) { product ->
                ListItem(
                    headlineContent = { Text(product.name) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.addItem(product) }) {
                            Icon(Icons.Default.Add, null)
                        }
                    }
                )
            }
        }
        
        BottomAppBar {
            Text("Total: $${total / 100.0}", modifier = Modifier.padding(16.dp))
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { if (total > 0) onCheckout(total) }, enabled = total > 0) {
                Text("CHECKOUT")
            }
        }
    }
}
