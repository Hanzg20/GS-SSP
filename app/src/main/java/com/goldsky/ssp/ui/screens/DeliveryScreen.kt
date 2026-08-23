package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
            DeliveryOrder("ORD-2209", "123 RIDEAU ST", 2550),
            DeliveryOrder("ORD-2210", "456 BANK ST", 1200),
            DeliveryOrder("ORD-2215", "789 ELGIN ST", 4500)
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("DELIVERY DASHBOARD", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "ASSIGNED JOBS",
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    letterSpacing = 2.sp
                )
            }
            items(orders) { order ->
                OrderCard(order = order, onCollect = { onCollect(order.amountCents) })
            }
        }
    }
}

@Composable
fun OrderCard(order: DeliveryOrder, onCollect: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.LocalShipping, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    order.id, 
                    fontWeight = FontWeight.Bold, 
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    order.address, 
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "$${"%.2f".format(order.amountCents / 100.0)}",
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 22.sp
                )
            }
            
            Button(
                onClick = onCollect,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("COLLECT", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
