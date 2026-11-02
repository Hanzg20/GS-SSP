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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.dine.parseIso
import com.goldsky.ssp.payment.DeliveryOrder
import com.goldsky.ssp.payment.DeliveryOrderRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private fun money(cents: Int) = "$%.2f".format(cents / 100.0)

private fun clock(iso: String?): String? = parseIso(iso)?.let { SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(it)) }

/**
 * Open delivery orders from CMP (this store's unassigned ones plus any
 * assigned to this terminal). Tap an order for its lines; a driver takes it out
 * (from then on only this terminal lists it), collects by card or cash at the
 * door -- which marks it delivered -- or brings it back with the reason.
 * [reloadKey] changes after a collection so the paid order drops off the list.
 */
@Composable
fun DeliveryScreen(
    reloadKey: Int,
    staffName: String?,
    onCollectCard: (DeliveryOrder) -> Unit,
    onCollectCash: (DeliveryOrder) -> Unit
) {
    var orders by remember { mutableStateOf<List<DeliveryOrder>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var backFor by remember { mutableStateOf<DeliveryOrder?>(null) }
    // The last take-out / bring-back answer, shown under the header.
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // A collection elsewhere (reloadKey) ends the run's notice.
    LaunchedEffect(reloadKey) { notice = null }
    LaunchedEffect(reloadKey, refreshTick) {
        loading = true
        val result = DeliveryOrderRepository.fetchOpen()
        failed = result == null
        if (result != null) orders = result
        loading = false
        // Orders move between "my run" and "waiting": start from the top, headers included.
        listState.scrollToItem(0)
    }

    fun answer(order: DeliveryOrder, r: String?): String = when (r) {
        "ok" -> ""
        null -> context.getString(R.string.retail_can_t_reach_the_server)
        "taken" -> context.getString(R.string.retail_delivery_taken, order.order_no.toString())
        "not_open", "not_out", "not_found" -> context.getString(R.string.retail_delivery_gone, order.order_no.toString())
        else -> r
    }

    fun takeOut(order: DeliveryOrder) {
        busy = true
        scope.launch {
            val r = DeliveryOrderRepository.takeOut(order.id, staffName)
            notice = answer(order, r).ifEmpty { context.getString(R.string.retail_delivery_out_done, order.order_no.toString()) }
            busy = false
            if (r != null) refreshTick++
        }
    }

    fun bringBack(order: DeliveryOrder, note: String) {
        busy = true
        scope.launch {
            val r = DeliveryOrderRepository.bringBack(order.id, note)
            notice = answer(order, r).ifEmpty { context.getString(R.string.retail_delivery_back_done, order.order_no.toString()) }
            busy = false
            if (r != null) { expandedId = null; refreshTick++ }
        }
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
            if (loading || busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
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
        notice?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp).clickable { notice = null })
        }
        val list = orders.orEmpty()
        if (!loading && list.isEmpty() && !failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.retail_no_open_delivery_orders), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val mine = list.filter { it.isOut }
        val waiting = list.filterNot { it.isOut }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            listOf(R.string.retail_delivery_mine to mine, R.string.retail_delivery_waiting to waiting).forEach { (title, group) ->
                if (group.isNotEmpty() && mine.isNotEmpty()) item(key = "h$title") {
                    Text(stringResource(title, group.size), style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(group, key = { it.id }) { order ->
                    OrderCard(
                        order = order,
                        expanded = expandedId == order.id,
                        busy = busy,
                        onToggle = { expandedId = if (expandedId == order.id) null else order.id },
                        onTakeOut = { takeOut(order) },
                        onBack = { backFor = order },
                        onCard = { onCollectCard(order) },
                        onCash = { onCollectCash(order) }
                    )
                }
            }
        }
    }

    backFor?.let { order ->
        BringBackDialog(
            order = order,
            onConfirm = { note -> backFor = null; bringBack(order, note) },
            onDismiss = { backFor = null }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BringBackDialog(order: DeliveryOrder, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    val reasons = listOf(
        stringResource(R.string.retail_delivery_reason_nobody),
        stringResource(R.string.retail_delivery_reason_address),
        stringResource(R.string.retail_delivery_reason_refused)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.retail_delivery_back_title, order.order_no.toString())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.retail_delivery_back_why))
                reasons.forEach { r ->
                    FilterChip(selected = note == r, onClick = { note = r }, label = { Text(r) })
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, singleLine = true,
                    label = { Text(stringResource(R.string.retail_delivery_back_note)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(note) }, enabled = note.isNotBlank()) {
                Text(stringResource(R.string.retail_delivery_back_confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_cancel_2)) } },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun OrderCard(
    order: DeliveryOrder,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onTakeOut: () -> Unit,
    onBack: () -> Unit,
    onCard: () -> Unit,
    onCash: () -> Unit
) {
    val total = order.subtotalCents + order.taxCents
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val borderCol = if (order.isOut) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        border = BorderStroke(if (order.isOut) 1.5.dp else 0.5.dp, borderCol),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalShipping, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("D${order.order_no}", fontWeight = FontWeight.Black, color = textColor, modifier = Modifier.weight(1f))
                Text(money(total), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            if (order.isOut) {
                Text(
                    listOfNotNull(stringResource(R.string.retail_delivery_out), order.out_staff_name, clock(order.out_at)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
            } else if (order.attempts > 0) {
                Text(
                    stringResource(R.string.retail_delivery_tried, order.attempts) + (order.last_attempt_note?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(order.customer_name, fontWeight = FontWeight.Bold, color = textColor)
            Text(order.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            order.customer_phone?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            order.note?.takeIf { it.isNotBlank() }?.let {
                Text("Note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    order.customer_phone?.takeIf { it.isNotBlank() }?.let { phone ->
                        AssistChip(onClick = { open(context, "tel:" + phone.filter { it.isDigit() || it == '+' }, android.content.Intent.ACTION_DIAL) },
                            label = { Text(stringResource(R.string.retail_delivery_call)) }, leadingIcon = { Icon(Icons.Default.Call, null) })
                    }
                    AssistChip(onClick = { open(context, "geo:0,0?q=" + android.net.Uri.encode(order.address)) },
                        label = { Text(stringResource(R.string.retail_delivery_map)) }, leadingIcon = { Icon(Icons.Default.Place, null) })
                }
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
                if (!order.isOut) {
                    Button(onClick = onTakeOut, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.LocalShipping, null); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.retail_delivery_take_out), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val collect: @Composable RowScope.(Boolean) -> Unit = { card ->
                        val icon = if (card) Icons.Default.CreditCard else Icons.Default.Payments
                        val label = stringResource(if (card) R.string.retail_card else R.string.retail_cash)
                        val onClick = if (card) onCard else onCash
                        // Out for delivery, collecting is the main step; still in the store, taking it out is.
                        if (order.isOut) Button(onClick = onClick, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Icon(icon, null); Spacer(Modifier.width(6.dp)); Text(label, fontWeight = FontWeight.Bold)
                        } else OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp))
                            Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                    collect(true)
                    collect(false)
                }
                if (order.isOut) {
                    TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Undo, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.retail_delivery_bring_back), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
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

/** Dialer / maps; a terminal without such an app just says so. */
private fun open(context: android.content.Context, uri: String, action: String = android.content.Intent.ACTION_VIEW) {
    try {
        context.startActivity(android.content.Intent(action, android.net.Uri.parse(uri)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, R.string.retail_delivery_no_app, android.widget.Toast.LENGTH_SHORT).show()
    }
}
