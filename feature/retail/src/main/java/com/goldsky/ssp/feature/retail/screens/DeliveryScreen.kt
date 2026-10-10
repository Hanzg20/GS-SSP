package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import com.goldsky.ssp.payment.DeliveryOrder
import com.goldsky.ssp.payment.DeliveryOrderRepository

private fun money(cents: Int) = "$%.2f".format(cents / 100.0)

/**
 * Open delivery orders from CMP (this store's unassigned ones plus any
 * assigned to this terminal). Tap an order for its lines, then collect by
 * card or mark cash received. [reloadKey] changes after a collection so the
 * paid order drops off the list.
 */
@Composable
fun DeliveryScreen(
    reloadKey: Int,
    onCollectCard: (DeliveryOrder) -> Unit,
    onCollectCash: (DeliveryOrder) -> Unit
) {
    var orders by remember { mutableStateOf<List<DeliveryOrder>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var cashFor by remember { mutableStateOf<DeliveryOrder?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey, refreshTick) {
        loading = true
        val result = DeliveryOrderRepository.fetchOpen()
        failed = result == null
        if (result != null) orders = result
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                orders?.let { "${it.size} open order${if (it.size == 1) "" else "s"}" } ?: "Delivery orders",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            IconButton(onClick = { refreshTick++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
        if (failed) {
            Text(
                "Can't reach the server -- showing the last list" + if (orders == null) " (none yet)" else "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        val list = orders.orEmpty()
        if (!loading && list.isEmpty() && !failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No open delivery orders", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(list, key = { it.id }) { order ->
                OrderCard(
                    order = order,
                    expanded = expandedId == order.id,
                    onToggle = { expandedId = if (expandedId == order.id) null else order.id },
                    onCard = { onCollectCard(order) },
                    onCash = { cashFor = order }
                )
            }
        }
    }

    cashFor?.let { order ->
        AlertDialog(
            onDismissRequest = { cashFor = null },
            title = { Text("Cash received?") },
            text = { Text("Order D${order.order_no}: collect ${money(order.subtotalCents + order.taxCents)} in cash from ${order.customer_name}.") },
            confirmButton = {
                Button(onClick = { cashFor = null; onCollectCash(order) }) { Text("CASH RECEIVED") }
            },
            dismissButton = { TextButton(onClick = { cashFor = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun OrderCard(
    order: DeliveryOrder,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCard: () -> Unit,
    onCash: () -> Unit
) {
    val total = order.subtotalCents + order.taxCents
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalShipping, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("D${order.order_no}", fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text(money(total), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(order.customer_name, fontWeight = FontWeight.Bold)
            Text(order.address, style = MaterialTheme.typography.bodyMedium)
            order.customer_phone?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            order.note?.takeIf { it.isNotBlank() }?.let {
                Text("Note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }

            if (expanded) {
                Divider(Modifier.padding(vertical = 10.dp))
                order.lines.forEach { l ->
                    Row {
                        Text("${l.quantity} × ${l.name}" + if (l.taxRateBps > 0) "  H" else "", modifier = Modifier.weight(1f))
                        Text(money(l.lineTotalCents))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row { Text("Tax", modifier = Modifier.weight(1f)); Text(money(order.taxCents)) }
                Row {
                    Text("Total", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(money(total), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onCard, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.CreditCard, null); Spacer(Modifier.width(6.dp)); Text("CARD")
                    }
                    OutlinedButton(onClick = onCash, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Payments, null); Spacer(Modifier.width(6.dp)); Text("CASH")
                    }
                }
            } else {
                Text(
                    "${order.lines.sumOf { it.quantity }} item(s) · tap for details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
