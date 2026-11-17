package com.goldsky.ssp.feature.retail.arke

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.feature.retail.dine.parseIso
import com.goldsky.ssp.payment.ArkeOrderRepository
import com.goldsky.ssp.payment.ArkeOrderRepository.Order
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun money(cents: Int) = "$%.2f".format(cents / 100.0)
private fun clock(iso: String?): String = parseIso(iso)?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: ""

private val Gold = Color(0xFFF59E0B)
private val Sky = Color(0xFF0284C7)
private val Green = Color(0xFF059669)

/**
 * Chimes once per new paid online order, wherever the terminal is (call it once at the top of
 * the app's content), and keeps the list fresh.
 */
@Composable
fun ArkeChime(vm: ArkeViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.startRefreshing() }
    LaunchedEffect(vm.arrivals) {
        if (vm.arrivals > 0) {
            try {
                android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                    .startTone(android.media.ToneGenerator.TONE_PROP_PROMPT, 900)
            } catch (_: Exception) { /* no tone on this device: the banner still shows */ }
            com.goldsky.ssp.common.FeedbackManager.vibrate(context, 500)
        }
    }
}

/** "New online order A12" across the top of any screen, until opened or dismissed. */
@Composable
fun ArkeArrivalBanner(vm: ArkeViewModel, onOpen: () -> Unit) {
    val o = vm.latestArrival ?: return
    Surface(color = Gold, modifier = Modifier.fillMaxWidth().clickable { vm.dismissArrival(); onOpen() }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NotificationsActive, null, tint = Color.Black)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.arke_new_banner, o.pickup_code ?: ""), fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color.Black,
                modifier = Modifier.weight(1f))
            Text("${o.customer_name} · ${money(o.total_cents)}", fontWeight = FontWeight.Bold, color = Color.Black)
            IconButton(onClick = { vm.dismissArrival() }) { Icon(Icons.Default.Close, null, tint = Color.Black) }
        }
    }
}

/** The store's online orders: new (accept / can't make it), preparing (ready), ready (picked up), and today's done. */
@Composable
fun ArkeOrdersScreen(vm: ArkeViewModel, staffName: String?) {
    val context = LocalContext.current
    var accepting by remember { mutableStateOf<Order?>(null) }
    var rejecting by remember { mutableStateOf<Order?>(null) }
    LaunchedEffect(Unit) { vm.dismissArrival(); vm.refreshNow() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ShoppingBag, null, tint = Gold, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.arke_title), fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            if (!vm.online) Text(stringResource(R.string.arke_offline), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        vm.message?.let { msg ->
            Surface(color = Color(0xFFFFF7E6), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.clearMessage() }) { Icon(Icons.Default.Close, null) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val by = vm.orders.groupBy { it.status }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Lane(stringResource(R.string.arke_col_new), Gold, by[ArkeOrderRepository.NEW].orEmpty(), Modifier.weight(1f)) { o ->
                OrderCard(o, vm.busyId == o.id, onReprint = null) {
                    Button(onClick = { accepting = o }, enabled = vm.busyId == null, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
                        modifier = Modifier.weight(1f)) { Text(stringResource(R.string.arke_accept), fontWeight = FontWeight.Black) }
                    OutlinedButton(onClick = { rejecting = o }, enabled = vm.busyId == null) { Text(stringResource(R.string.arke_reject)) }
                }
            }
            Lane(stringResource(R.string.arke_col_preparing), Sky, by[ArkeOrderRepository.ACCEPTED].orEmpty(), Modifier.weight(1f)) { o ->
                OrderCard(o, vm.busyId == o.id, onReprint = { vm.reprint(context, o, staffName) }) {
                    Button(onClick = { vm.ready(context, o, staffName) }, enabled = vm.busyId == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Sky), modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.arke_ready), fontWeight = FontWeight.Black)
                    }
                    OutlinedButton(onClick = { rejecting = o }, enabled = vm.busyId == null) { Text(stringResource(R.string.arke_reject)) }
                }
            }
            Lane(stringResource(R.string.arke_col_ready), Green, by[ArkeOrderRepository.READY].orEmpty(), Modifier.weight(1f)) { o ->
                OrderCard(o, vm.busyId == o.id, onReprint = { vm.reprint(context, o, staffName) }) {
                    Button(onClick = { vm.pickedUp(context, o, staffName) }, enabled = vm.busyId == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Green), modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.arke_picked_up), fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        val done = vm.orders.filter { !it.open }
        if (done.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.arke_done_today), fontWeight = FontWeight.Bold, color = Color.Gray)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                done.take(8).forEach { o ->
                    val rejected = o.status == ArkeOrderRepository.REJECTED
                    AssistChip(onClick = {}, label = {
                        Text("${o.pickup_code ?: ""} ${o.customer_name} · " + stringResource(if (rejected) R.string.arke_status_rejected else R.string.arke_status_picked_up))
                    })
                }
            }
        }
    }

    accepting?.let { o ->
        AcceptDialog(o, onDismiss = { accepting = null }) { minutes -> accepting = null; vm.accept(context, o, minutes, staffName) }
    }
    rejecting?.let { o ->
        RejectDialog(o, onDismiss = { rejecting = null }) { reason -> rejecting = null; vm.reject(context, o, reason, staffName) }
    }
}

@Composable
private fun Lane(title: String, color: Color, orders: List<Order>, modifier: Modifier, card: @Composable (Order) -> Unit) {
    Column(modifier.fillMaxHeight().background(Color(0xFFF8FAFC), RoundedCornerShape(16.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(6.dp))
            Text(title, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            if (orders.isNotEmpty()) Text("${orders.size}", fontWeight = FontWeight.Black, color = color)
        }
        Spacer(Modifier.height(8.dp))
        if (orders.isEmpty()) {
            Text(stringResource(R.string.arke_none), color = Color.Gray, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders.sortedBy { it.paid_at ?: "" }, key = { it.id }) { card(it) }
            }
        }
    }
}

@Composable
private fun OrderCard(o: Order, busy: Boolean, onReprint: (() -> Unit)?, actions: @Composable RowScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.pickup_code ?: "", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Sky)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.customer_name, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    o.customer_phone?.let { Text(it, color = Color.Gray, fontSize = 13.sp) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(money(o.total_cents), fontWeight = FontWeight.Black)
                    Text(stringResource(R.string.arke_paid_at, clock(o.paid_at)), color = Color.Gray, fontSize = 12.sp)
                }
            }
            if (o.status == ArkeOrderRepository.ACCEPTED && o.ready_by != null) {
                Text(stringResource(R.string.arke_ready_by, clock(o.ready_by)), color = Gold, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 4.dp))
            }
            Divider(Modifier.padding(vertical = 8.dp))
            o.items.forEach { l ->
                Text("${l.quantity} × ${l.name}", fontWeight = FontWeight.Bold)
                l.options.forEach { Text("   + $it", fontSize = 13.sp, color = Sky) }
                l.note?.takeIf { it.isNotBlank() }?.let { Text("   * $it", fontSize = 13.sp, color = Color.Gray) }
            }
            o.note?.takeIf { it.isNotBlank() }?.let {
                Surface(color = Color(0xFFFFF7E6), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(it, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.arke_items, o.itemCount), color = Color.Gray, fontSize = 12.sp)
                if (o.tip_cents > 0) Text(" · " + stringResource(R.string.arke_tip, money(o.tip_cents)), color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (onReprint != null) TextButton(onClick = onReprint) {
                    Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.arke_reprint), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

@Composable
private fun AcceptDialog(o: Order, onDismiss: () -> Unit, onAccept: (Int) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
            Column(Modifier.padding(20.dp)) {
                Text("${o.pickup_code ?: ""} · ${o.customer_name}", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.arke_ready_in), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                listOf(listOf(10, 15, 20), listOf(30, 45, 60)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        row.forEach { m ->
                            Button(onClick = { onAccept(m) }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
                                modifier = Modifier.width(110.dp).height(56.dp)) {
                                Text(stringResource(R.string.arke_minutes, m), fontWeight = FontWeight.Black, fontSize = 17.sp)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.arke_cancel)) }
            }
        }
    }
}

@Composable
private fun RejectDialog(o: Order, onDismiss: () -> Unit, onReject: (String) -> Unit) {
    // The customer reads the reason on their phone in any language: kept in English.
    val reasons = listOf(R.string.arke_reason_sold_out to "Sold out", R.string.arke_reason_busy to "Too busy right now",
        R.string.arke_reason_closing to "Closing soon")
    var picked by remember { mutableStateOf<String?>(null) }
    var other by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val reason = if (other) text.trim() else picked
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
            Column(Modifier.padding(20.dp).width(380.dp)) {
                Text(stringResource(R.string.arke_reject_title, o.pickup_code ?: ""), fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(stringResource(R.string.arke_reject_hint), color = Color.Gray, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                reasons.forEach { (label, value) ->
                    val on = !other && picked == value
                    OutlinedButton(onClick = { other = false; picked = value }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        border = BorderStroke(if (on) 2.dp else 1.dp, if (on) Gold else Color.LightGray)) { Text(stringResource(label)) }
                }
                OutlinedButton(onClick = { other = true }, modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(if (other) 2.dp else 1.dp, if (other) Gold else Color.LightGray)) { Text(stringResource(R.string.arke_reason_other)) }
                if (other) OutlinedTextField(value = text, onValueChange = { text = it.take(200) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.arke_cancel)) }
                    Button(onClick = { reason?.takeIf { it.isNotBlank() }?.let(onReject) }, enabled = !reason.isNullOrBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.arke_reject_confirm), fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}
