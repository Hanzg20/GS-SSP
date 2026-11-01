package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.feature.retail.RetailRepository
import com.goldsky.ssp.payment.InventoryRepository
import com.goldsky.ssp.payment.StaffRepository
import com.goldsky.ssp.ui.theme.GsTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun stockColor(i: InventoryRepository.Item): Color = when {
    !i.tracked -> MaterialTheme.colorScheme.onSurfaceVariant
    i.out -> GsTheme.colors.danger
    i.low -> GsTheme.colors.warning
    else -> GsTheme.colors.success
}

/**
 * Stock of the store's retail products (Journal > Stock on Iris): low and out
 * first, search by name or barcode (a scanner typing into the field works), tap
 * one to receive goods, count it, adjust it or see its history. Sales move the
 * numbers by themselves on the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryPane() {
    var items by remember { mutableStateOf<List<InventoryRepository.Item>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var onlyLow by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<InventoryRepository.Item?>(null) }

    LaunchedEffect(reload) {
        loading = true
        val r = InventoryRepository.list()
        failed = r == null
        if (r != null) items = r
        loading = false
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.stock_search)) }, leadingIcon = { Icon(Icons.Default.Search, null) }
            )
            IconButton(onClick = { reload++ }, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, stringResource(R.string.retail_refresh), tint = MaterialTheme.colorScheme.primary)
            }
        }
        val all = items.orEmpty()
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = onlyLow, onClick = { onlyLow = !onlyLow },
                label = { Text(stringResource(R.string.stock_only_low, all.count { it.low })) })
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.stock_summary, all.count { it.tracked }, all.count { it.out }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (failed) Text(stringResource(R.string.retail_can_t_reach_the_server), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        val shown = InventoryRepository.filter(all, query, onlyLow)
        if (items != null && shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.stock_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { i ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { open = i },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(i.name, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text(listOfNotNull(i.category, i.barcode,
                                if (i.tracked) stringResource(R.string.stock_sold_7d, i.sold_7d) else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(i.stock_qty?.toString() ?: "—", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black, color = stockColor(i))
                            Text(stringResource(when {
                                !i.tracked -> R.string.stock_untracked
                                i.out -> R.string.stock_out
                                i.low -> R.string.stock_low
                                else -> R.string.stock_ok
                            }), style = MaterialTheme.typography.labelSmall, color = stockColor(i))
                        }
                    }
                }
            }
        }
    }

    open?.let { i -> StockDialog(i, onChanged = { reload++ }, onDismiss = { open = null }) }
}

/**
 * One product's stock: receive goods, count, adjust (with a reason), low level,
 * stop tracking, and its latest moves. [onChanged] after a change went through
 * (the catalog is synced too, so "sold out" shows right away on this terminal).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDialog(item: InventoryRepository.Item, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(item.stock_qty) }
    // RECEIVE / COUNT / ADJUST
    var kind by remember { mutableStateOf(if (item.tracked) InventoryRepository.RECEIVE else InventoryRepository.COUNT) }
    var qty by remember { mutableStateOf("") }
    var minus by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var low by remember { mutableStateOf(item.low_stock_qty.toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var moves by remember { mutableStateOf<List<InventoryRepository.Move>?>(null) }
    var movesTick by remember { mutableStateOf(0) }
    // One ref per entry: a double tap is one change.
    var ref by remember { mutableStateOf(InventoryRepository.newRef()) }

    LaunchedEffect(movesTick) { moves = InventoryRepository.moves(item.id, 15) }

    val n = qty.trim().toIntOrNull()
    val valid = when (kind) {
        InventoryRepository.RECEIVE -> n != null && n > 0
        InventoryRepository.COUNT -> n != null && n >= 0
        else -> n != null && n > 0 && note.isNotBlank()
    }

    fun run(k: String, q: Int?, nt: String?) {
        busy = true; error = null
        scope.launch {
            val r = InventoryRepository.change(item.id, k, q, nt, StaffRepository.current, ref)
            busy = false
            if (r.ok) {
                if (k != InventoryRepository.LOW) current = r.stockQty
                qty = ""; note = ""; ref = InventoryRepository.newRef(); movesTick++
                onChanged()
                RetailRepository.syncWithCloud(context)
                if (k == InventoryRepository.UNTRACK) onDismiss()
            } else error = when (r.message) {
                null -> context.getString(R.string.retail_can_t_reach_the_server)
                "note_required" -> context.getString(R.string.stock_err_note)
                "not_tracked" -> context.getString(R.string.stock_err_not_tracked)
                "bad_qty" -> context.getString(R.string.stock_err_qty)
                else -> r.message
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(item.name, maxLines = 2)
                Text(current?.let { stringResource(R.string.stock_now, it) } ?: stringResource(R.string.stock_untracked_long),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(InventoryRepository.RECEIVE to R.string.stock_receive, InventoryRepository.COUNT to R.string.stock_count,
                        InventoryRepository.ADJUST to R.string.stock_adjust).forEach { (k, label) ->
                        FilterChip(selected = kind == k, onClick = { kind = k; error = null }, label = { Text(stringResource(label)) },
                            enabled = k != InventoryRepository.ADJUST || current != null)
                    }
                }
                Text(stringResource(when (kind) {
                    InventoryRepository.RECEIVE -> R.string.stock_receive_hint
                    InventoryRepository.COUNT -> R.string.stock_count_hint
                    else -> R.string.stock_adjust_hint
                }), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (kind == InventoryRepository.ADJUST) {
                        FilterChip(selected = minus, onClick = { minus = !minus }, label = { Text(if (minus) "−" else "+", fontWeight = FontWeight.Black) })
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedTextField(qty, { qty = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.stock_qty)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(note, { note = it.take(200) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(if (kind == InventoryRepository.ADJUST) R.string.stock_reason else R.string.stock_note)) })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { run(kind, if (kind == InventoryRepository.ADJUST && minus) -(n ?: 0) else n, note) },
                    enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stock_apply), fontWeight = FontWeight.Bold)
                }

                if (current != null) {
                    Divider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(low, { low = it.filter { c -> c.isDigit() }.take(5) }, singleLine = true, modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.stock_low_level)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TextButton(onClick = { run(InventoryRepository.LOW, low.toIntOrNull(), null) }, enabled = !busy && low.toIntOrNull() != null) {
                            Text(stringResource(R.string.clock_save))
                        }
                    }
                    TextButton(onClick = { run(InventoryRepository.UNTRACK, null, null) }, enabled = !busy) {
                        Text(stringResource(R.string.stock_untrack), color = MaterialTheme.colorScheme.error)
                    }
                }

                Divider()
                Text(stringResource(R.string.stock_history), fontWeight = FontWeight.Bold)
                when {
                    moves == null -> Text("…")
                    moves!!.isEmpty() -> Text(stringResource(R.string.stock_no_moves), style = MaterialTheme.typography.bodySmall)
                    else -> moves!!.forEach { m -> MoveRow(m) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_close)) } }
    )
}

@Composable
private fun MoveRow(m: InventoryRepository.Move) {
    val at = com.goldsky.ssp.payment.TimeClockRepository.parseIso(m.created_at)?.let { SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it)) } ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(when (m.reason) {
                "SALE" -> R.string.stock_move_sale
                "RETURN" -> R.string.stock_move_return
                "RECEIVE" -> R.string.stock_move_receive
                "COUNT" -> R.string.stock_move_count
                else -> R.string.stock_move_adjust
            }), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(listOfNotNull(at, m.staff_name, m.note).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Text((if (m.delta > 0) "+" else "") + m.delta, fontWeight = FontWeight.Bold,
            color = if (m.delta < 0) GsTheme.colors.danger else GsTheme.colors.success)
        m.qty_after?.let { Text("  → $it", style = MaterialTheme.typography.bodySmall) }
    }
}

