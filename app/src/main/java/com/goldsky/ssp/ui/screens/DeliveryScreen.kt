package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DeliveryOrder(val id: String, val address: String, val amountCents: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryScreen(onCollect: (Int) -> Unit) {
    val orders = remember {
        mutableStateListOf(
            DeliveryOrder("ORD-101", "123 Main St, Ottawa", 2550),
            DeliveryOrder("ORD-102", "456 Elgin St, Ottawa", 1200),
            DeliveryOrder("ORD-105", "789 Baseline Rd, Ottawa", 4500)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("DELIVERY JOBS", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(orders) { order ->
                OrderCard(order = order, onCollect = { onCollect(order.amountCents) })
            }
        }
    }
}

@Composable
fun OrderCard(order: DeliveryOrder, onCollect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(order.id, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(order.address, style = MaterialTheme.typography.bodySmall)
                Text(
                    "$${"%.2f".format(order.amountCents / 100.0)}",
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 20.sp
                )
            }
            Button(onClick = onCollect) {
                Icon(Icons.Default.Payment, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("COLLECT")
            }
        }
    }
}
