package com.goldsky.ssp.ourea

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.payment.CashDrawerRepository
import com.goldsky.ssp.payment.CashDrawerRepository.Session
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.StaffMember
import com.goldsky.ssp.ui.components.NumericKeypad
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

private fun money(cents: Long) = (if (cents < 0) "-" else "") + "$" + "%.2f".format(Math.abs(cents) / 100.0)

// "2026-11-02T15:45:38.734798-05:00" (or "+00:00"): fraction dropped, shown in this terminal's time zone.
// SimpleDateFormat, not java.time: minSdk 25.
private fun timeOf(iso: String?): String = iso?.let {
    runCatching {
        val s = it.replace(' ', 'T').replace(Regex("\\.\\d+"), "").let { t -> if (Regex("[+-]\\d\\d$").containsMatchIn(t)) "$t:00" else t }
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s)!!
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(parsed)
    }.getOrNull()
} ?: ""

/**
 * The cash drawer (Ourea sidebar): open it with the float counted in, put cash in or
 * take it out during the shift (a reason each; taking out needs a manager), and
 * close by counting what's there -- the server works out what should be there
 * (float + cash sales - cash refunds + in - out) and the over / short.
 * The drawer pops open on the receipt printer set at the bottom, if any.
 */
@Composable
fun OureaDrawerScreen(staff: StaffMember) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<Session?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<Session>>(emptyList()) }
    var opening by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    var approvalFor by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var closing by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf<Session?>(null) }
    val noPrinter = DeviceRepository.getDrawerPrinterHost() == null
    val texts = mapOf(
        "already_open" to stringResource(R.string.ourea_drawer_err_already_open),
        "not_open" to stringResource(R.string.ourea_drawer_err_not_open),
        "reason_required" to stringResource(R.string.ourea_drawer_err_reason),
        "bad_amount" to stringResource(R.string.ourea_drawer_err_amount)
    )
    val offlineText = stringResource(R.string.ourea_drawer_offline)
    val noKickText = stringResource(R.string.ourea_drawer_no_kick)

    fun reload() {
        scope.launch {
            val s = CashDrawerRepository.status()
            offline = s == null
            if (s != null) session = s
            history = CashDrawerRepository.history(5) ?: history
            loaded = true
        }
    }
    LaunchedEffect(Unit) { reload() }

    // One write: the answer replaces the session; the drawer pops open when it went through.
    fun write(kick: Boolean, call: suspend () -> Session?, after: (Session) -> Unit = {}) {
        busy = true
        message = null
        scope.launch {
            val r = call()
            busy = false
            when {
                r == null -> message = offlineText
                !r.success -> message = texts[r.message] ?: r.message
                else -> {
                    if (r.open) session = r else { session = Session(open = false); closed = r; history = CashDrawerRepository.history(5) ?: history }
                    after(r)
                    if (kick && !noPrinter && !CashDrawerRepository.kick()) message = noKickText
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PointOfSale, null, tint = OureaGold, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.ourea_drawer_title), color = OureaTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, null, tint = OureaSkyBlue) }
        }
        message?.let { Text(it, color = OureaRed) }
        if (offline) Text(offlineText, color = OureaRed)

        val s = session
        when {
            !loaded -> CircularProgressIndicator(color = OureaGold)
            s == null -> {}
            !s.open -> DrawerCard {
                Text(stringResource(R.string.ourea_drawer_closed_now), color = OureaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.ourea_drawer_closed_hint), color = OureaTextSecondary)
                Button(onClick = { opening = true }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = OureaGold)) {
                    Text(stringResource(R.string.ourea_drawer_open))
                }
            }
            else -> DrawerCard {
                Text(stringResource(R.string.ourea_drawer_open_since, s.opened_by ?: "", timeOf(s.opened_at)), color = OureaTextSecondary)
                DrawerLine(stringResource(R.string.ourea_drawer_float), money(s.float_cents))
                DrawerLine(stringResource(R.string.ourea_drawer_cash_sales, s.cash_sales), "+ " + money(s.cash_sales_cents))
                if (s.cash_refunds > 0) DrawerLine(stringResource(R.string.ourea_drawer_cash_refunds, s.cash_refunds), "- " + money(s.cash_refunds_cents))
                if (s.pay_in_cents > 0) DrawerLine(stringResource(R.string.ourea_drawer_pay_ins), "+ " + money(s.pay_in_cents))
                if (s.pay_out_cents > 0) DrawerLine(stringResource(R.string.ourea_drawer_pay_outs), "- " + money(s.pay_out_cents))
                Divider(color = OureaSurfaceVariant)
                DrawerLine(stringResource(R.string.ourea_drawer_expected), money(s.expected_cents), bold = true)
                s.moves.filter { it.kind != CashDrawerRepository.NO_SALE }.takeLast(6).forEach { m ->
                    Text(
                        "${timeOf(m.at)}  " + (if (m.kind == CashDrawerRepository.PAY_IN) "+" else "-") + money(m.cents) +
                            "  ${m.note.orEmpty()} · ${m.by.orEmpty()}" + (m.approved_by?.takeIf { it != m.by }?.let { " ($it)" } ?: ""),
                        color = OureaTextSecondary, fontSize = 13.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { moving = CashDrawerRepository.PAY_IN }, enabled = !busy) { Text(stringResource(R.string.ourea_drawer_pay_in)) }
                    OutlinedButton(onClick = { moving = CashDrawerRepository.PAY_OUT }, enabled = !busy) { Text(stringResource(R.string.ourea_drawer_pay_out)) }
                    OutlinedButton(
                        onClick = { write(true, { CashDrawerRepository.move(CashDrawerRepository.NO_SALE, 0, null, staff, UUID.randomUUID().toString()) }) },
                        enabled = !busy && !noPrinter
                    ) { Text(stringResource(R.string.ourea_drawer_no_sale)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { closing = true }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = OureaGold)) {
                        Text(stringResource(R.string.ourea_drawer_close))
                    }
                }
            }
        }

        closed?.let { c -> DrawerCard { ClosedSummary(c, highlight = true) } }

        val earlier = history.filter { it.id != closed?.id }
        if (earlier.isNotEmpty()) {
            Text(stringResource(R.string.ourea_drawer_history), color = OureaTextPrimary, fontWeight = FontWeight.Bold)
            earlier.forEach { h -> DrawerCard { ClosedSummary(h, highlight = false) } }
        }

        DrawerCard { DrawerPrinterSetting() }
    }

    if (opening) AmountDialog(
        title = stringResource(R.string.ourea_drawer_open_title),
        hint = stringResource(R.string.ourea_drawer_open_hint),
        askNote = false,
        onDismiss = { opening = false },
        onConfirm = { cents, _ -> opening = false; write(true, { CashDrawerRepository.open(cents, staff) }) }
    )
    moving?.let { kind ->
        AmountDialog(
            title = stringResource(if (kind == CashDrawerRepository.PAY_IN) R.string.ourea_drawer_pay_in else R.string.ourea_drawer_pay_out),
            hint = stringResource(if (kind == CashDrawerRepository.PAY_IN) R.string.ourea_drawer_pay_in_hint else R.string.ourea_drawer_pay_out_hint),
            askNote = true,
            onDismiss = { moving = null },
            onConfirm = { cents, note ->
                moving = null
                // Taking cash out: a manager's say-so (the signed-in one, or their PIN now).
                if (kind == CashDrawerRepository.PAY_OUT && !staff.isManager) approvalFor = cents to note
                else write(true, { CashDrawerRepository.move(kind, cents, note, staff, UUID.randomUUID().toString(), if (kind == CashDrawerRepository.PAY_OUT) staff.name else null) })
            }
        )
    }
    approvalFor?.let { (cents, note) ->
        com.goldsky.ssp.feature.retail.ui.ManagerApprovalDialog(
            action = stringResource(R.string.ourea_drawer_pay_out_approve, money(cents)),
            onApproved = { manager ->
                approvalFor = null
                write(true, { CashDrawerRepository.move(CashDrawerRepository.PAY_OUT, cents, note, staff, UUID.randomUUID().toString(), manager.name) })
            },
            onDismiss = { approvalFor = null }
        )
    }
    if (closing) AmountDialog(
        title = stringResource(R.string.ourea_drawer_close_title),
        hint = stringResource(R.string.ourea_drawer_close_hint),
        askNote = true,
        noteOptional = true,
        onDismiss = { closing = false },
        onConfirm = { cents, note -> closing = false; write(false, { CashDrawerRepository.close(cents, staff, note.ifBlank { null }) }) }
    )
}

@Composable
private fun DrawerCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(OureaSurface, RoundedCornerShape(14.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun DrawerLine(label: String, value: String, bold: Boolean = false, color: androidx.compose.ui.graphics.Color? = null) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = OureaTextSecondary, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(value, color = color ?: OureaTextPrimary, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = if (bold) 18.sp else 15.sp)
    }
}

@Composable
private fun ClosedSummary(c: Session, highlight: Boolean) {
    val os = c.over_short_cents ?: 0
    Text(
        stringResource(R.string.ourea_drawer_closed_line, timeOf(c.opened_at), timeOf(c.closed_at), c.closed_by ?: ""),
        color = if (highlight) OureaTextPrimary else OureaTextSecondary, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal
    )
    DrawerLine(stringResource(R.string.ourea_drawer_expected), money(c.expected_cents))
    DrawerLine(stringResource(R.string.ourea_drawer_counted), money(c.counted_cents ?: 0))
    DrawerLine(
        stringResource(when { os > 0 -> R.string.ourea_drawer_over; os < 0 -> R.string.ourea_drawer_short; else -> R.string.ourea_drawer_even }),
        if (os == 0L) money(0) else money(Math.abs(os)), bold = true,
        color = when { os > 0 -> OureaAmber; os < 0 -> OureaRed; else -> OureaGreen }
    )
    c.close_note?.let { Text(it, color = OureaTextSecondary, fontSize = 13.sp) }
}

/** An amount on the calculator keypad ("5","0","0" = $5.00), with a reason when [askNote]. */
@Composable
private fun AmountDialog(title: String, hint: String, askNote: Boolean, noteOptional: Boolean = false,
                         onDismiss: () -> Unit, onConfirm: (cents: Long, note: String) -> Unit) {
    var cents by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("") }
    val ok = (cents > 0 || !askNote || noteOptional) && (!askNote || noteOptional || note.isNotBlank())
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = OureaSurface, tonalElevation = 8.dp) {
            Column(
                Modifier.padding(24.dp).width(340.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(hint, color = OureaTextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text(money(cents), color = OureaGold, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                if (askNote) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = note, onValueChange = { note = it.take(200) },
                        label = { Text(stringResource(if (noteOptional) R.string.ourea_drawer_note_optional else R.string.ourea_drawer_reason)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                NumericKeypad(
                    onNumberClick = { d -> val next = cents * 10 + d.toInt(); if (next <= 100_000_00) cents = next },
                    onBackSpace = { cents /= 10 },
                    onConfirm = { if (ok) onConfirm(cents, note.trim()) }
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.ourea_cancel)) }
                    Button(
                        onClick = { onConfirm(cents, note.trim()) }, enabled = ok, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = OureaGold, contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) { Text(stringResource(R.string.ourea_confirm)) }
                }
            }
        }
    }
}

/** The receipt printer the drawer is plugged into, and a test that pops it open. */
@Composable
private fun DrawerPrinterSetting() {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(DeviceRepository.getDrawerPrinter() ?: "") }
    var result by remember { mutableStateOf<Boolean?>(null) }
    Text(stringResource(R.string.ourea_drawer_printer), color = OureaTextPrimary, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = value,
        onValueChange = { value = it.trim(); DeviceRepository.persistDrawerPrinter(value); result = null },
        label = { Text(stringResource(R.string.ourea_drawer_printer_ip)) },
        supportingText = { Text(stringResource(R.string.ourea_drawer_printer_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { scope.launch { result = CashDrawerRepository.kick() } }, enabled = value.isNotBlank()) {
            Text(stringResource(R.string.ourea_drawer_test))
        }
        Spacer(Modifier.width(12.dp))
        when (result) {
            true -> Text(stringResource(R.string.ourea_drawer_test_ok), color = OureaGreen)
            false -> Text(stringResource(R.string.ourea_drawer_test_fail), color = OureaRed)
            null -> {}
        }
    }
}
