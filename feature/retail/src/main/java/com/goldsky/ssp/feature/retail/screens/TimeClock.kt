package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.feature.retail.ui.ManagerApprovalDialog
import com.goldsky.ssp.payment.StaffMember
import com.goldsky.ssp.payment.StaffRepository
import com.goldsky.ssp.payment.TimeClockRepository
import com.goldsky.ssp.ui.theme.GsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun money(cents: Long) = "$%.2f".format(cents / 100.0)
private fun clock(millis: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

/** The server's refusals in words; unknown codes as they are. */
@Composable
private fun clockMessage(code: String?): String = when (code) {
    null -> stringResource(R.string.retail_can_t_reach_the_server)
    "already_in" -> stringResource(R.string.clock_err_already_in)
    "not_in" -> stringResource(R.string.clock_err_not_in)
    "bad_time" -> stringResource(R.string.clock_err_bad_time)
    "too_long" -> stringResource(R.string.clock_err_too_long)
    "note_required" -> stringResource(R.string.clock_err_note)
    "already_on_clock" -> stringResource(R.string.clock_err_already_in)
    else -> code
}

/** Minutes on the clock since [since], ticking each minute. */
@Composable
private fun minutesSince(since: Long?): Long? {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (since != null) { delay(30_000); now = System.currentTimeMillis() } }
    return since?.let { ((now - it) / 60_000).coerceAtLeast(0) }
}

/**
 * The signed-in person's clock: "Clock in", or the time on the clock (tap to clock
 * out). [compact] = an icon with the hours under it (Ourea's sidebar).
 */
@Composable
fun ClockButton(staff: StaffMember, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val version by TimeClockRepository.version.collectAsState()
    LaunchedEffect(staff.id) { TimeClockRepository.refresh() }
    val since = remember(staff.id, version) { TimeClockRepository.clockedInAt(staff.id) }
    val minutes = minutesSince(since)
    val color = if (since != null) GsTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant

    if (compact) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { open = true }.padding(4.dp)) {
            Icon(Icons.Default.Schedule, stringResource(R.string.clock_title), tint = color)
            Text(minutes?.let { TimeClockRepository.hours(it) } ?: stringResource(R.string.clock_off_short),
                style = MaterialTheme.typography.labelSmall, color = color)
        }
    } else {
        OutlinedButton(
            onClick = { open = true },
            border = BorderStroke(1.dp, color),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp)
        ) {
            Icon(Icons.Default.Schedule, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(minutes?.let { TimeClockRepository.hours(it) } ?: stringResource(R.string.clock_in),
                fontWeight = FontWeight.Bold, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (open) ClockDialog(staff, onDone = {}, onDismiss = { open = false })
}

/** Clock in or out, with what the terminal knows (since when, punches waiting for the network). */
@Composable
fun ClockDialog(staff: StaffMember, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val since = remember { TimeClockRepository.clockedInAt(staff.id) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    val errorText = error?.let { clockMessage(errorCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Schedule, null) },
        title = { Text(staff.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (since != null) stringResource(R.string.clock_on_since, clock(since), TimeClockRepository.hours((System.currentTimeMillis() - since) / 60_000))
                    else stringResource(R.string.clock_off_now)
                )
                errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val r = TimeClockRepository.punch(context, staff, clockIn = since == null)
                        busy = false
                        onDone()
                        if (r.ok) {
                            android.widget.Toast.makeText(context,
                                context.getString(if (since == null) R.string.clock_in_done else R.string.clock_out_done, staff.name) +
                                    if (r.queued) "\n" + context.getString(R.string.clock_queued) else "",
                                android.widget.Toast.LENGTH_LONG).show()
                            onDismiss()
                        } else { errorCode = r.message; error = r.message ?: "" }
                    }
                },
                colors = if (since != null) ButtonDefaults.buttonColors(containerColor = GsTheme.colors.danger) else ButtonDefaults.buttonColors()
            ) { Text(stringResource(if (since != null) R.string.clock_out else R.string.clock_in), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_cancel_2)) } }
    )
}

/**
 * Right after sign-in: "clock in?" for someone not on the clock -- once per person
 * per day on this terminal (a "Not now" isn't asked again until tomorrow), so a store
 * that doesn't use the time clock is asked once a day at most.
 */
@Composable
fun ClockInPrompt(staff: StaffMember) {
    val context = LocalContext.current
    var ask by remember(staff.id) { mutableStateOf(false) }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val prefs = remember { context.getSharedPreferences("time_clock_prompt", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(staff.id) {
        if (prefs.getString(staff.id, null) == today) return@LaunchedEffect
        TimeClockRepository.refresh()
        ask = TimeClockRepository.clockedInAt(staff.id) == null
    }
    if (!ask) return
    fun asked() { prefs.edit().putString(staff.id, today).apply(); ask = false }
    ClockDialog(staff, onDone = {}, onDismiss = { asked() })
}

/**
 * The Day report's staff part, for the whole store and the report's day: each
 * person's hours (on the clock now marked), the tips on their own sales and their
 * share under the store's rule. Managers change the rule and fix shifts (a
 * forgotten clock-out, a missed shift) -- others need a manager's PIN for a fix.
 */
@Composable
fun StaffHoursCard(dayMillis: Long) {
    val scope = rememberCoroutineScope()
    val day = remember(dayMillis) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayMillis)) }
    var data by remember(day) { mutableStateOf<TimeClockRepository.DayTips?>(null) }
    var failed by remember(day) { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var person by remember { mutableStateOf<TimeClockRepository.StaffDay?>(null) }
    var choosingRule by remember { mutableStateOf(false) }
    var ruleError by remember { mutableStateOf<String?>(null) }
    val me = StaffRepository.current

    LaunchedEffect(day, reload) {
        val d = TimeClockRepository.day(day)
        failed = d == null
        if (d != null) data = d
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clock_card_title), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            if (failed && data == null) {
                Text(stringResource(R.string.retail_can_t_reach_the_server), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val d = data ?: return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.clock_tips_total, money(d.tips_cents)), modifier = Modifier.weight(1f))
                TextButton(onClick = { choosingRule = true }, enabled = me?.isManager == true) {
                    Text(ruleLabel(d.rule))
                }
            }
            ruleError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (d.staff.isEmpty()) {
                Text(stringResource(R.string.clock_nobody), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                Row {
                    Text(stringResource(R.string.clock_col_staff), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.clock_col_hours), Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.clock_col_own), Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.clock_col_share), Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                d.staff.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { person = s }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name + if (s.on_clock) " ●" else "", Modifier.weight(1f), fontWeight = FontWeight.Medium,
                            color = if (s.on_clock) GsTheme.colors.success else MaterialTheme.colorScheme.onSurface)
                        Text(TimeClockRepository.hours(s.minutes), Modifier.width(56.dp))
                        Text(money(s.own_tips_cents), Modifier.width(72.dp))
                        Text(money(s.share_cents), Modifier.width(72.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (d.unassigned_cents > 0) {
                Text(stringResource(R.string.clock_unassigned, money(d.unassigned_cents)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.clock_card_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (choosingRule) {
        val rules = listOf(TimeClockRepository.OWN, TimeClockRepository.POOL_HOURS, TimeClockRepository.POOL_EQUAL)
        AlertDialog(
            onDismissRequest = { choosingRule = false },
            title = { Text(stringResource(R.string.clock_rule_title)) },
            text = {
                Column {
                    rules.forEach { r ->
                        Row(Modifier.fillMaxWidth().clickable {
                            choosingRule = false
                            scope.launch {
                                val res = TimeClockRepository.setRule(r, me)
                                ruleError = if (res.ok) null else res.message ?: "offline"
                                reload++
                            }
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = data?.rule == r, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(ruleLabel(r), fontWeight = FontWeight.Bold)
                                Text(ruleHint(r), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingRule = false }) { Text(stringResource(R.string.retail_cancel_2)) } }
        )
    }

    person?.let { p ->
        ShiftsDialog(
            person = p,
            shifts = data?.shifts.orEmpty().filter { it.staff_id == p.staff_id },
            dayMillis = dayMillis,
            onChanged = { reload++ },
            onDismiss = { person = null }
        )
    }
}

@Composable
private fun ruleLabel(rule: String) = stringResource(when (rule) {
    TimeClockRepository.POOL_HOURS -> R.string.clock_rule_hours
    TimeClockRepository.POOL_EQUAL -> R.string.clock_rule_equal
    else -> R.string.clock_rule_own
})

@Composable
private fun ruleHint(rule: String) = stringResource(when (rule) {
    TimeClockRepository.POOL_HOURS -> R.string.clock_rule_hours_hint
    TimeClockRepository.POOL_EQUAL -> R.string.clock_rule_equal_hint
    else -> R.string.clock_rule_own_hint
})

/** "HH:mm" on the day of [dayMillis] (local time); null when not a time. */
internal fun timeOnDay(dayMillis: Long, hhmm: String): Long? {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm.trim()) ?: return null
    val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
    if (h > 23 || min > 59) return null
    return Calendar.getInstance().apply {
        timeInMillis = dayMillis
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** One person's shifts on the day; a manager fixes one or adds a missed one. */
@Composable
private fun ShiftsDialog(
    person: TimeClockRepository.StaffDay,
    shifts: List<TimeClockRepository.Shift>,
    dayMillis: Long,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    // null = the list; a shift = fixing it; EMPTY = adding a missed one.
    var editing by remember { mutableStateOf<TimeClockRepository.Shift?>(null) }
    val adding = remember { TimeClockRepository.Shift("", person.staff_id, person.name, "") }

    val e = editing
    val staffId = person.staff_id
    if (e != null && staffId != null) {
        FixShiftDialog(e.takeIf { it.id.isNotEmpty() }, staffId, person.name, dayMillis,
            onDone = { editing = null; onChanged(); onDismiss() }, onDismiss = { editing = null })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(person.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shifts.isEmpty()) Text(stringResource(R.string.clock_no_shifts))
                shifts.forEach { s ->
                    val inMs = TimeClockRepository.parseIso(s.clock_in_at)
                    val outMs = TimeClockRepository.parseIso(s.clock_out_at)
                    Row(Modifier.fillMaxWidth().clickable { editing = s }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((inMs?.let(::clock) ?: "?") + " – " + (outMs?.let(::clock) ?: stringResource(R.string.clock_still_on)), fontWeight = FontWeight.Bold)
                            s.fixed_note?.let { Text(stringResource(R.string.clock_fixed_by, s.fixed_by_name ?: "?", it), style = MaterialTheme.typography.bodySmall) }
                        }
                        Icon(Icons.Default.Edit, stringResource(R.string.clock_fix))
                    }
                }
            }
        },
        confirmButton = { if (person.staff_id != null) TextButton(onClick = { editing = adding }) { Text(stringResource(R.string.clock_add_missed)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_close)) } }
    )
}

/** Fix [shift] (null = add a missed one): times as HH:mm on the shift's day, a reason, a manager. */
@Composable
private fun FixShiftDialog(
    shift: TimeClockRepository.Shift?,
    staffId: String,
    name: String,
    dayMillis: Long,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val inMs0 = shift?.let { TimeClockRepository.parseIso(it.clock_in_at) }
    val outMs0 = shift?.let { TimeClockRepository.parseIso(it.clock_out_at) }
    val baseDay = inMs0 ?: dayMillis
    var inText by remember { mutableStateOf(inMs0?.let(::clock) ?: "") }
    var outText by remember { mutableStateOf(outMs0?.let(::clock) ?: "") }
    var note by remember { mutableStateOf("") }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var showError by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val inMs = timeOnDay(baseDay, inText)
    // A clock-out before the clock-in time is the next morning (a night shift).
    val outMs = timeOnDay(baseDay, outText)?.let { if (inMs != null && it < inMs) it + 24 * 3600_000L else it }
    val valid = inMs != null && note.isNotBlank() && (outText.isBlank() && shift != null || outMs != null)

    fun save(manager: StaffMember) {
        busy = true
        scope.launch {
            val r = TimeClockRepository.fix(shift?.id, staffId, inMs!!, if (outText.isBlank()) null else outMs, note, manager)
            busy = false
            if (r.ok) onDone() else { errorCode = r.message; showError = true }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (shift == null) R.string.clock_add_missed else R.string.clock_fix) + " · " + name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(inText, { inText = it.take(5) }, label = { Text(stringResource(R.string.clock_in_time)) }, singleLine = true,
                    isError = inText.isNotBlank() && inMs == null, placeholder = { Text("09:00") })
                OutlinedTextField(outText, { outText = it.take(5) }, label = { Text(stringResource(R.string.clock_out_time)) }, singleLine = true,
                    isError = outText.isNotBlank() && outMs == null, placeholder = { Text("17:30") },
                    supportingText = { if (shift != null) Text(stringResource(R.string.clock_out_blank_hint)) })
                OutlinedTextField(note, { note = it.take(200) }, label = { Text(stringResource(R.string.clock_reason)) }, singleLine = true)
                if (showError) Text(clockMessage(errorCode), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = valid && !busy, onClick = {
                val me = StaffRepository.current
                if (me?.isManager == true) save(me) else approving = true
            }) { Text(stringResource(R.string.clock_save), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_cancel_2)) } }
    )

    if (approving) {
        ManagerApprovalDialog(
            action = stringResource(R.string.clock_fix_action, name),
            onApproved = { approving = false; save(it) },
            onDismiss = { approving = false }
        )
    }
}
