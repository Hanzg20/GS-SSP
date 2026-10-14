package com.goldsky.ssp.feature.retail.dine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.model.SaleLine
import com.goldsky.ssp.payment.DineRepository
import java.text.SimpleDateFormat
import java.util.Locale

private fun money(cents: Int) = "$" + "%.2f".format(cents / 100.0)

/** Epoch millis of a Postgres timestamptz ("2026-10-13T12:00:00.123456+00:00"); java.time needs API 26. */
internal fun parseIso(iso: String?): Long? = try {
    iso?.let {
        val noFraction = it.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00")
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(noFraction)?.time
    }
} catch (e: Exception) { null }

/** Minutes since an ISO timestamp, or null. */
private fun minutesSince(iso: String?): Long? = parseIso(iso)?.let { (System.currentTimeMillis() - it) / 60_000 }

private fun clock(iso: String?): String? = parseIso(iso)?.let { SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(it)) }

/** The server's refusal codes in words; unknown codes as they are. */
@Composable
fun dineMessage(code: String): String = when (code) {
    DineViewModel.OFFLINE -> stringResource(R.string.dine_err_offline)
    "check_paid" -> stringResource(R.string.dine_err_check_paid)
    "check_void" -> stringResource(R.string.dine_err_check_void)
    "check_not_empty" -> stringResource(R.string.dine_err_not_empty)
    "item_paid" -> stringResource(R.string.dine_err_item_paid)
    "table_not_found" -> stringResource(R.string.dine_err_table_gone)
    // Another terminal got there first (two terminals on one floor).
    "order_accepted" -> stringResource(R.string.dine_err_order_accepted)
    "order_rejected" -> stringResource(R.string.dine_err_order_rejected)
    "order_not_found" -> stringResource(R.string.dine_err_order_gone)
    "table_in_use" -> stringResource(R.string.dine_err_table_in_use)
    "check_not_found" -> stringResource(R.string.dine_err_check_gone)
    "sold_out" -> stringResource(R.string.dine_err_sold_out)
    else -> code
}

/**
 * Keeps the floor and the open check fresh (every 4 s) while it is on screen. Every dine
 * screen needs it -- the floor AND an open check: a round another terminal sends, or a
 * payment taken there, must show here before this terminal bills or takes money.
 */
@Composable
fun DineRefresh(dine: DineViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(dine) { dine.startRefreshing(context); onDispose { dine.stopRefreshing() } }
}

/**
 * The floor: every table of the store, free or with its check (guests, total,
 * how long they've been seated). Tap a free table to seat it, a busy one to
 * open its check. Refreshes every few seconds -- other terminals seat and
 * order too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DineFloor(dine: DineViewModel, staffId: String?, staffName: String?, modifier: Modifier = Modifier, minCell: Dp = 130.dp) {
    DineRefresh(dine)
    val context = androidx.compose.ui.platform.LocalContext.current
    val floor = dine.floor
    var area by remember { mutableStateOf<String?>(null) }
    var seating by remember { mutableStateOf<DineRepository.DiningTable?>(null) }
    var reviewingScans by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    // A chime and a buzz when a customer's QR order comes in.
    LaunchedEffect(dine.scanArrivals) {
        if (dine.scanArrivals > 0) {
            runCatching {
                android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
                    .startTone(android.media.ToneGenerator.TONE_PROP_ACK, 400)
            }
            com.goldsky.ssp.common.FeedbackManager.vibrate(context, 300)
        }
    }

    Column(modifier.fillMaxSize()) {
        if (!dine.online) {
            Surface(color = scheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudOff, null, tint = scheme.onErrorContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dine_err_offline), color = scheme.onErrorContainer)
                }
            }
        }
        if (dine.scanPending.isNotEmpty()) {
            Surface(
                color = scheme.tertiary, contentColor = scheme.onTertiary,
                modifier = Modifier.fillMaxWidth().clickable { reviewingScans = true }
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(androidx.compose.material.icons.Icons.Default.Notifications, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.scan_pending_banner, dine.scanPending.size), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.scan_review), fontWeight = FontWeight.Bold)
                }
            }
        }
        when {
            floor == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            floor.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.dine_no_tables), color = scheme.onSurfaceVariant)
            }
            else -> {
                val areas = floor.mapNotNull { it.table.area }.distinct()
                if (areas.size > 1) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = area == null, onClick = { area = null }, label = { Text(stringResource(R.string.retail_all)) }) }
                        items(areas) { a -> FilterChip(selected = area == a, onClick = { area = a }, label = { Text(a) }) }
                    }
                }
                val busy = floor.count { it.check != null }
                Text(
                    stringResource(R.string.dine_floor_summary, busy, floor.size),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minCell),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(floor.filter { area == null || it.table.area == area }, key = { it.table.id }) { ft ->
                        TableCard(ft, dine.scanPending.count { it.table_id == ft.table.id }) {
                            val c = ft.check
                            if (c != null) dine.openCheck(c.id) else seating = ft.table
                        }
                    }
                }
            }
        }
    }

    if (reviewingScans && dine.scanPending.isNotEmpty()) {
        ScanOrdersDialog(
            orders = dine.scanPending,
            busy = dine.busy,
            onAccept = { dine.acceptScan(context, it, staffName) },
            onReject = { o, reason -> dine.rejectScan(o, staffName, reason) },
            onDismiss = { reviewingScans = false }
        )
    }

    seating?.let { t ->
        SeatDialog(t, onSeat = { guests -> seating = null; dine.seat(t, guests, staffId, staffName) }, onDismiss = { seating = null })
    }
}

@Composable
private fun TableCard(ft: DineRepository.FloorTable, scanPending: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val c = ft.check
    val (bg, fg) = when (c?.status) {
        null -> scheme.surface to scheme.onSurface
        "BILLED" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Surface(
        shape = RoundedCornerShape(14.dp), color = bg,
        border = BorderStroke(if (c == null) 1.dp else 0.dp, scheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ft.table.name, fontSize = 22.sp, fontWeight = FontWeight.Black, color = fg, modifier = Modifier.weight(1f))
                if (scanPending > 0) {
                    Surface(color = scheme.tertiary, shape = RoundedCornerShape(50)) {
                        Text("QR $scanPending", color = scheme.onTertiary, style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (c != null) Text("#${c.check_no}", style = MaterialTheme.typography.labelSmall, color = fg)
            }
            if (c == null) {
                Text(stringResource(R.string.dine_free_seats, ft.table.seats), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    listOfNotNull(stringResource(R.string.dine_guests, c.guests), minutesSince(c.opened_at)?.let { stringResource(R.string.dine_minutes, it) }).joinToString(" · "),
                    color = fg, style = MaterialTheme.typography.bodySmall
                )
                Text(money(ft.totalCents), color = fg, fontWeight = FontWeight.Bold)
                if (c.status == "BILLED") Text(stringResource(R.string.dine_billed), color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                c.server_name?.let { Text(it, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun SeatDialog(table: DineRepository.DiningTable, onSeat: (Int) -> Unit, onDismiss: () -> Unit) {
    var guests by remember { mutableStateOf(table.seats.coerceIn(1, 99)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dine_seat_title, table.name)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dine_how_many_guests))
                Spacer(Modifier.height(12.dp))
                GuestsStepper(guests) { guests = it }
            }
        },
        confirmButton = { Button(onClick = { onSeat(guests) }) { Text(stringResource(R.string.dine_seat)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_mod_cancel)) } }
    )
}

@Composable
fun GuestsStepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Remove, null) }
        Text("$value", fontSize = 32.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 24.dp))
        FilledTonalIconButton(onClick = { onChange((value + 1).coerceAtMost(99)) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Add, null) }
    }
}

/**
 * The check's lines, round by round: what the kitchen has, voided lines struck
 * through, lines paid on their own marked. [onVoid] (managers) offers a void.
 */
fun LazyListScope.checkItemRows(detail: DineRepository.CheckDetail, onVoid: ((DineRepository.Item) -> Unit)?) {
    detail.items.groupBy { it.round }.toSortedMap().forEach { (round, items) ->
        item(key = "round-$round") {
            val first = items.first()
            Text(
                listOfNotNull(
                    if (round == 1) stringResource(R.string.dine_round_first) else stringResource(R.string.dine_round_add, round),
                    clock(first.sent_at),
                    first.sent_by
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
            )
        }
        items(items, key = { it.id }) { item -> CheckItemRow(item, onVoid) }
    }
}

@Composable
private fun CheckItemRow(item: DineRepository.Item, onVoid: ((DineRepository.Item) -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val struck = if (item.isVoid) TextDecoration.LineThrough else null
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${item.quantity} × ${item.name}", fontWeight = FontWeight.Medium, textDecoration = struck,
                color = if (item.isVoid) scheme.onSurfaceVariant else scheme.onSurface)
            if (item.options.isNotEmpty()) Text("+ " + item.options.joinToString(", "), style = MaterialTheme.typography.bodySmall,
                color = scheme.secondary, textDecoration = struck)
        }
        if (item.paid_ecr_ref_num != null) {
            Text(stringResource(R.string.dine_item_paid), style = MaterialTheme.typography.labelSmall, color = scheme.primary,
                modifier = Modifier.padding(end = 6.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(money(item.lineTotalCents), textDecoration = struck, color = if (item.isVoid) scheme.onSurfaceVariant else scheme.onSurface)
        if (onVoid != null && !item.isVoid && item.paid_ecr_ref_num == null) {
            IconButton(onClick = { onVoid(item) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.dine_void), tint = scheme.error, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Total / paid / balance lines for a check. */
@Composable
fun CheckTotals(detail: DineRepository.CheckDetail) {
    val scheme = MaterialTheme.colorScheme
    @Composable fun line(label: String, cents: Int, strong: Boolean = false) = Row(Modifier.fillMaxWidth()) {
        Text(label, color = if (strong) scheme.onSurface else scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
        Text(money(cents), color = if (strong) scheme.onSurface else scheme.onSurfaceVariant,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, fontSize = if (strong) 18.sp else 14.sp)
    }
    line(stringResource(R.string.dine_subtotal), detail.subtotalCents)
    line(stringResource(R.string.dine_tax), detail.taxCents)
    line(stringResource(R.string.dine_total), detail.totalCents, strong = detail.paidCents == 0)
    if (detail.paidCents > 0) {
        line(stringResource(R.string.dine_paid), detail.paidCents)
        line(stringResource(R.string.dine_balance), detail.balanceCents, strong = true)
    }
}

/**
 * How this payment splits the check: the whole balance, chosen lines, or one
 * of N equal shares. Tip and the payment method come after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckPayDialog(detail: DineRepository.CheckDetail, onPay: (CheckPay) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(0) }       // 0 all, 1 by items, 2 equal shares
    val picked = remember { mutableStateListOf<String>() }
    var ways by remember { mutableStateOf(detail.check.guests.coerceIn(2, 20)) }
    val table = detail.table?.name ?: "?"
    val chosen: CheckPay? = when (mode) {
        0 -> CheckPay(detail.check.id, detail.balanceCents, null,
            detail.unpaidItems.map { SaleLine(it.displayName, it.quantity, it.unit_price_cents, it.tax_rate_bps, it.product_id) },
            "", table, detail.check.check_no)
        1 -> DineRepository.amountForItems(detail, picked).takeIf { it > 0 }?.let { amount ->
            val lines = detail.unpaidItems.filter { it.id in picked }
            CheckPay(detail.check.id, amount, picked.toList(),
                lines.map { SaleLine(it.displayName, it.quantity, it.unit_price_cents, it.tax_rate_bps, it.product_id) },
                "", table, detail.check.check_no)
        }
        else -> CheckPay(detail.check.id, DineRepository.equalShare(detail.balanceCents, detail.balanceCents, ways, ways),
            null, emptyList(), "1/$ways", table, detail.check.check_no)
    }?.takeIf { it.amountCents > 0 }
    val pay = chosen?.let { it.copy(taxCents = CheckPay.taxOf(detail, it.amountCents, if (mode == 1) it.lines else null)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dine_pay_title, table, money(detail.balanceCents))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.dine_pay_all, R.string.dine_pay_items, R.string.dine_pay_equal).forEachIndexed { i, label ->
                        FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(stringResource(label)) })
                    }
                }
                when (mode) {
                    0 -> Text(stringResource(R.string.dine_pay_all_desc))
                    1 -> {
                        Text(stringResource(R.string.dine_pay_items_desc), style = MaterialTheme.typography.bodySmall)
                        detail.unpaidItems.forEach { item ->
                            val on = item.id in picked
                            Row(
                                Modifier.fillMaxWidth().clickable { if (on) picked.remove(item.id) else picked.add(item.id) }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = on, onCheckedChange = { if (it) picked.add(item.id) else picked.remove(item.id) })
                                Text("${item.quantity} × ${item.displayName}", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(money(item.lineTotalCents))
                            }
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.dine_pay_equal_desc))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GuestsStepper(ways) { ways = it.coerceIn(2, 20) } }
                    }
                }
                pay?.let {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.dine_pay_this, money(it.amountCents)), fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        },
        confirmButton = { Button(enabled = pay != null, onClick = { pay?.let(onPay) }) { Text(stringResource(R.string.dine_pay_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_mod_cancel)) } }
    )
}

/**
 * Customers' QR orders waiting for staff: check them (right table, nothing
 * silly) and accept -- the order becomes the table's next round and prints in
 * the kitchen -- or reject with a reason the customer sees on their phone.
 */
@Composable
fun ScanOrdersDialog(
    orders: List<DineRepository.ScanOrder>,
    busy: Boolean,
    onAccept: (DineRepository.ScanOrder) -> Unit,
    onReject: (DineRepository.ScanOrder, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var rejecting by remember { mutableStateOf<DineRepository.ScanOrder?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_dialog_title, orders.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                orders.forEach { o ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.scan_table, o.table_name), fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                clock(o.created_at)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            }
                            o.items.forEach { l ->
                                Row {
                                    Text("${l.quantity} × ${l.name}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                    Text(money(l.unit_price_cents * l.quantity))
                                }
                                if (l.options.isNotEmpty()) Text("+ " + l.options.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                l.note?.let { Text("* $it", style = MaterialTheme.typography.bodySmall) }
                            }
                            o.note?.let { Text(stringResource(R.string.scan_note, it), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
                            Text(stringResource(R.string.scan_total, money(o.subtotal_cents + o.tax_cents)), fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { rejecting = o }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.scan_reject), color = MaterialTheme.colorScheme.error)
                                }
                                Button(onClick = { onAccept(o) }, enabled = !busy, modifier = Modifier.weight(1.5f)) {
                                    Text(stringResource(R.string.scan_accept), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_done)) } }
    )
    rejecting?.let { o ->
        val reasons = listOf(R.string.scan_reason_wrong_table, R.string.scan_reason_sold_out, R.string.scan_reason_ask_staff).map { stringResource(it) }
        AlertDialog(
            onDismissRequest = { rejecting = null },
            title = { Text(stringResource(R.string.scan_reject_title, o.table_name)) },
            text = {
                Column {
                    reasons.forEach { r ->
                        TextButton(onClick = { rejecting = null; onReject(o, r) }, modifier = Modifier.fillMaxWidth()) { Text(r) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text(stringResource(R.string.retail_mod_cancel)) } }
        )
    }
}
