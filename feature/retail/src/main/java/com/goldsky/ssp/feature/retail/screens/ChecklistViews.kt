package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.feature.retail.ui.EmptyArt
import com.goldsky.ssp.feature.retail.ui.EmptyState
import com.goldsky.ssp.payment.ChecklistRepository
import com.goldsky.ssp.payment.ChecklistRepository.Checklist
import com.goldsky.ssp.payment.StaffRepository
import com.goldsky.ssp.ui.theme.GsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/** Minutes since midnight of "HH:mm"; null if unreadable. */
private fun minutesOf(hhmm: String): Int? =
    hhmm.split(":").takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { hh * 60 + it } } }

private fun nowMinutes(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

/** Due now: its time has come today and steps are left. */
internal fun Checklist.isDueNow(): Boolean = !complete && items.isNotEmpty() && (minutesOf(due_time) ?: 0) <= nowMinutes()

/** Ticks or unticks a step; on success hands back the checklist with that step changed (no reload flicker). */
private suspend fun tickStep(list: Checklist, step: ChecklistRepository.Step, done: Boolean): Checklist? {
    val id = step.id ?: return null
    val staff = StaffRepository.current
    if (!ChecklistRepository.tick(id, done, staff).ok) return null
    val stamp = if (done) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()) else null
    return list.copy(items = list.items.map { if (it.id == id) it.copy(done_at = stamp, staff_name = if (done) staff?.name else null) else it })
}

// --------------------------------------------------------------------------
// The Journal's Checklists tab (managers): today's lists, and the editor
// --------------------------------------------------------------------------

@Composable
internal fun ChecklistsPane() {
    val scope = rememberCoroutineScope()
    var today by remember { mutableStateOf<List<Checklist>?>(null) }
    var failed by remember { mutableStateOf(false) }
    // The editor: null closed; a Checklist with a blank id = a new one.
    var editing by remember { mutableStateOf<Checklist?>(null) }
    // Checklists that don't run today still need editing: the full list, loaded on demand.
    var showAll by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val r = ChecklistRepository.today()
            failed = r == null
            today = r ?: emptyList()
        }
    }
    LaunchedEffect(Unit) { reload() }

    editing?.let { c -> ChecklistEditor(c, onDismiss = { editing = null }, onSaved = { editing = null; reload() }) }
    if (showAll) AllChecklistsDialog(onDismiss = { showAll = false }, onEdit = { showAll = false; editing = it })

    Box(Modifier.fillMaxSize()) {
        val list = today
        when {
            list == null -> JournalSkeleton()
            failed -> EmptyState(EmptyArt.BOX, stringResource(R.string.journal_offline_checklists), modifier = Modifier.align(Alignment.Center))
            list.isEmpty() -> EmptyState(EmptyArt.BOX, stringResource(R.string.checklist_none_today), stringResource(R.string.checklist_none_hint),
                Modifier.align(Alignment.Center))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
            ) {
                items(list, key = { it.id }) { c ->
                    ChecklistCard(
                        c,
                        onChanged = { updated -> today = today?.map { if (it.id == updated.id) updated else it } },
                        onEdit = { editing = c }
                    )
                }
            }
        }
        // All checklists (incl. other weekdays'): bottom left, the add button bottom right.
        TextButton(onClick = { showAll = true }, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(stringResource(R.string.checklist_all), fontWeight = FontWeight.Bold)
        }
        JournalAddButton(stringResource(R.string.checklist_new)) {
            editing = Checklist(id = "", name = "", due_time = "09:00", items = listOf(ChecklistRepository.Step(text = "")))
        }
    }
}

/** One of today's checklists: header (name, time, n/m, status) and its steps to tick. [onEdit] null: no editing (staff). */
@Composable
internal fun ChecklistCard(c: Checklist, onChanged: (Checklist) -> Unit, onEdit: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    val due = c.isDueNow()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FactCheck, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.checklist_due_at, c.due_time) + " · ${c.doneCount}/${c.items.size}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    c.complete -> JournalChip(stringResource(R.string.journal_done), GsTheme.colors.success)
                    due -> JournalChip(stringResource(R.string.checklist_due_now), GsTheme.colors.danger)
                }
                if (onEdit != null) IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, stringResource(R.string.checklist_edit), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onEdit == null) Spacer(Modifier.width(12.dp))
            }
            Divider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            c.items.forEach { step ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                        busy = step.id
                        scope.launch { tickStep(c, step, !step.done)?.let(onChanged); busy = null }
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy == step.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    else Icon(
                        if (step.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                        tint = if (step.done) GsTheme.colors.success else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.text,
                            color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (step.done) TextDecoration.LineThrough else null)
                        if (step.done) Text(
                            listOfNotNull(step.done_at?.let { stamp -> com.goldsky.ssp.payment.DayReportRepository.parseIsoMillis(stamp)
                                ?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it)) } }, step.staff_name).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Every checklist, including those that don't run today: pick one to edit. */
@Composable
private fun AllChecklistsDialog(onDismiss: () -> Unit, onEdit: (Checklist) -> Unit) {
    var all by remember { mutableStateOf<List<Checklist>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { val r = ChecklistRepository.all(); failed = r == null; all = r ?: emptyList() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.checklist_all), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface)
                val list = all
                when {
                    list == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    failed -> Text(stringResource(R.string.journal_offline_checklists), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    list.isEmpty() -> Text(stringResource(R.string.checklist_none_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        list.forEach { c ->
                            JournalRow(
                                icon = Icons.Default.FactCheck,
                                title = c.name,
                                subtitle = stringResource(R.string.checklist_due_at, c.due_time) + " · " + daysLabel(c.days) +
                                    " · " + stringResource(R.string.checklist_steps, c.items.size),
                                trailing = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = { onEdit(c) }
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.retail_cancel)) }
            }
        }
    }
}

/** "Every day", or the weekdays: "Mon Tue Wed". */
@Composable
private fun daysLabel(days: Int): String {
    if (days == ChecklistRepository.EVERY_DAY) return stringResource(R.string.checklist_every_day)
    val names = weekdayNames()
    return (0..6).filter { days shr it and 1 == 1 }.joinToString(" ") { names[it] }
}

@Composable
private fun weekdayNames(): List<String> = listOf(
    R.string.checklist_mon, R.string.checklist_tue, R.string.checklist_wed, R.string.checklist_thu,
    R.string.checklist_fri, R.string.checklist_sat, R.string.checklist_sun
).map { stringResource(it) }

/** Create ([c].id blank) or edit a checklist: name, time, weekdays, steps in order; remove. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistEditor(c: Checklist, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isNew = c.id.isBlank()
    var name by remember { mutableStateOf(c.name) }
    var time by remember { mutableStateOf(c.due_time) }
    var days by remember { mutableIntStateOf(c.days) }
    val steps = remember { c.items.map { it.copy(done_at = null, staff_name = null) }.toMutableStateList() }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val messages = mapOf(
        "name_taken" to stringResource(R.string.checklist_err_name_taken),
        "bad_time" to stringResource(R.string.journal_bad_time),
        null to stringResource(R.string.journal_save_failed)
    )
    val fallback = stringResource(R.string.checklist_err_incomplete)

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(if (isNew) R.string.checklist_new else R.string.checklist_edit),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, Modifier.weight(1.6f),
                        label = { Text(stringResource(R.string.checklist_name)) }, singleLine = true)
                    OutlinedTextField(time, { time = it.take(5) }, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.journal_todo_time)) }, singleLine = true)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    weekdayNames().forEachIndexed { i, label ->
                        val on = days shr i and 1 == 1
                        FilterChip(selected = on, onClick = {
                            val next = days xor (1 shl i)
                            if (next != 0) days = next
                        }, label = { Text(label) })
                    }
                }
                Text(stringResource(R.string.checklist_steps_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                steps.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(s.text, { steps[i] = s.copy(text = it.take(200)) }, Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.checklist_step_hint)) }, singleLine = true)
                        IconButton(onClick = { steps.removeAt(i) }, enabled = steps.size > 1) {
                            Icon(Icons.Default.Close, stringResource(R.string.journal_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { steps.add(ChecklistRepository.Step(text = "")) }) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.checklist_add_step))
                }
                error?.let { Text(it, color = GsTheme.colors.danger) }
                if (confirmRemove) Text(stringResource(R.string.checklist_remove_q), color = GsTheme.colors.danger, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!isNew) OutlinedButton(
                        onClick = {
                            if (!confirmRemove) { confirmRemove = true; return@OutlinedButton }
                            busy = true
                            scope.launch {
                                val r = ChecklistRepository.remove(c.id)
                                busy = false
                                if (r.ok) onSaved() else error = messages[r.message] ?: fallback
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, GsTheme.colors.danger),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GsTheme.colors.danger)
                    ) { Text(stringResource(if (confirmRemove) R.string.journal_delete_confirm else R.string.journal_delete), fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = ChecklistRepository.save(c.id.ifBlank { null }, name, time, days, steps, StaffRepository.current)
                                busy = false
                                if (r.ok) onSaved() else error = messages[r.message] ?: fallback
                            }
                        },
                        enabled = !busy && name.isNotBlank() && steps.any { it.text.isNotBlank() },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.journal_save), fontWeight = FontWeight.Bold) }
                }
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.retail_cancel))
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// Every staff member: a line on the checkout screen while a checklist is due
// --------------------------------------------------------------------------

/**
 * Shown above Quick's tabs while one of today's checklists is due and not done
 * ("Opening checklist · 2/5 · Tap to do it"); tapping opens it to tick. Checks
 * again every minute; shows nothing offline or when all is done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueChecklistBanner(modifier: Modifier = Modifier) {
    var today by remember { mutableStateOf<List<Checklist>>(emptyList()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            ChecklistRepository.today()?.let { today = it }
            delay(60_000)
            tick++
        }
    }
    // Re-read each minute so a checklist turns due on time, even without a reload.
    val due = remember(today, tick) { today.filter { it.isDueNow() } }

    due.firstOrNull()?.let { c ->
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            onClick = { openId = c.id }
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FactCheck, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${c.name} · ${c.doneCount}/${c.items.size}" + if (due.size > 1) "  (+${due.size - 1})" else "",
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f)
                )
                Text(stringResource(R.string.checklist_do_it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }

    today.find { it.id == openId }?.let { c ->
        Dialog(onDismissRequest = { openId = null }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(12.dp).fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    ChecklistCard(c, onChanged = { updated -> today = today.map { if (it.id == updated.id) updated else it } })
                    TextButton(onClick = { openId = null }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.checklist_close), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
