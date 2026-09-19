package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CheckoutScreen(viewModel: RetailViewModel = viewModel()) {
    val cart = viewModel.cart
    val total by viewModel.totalCents.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Checkout", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        cart.forEach { item ->
            Text("${item.product.name} x${item.quantity}")
        }
        
        Spacer(modifier = Modifier.weight(1f))
        Text("Total: $${total / 100.0}")
        Button(onClick = { /* Handle payment */ }, modifier = Modifier.fillMaxWidth()) {
            Text("PAY NOW")
        }
    }
}
