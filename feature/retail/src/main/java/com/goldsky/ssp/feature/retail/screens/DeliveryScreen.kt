package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.ui.GoldSkyCyan
import com.goldsky.ssp.payment.DeliveryOrder
import com.goldsky.ssp.payment.DeliveryOrderRepository
import com.goldsky.ssp.payment.DeviceRepository

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                orders?.let { androidx.compose.ui.res.pluralStringResource(R.plurals.retail_open_orders, it.size, it.size) } ?: stringResource(R.string.retail_delivery_orders),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { refreshTick++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.retail_refresh), tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (failed) {
            Text(stringResource(R.string.retail_can_t_reach_the_server) + if (orders == null) " (none yet)" else "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        val list = orders.orEmpty()
        if (!loading && list.isEmpty() && !failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.retail_no_open_delivery_orders), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            title = { Text(stringResource(R.string.retail_cash_received)) },
            text = { Text(stringResource(R.string.retail_collect_cash, order.order_no.toString(), money(order.subtotalCents + order.taxCents), order.customer_name)) },
            confirmButton = {
                Button(
                    onClick = { cashFor = null; onCollectCash(order) },
                    colors = ButtonDefaults.buttonColors()
                ) { Text(stringResource(R.string.retail_cash_received_2), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { cashFor = null }) { Text(stringResource(R.string.retail_cancel_2)) } },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val borderCol = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        border = BorderStroke(0.5.dp, borderCol),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalShipping, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("D${order.order_no}", fontWeight = FontWeight.Black, color = textColor, modifier = Modifier.weight(1f))
                Text(money(total), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(order.customer_name, fontWeight = FontWeight.Bold, color = textColor)
            Text(order.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            order.customer_phone?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            order.note?.takeIf { it.isNotBlank() }?.let {
                Text("Note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            if (expanded) {
                Divider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
                order.lines.forEach { l ->
                    Row {
                        Text("${l.quantity} × ${l.name}" + if (l.taxRateBps > 0) "  H" else "", color = textColor, modifier = Modifier.weight(1f))
                        Text(money(l.lineTotalCents), color = textColor)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row { Text(stringResource(R.string.retail_tax), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); Text(money(order.taxCents), color = textColor) }
                Row {
                    Text(stringResource(R.string.retail_total), fontWeight = FontWeight.Bold, color = textColor, modifier = Modifier.weight(1f))
                    Text(money(total), fontWeight = FontWeight.Bold, color = textColor)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onCard,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors()
                    ) {
                        Icon(Icons.Default.CreditCard, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.retail_card), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onCash,
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Payments, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.retail_cash), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(
                    stringResource(R.string.retail_items_tap_details, order.lines.sumOf { it.quantity }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

