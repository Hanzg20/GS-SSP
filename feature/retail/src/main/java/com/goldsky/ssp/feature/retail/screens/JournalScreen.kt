package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.db.NoteEntity
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.feature.retail.ui.EmptyArt
import com.goldsky.ssp.feature.retail.ui.EmptyState
import com.goldsky.ssp.feature.retail.ui.PosTab
import com.goldsky.ssp.feature.retail.ui.glassCard
import com.goldsky.ssp.feature.retail.ui.shimmer
import com.goldsky.ssp.payment.NoteRepository
import com.goldsky.ssp.payment.ReminderRepository
import com.goldsky.ssp.payment.StaffRepository
import com.goldsky.ssp.ui.theme.GsTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * The store's Journal (手账), a manager's back-office notebook in one place: notes,
 * to-dos (reminders, with a notification on this terminal when due) and expenses --
 * the same cloud records 小海狸 keeps by voice. More store tools go here as tabs.
 * Laid out like Quick and Records: the tab row right under the staff bar, rows like
 * the sales list, a tap opens the detail.
 */
@Composable
fun JournalScreen(onScanReceipt: () -> Unit) {
    // Kept across the receipt camera and back.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            PosTab(tab == 0, stringResource(R.string.journal_tab_notes)) { tab = 0 }
            PosTab(tab == 1, stringResource(R.string.journal_tab_todo)) { tab = 1 }
            PosTab(tab == 2, stringResource(R.string.journal_tab_expenses)) { tab = 2 }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> NotesPane()
                1 -> TodoPane()
                else -> ExpensesPane(onScanReceipt)
            }
        }
    }
}

// --------------------------------------------------------------------------
// Shared by the three tabs: the sales list's look (TransactionHistoryScreen)
// --------------------------------------------------------------------------

/** One row: icon, bold title, small grey lines, a status on the right. */
@Composable
internal fun JournalRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    preview: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleDone: Boolean = false,
    onIcon: (() -> Unit)? = null,
    iconLabel: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onIcon != null) {
                // A to-do's tick: a bigger target than the bare icon, same place.
                Box(Modifier.size(28.dp).clickable(onClick = onIcon), contentAlignment = Alignment.Center) {
                    Icon(icon, iconLabel, tint = iconTint)
                }
            } else Icon(icon, null, tint = iconTint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (titleDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (titleDone) TextDecoration.LineThrough else null
                )
                preview?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        }
    }
}

/** The status on the right of a row (the sales list's StatusChip). */
@Composable
internal fun JournalChip(label: String, color: Color) {
    Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
}

/** While a list loads: placeholder rows shaped like it (as Records). */
@Composable
internal fun JournalSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        repeat(6) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).glassCard(RoundedCornerShape(12.dp), elevation = 1.dp).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(28.dp).shimmer(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.size(width = 120.dp, height = 16.dp).shimmer())
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.size(width = 160.dp, height = 11.dp).shimmer())
                }
            }
        }
    }
}

/** The add button, bottom right, on every tab. */
@Composable
internal fun BoxScope.JournalAddButton(label: String, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(16.dp)
    ) { Icon(Icons.Default.Add, label) }
}

private val stampFormat get() = SimpleDateFormat("MMM d, HH:mm", Locale.US)

// --------------------------------------------------------------------------
// Notes
// --------------------------------------------------------------------------

@Composable
private fun NotesPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<NoteEntity>?>(null) }
    // null: closed; a NoteEntity with a blank id: a new note.
    var open by remember { mutableStateOf<NoteEntity?>(null) }

    fun reload() { scope.launch { notes = NoteRepository.list(context) } }
    LaunchedEffect(Unit) { reload() }

    open?.let { note -> NoteDialog(note, onDismiss = { open = null }, onChanged = { open = null; reload() }) }

    Box(Modifier.fillMaxSize()) {
        val list = notes
        when {
            list == null -> JournalSkeleton()
            list.isEmpty() -> EmptyState(EmptyArt.RECEIPT, stringResource(R.string.journal_no_notes), stringResource(R.string.journal_add_hint),
                Modifier.align(Alignment.Center))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
            ) {
                items(list, key = { it.id }) { note ->
                    // An untitled note's title is its first line: don't show it twice.
                    val body = if (note.content.startsWith(note.title)) note.content.removePrefix(note.title).trim() else note.content
                    JournalRow(
                        icon = if (note.pinned) Icons.Default.PushPin else Icons.Default.StickyNote2,
                        title = note.title,
                        preview = body.ifBlank { null },
                        subtitle = stampFormat.format(Date(note.updatedAt)) + (note.staffName?.let { " · $it" } ?: ""),
                        trailing = if (note.synced) null else {
                            { JournalChip(stringResource(R.string.journal_not_uploaded), GsTheme.colors.warning) }
                        },
                        onClick = { open = note }
                    )
                }
            }
        }
        JournalAddButton(stringResource(R.string.journal_add_note)) { open = NoteEntity(id = "", content = "") }
    }
}

/**
 * A note opened (or a new one, [note].id blank): edit it in place; pin and delete
 * sit at the bottom like Refund in a sale's detail.
 */
@Composable
private fun NoteDialog(note: NoteEntity, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = note.id.isBlank()
    // An untitled note's title is its first line: edit the text alone then.
    var title by remember { mutableStateOf(if (!isNew && note.content.startsWith(note.title)) "" else note.title) }
    var content by remember { mutableStateOf(note.content) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch { block(); busy = false; onChanged() }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (isNew) R.string.journal_add_note else R.string.journal_edit_note),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (!isNew) IconButton(onClick = { act { NoteRepository.togglePin(context, note.id, !note.pinned) } }, enabled = !busy) {
                        Icon(
                            if (note.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            stringResource(if (note.pinned) R.string.journal_unpin else R.string.journal_pin),
                            tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!isNew) Text(
                    stampFormat.format(Date(note.updatedAt)) + (note.staffName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.journal_note_title)) }, singleLine = true)
                OutlinedTextField(content, { content = it.take(2000) }, Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    label = { Text(stringResource(R.string.journal_note_content)) })
                if (confirmDelete) Text(stringResource(R.string.journal_delete_note_q), color = GsTheme.colors.danger, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!isNew) OutlinedButton(
                        onClick = { if (confirmDelete) act { NoteRepository.delete(context, note.id) } else confirmDelete = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, GsTheme.colors.danger),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GsTheme.colors.danger)
                    ) {
                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (confirmDelete) R.string.journal_delete_confirm else R.string.journal_delete), fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            // Offline it still saves here and goes up later.
                            act {
                                if (isNew) NoteRepository.create(context, title, content, staff = StaffRepository.current)
                                else NoteRepository.update(context, note.id, title, content)
                            }
                        },
                        enabled = !busy && content.isNotBlank(),
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
// To-dos
// --------------------------------------------------------------------------

@Composable
private fun TodoPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // null while loading; failed: no connection (reminders are cloud-only).
    var todos by remember { mutableStateOf<List<ReminderRepository.Reminder>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val r = ReminderRepository.list()
            failed = r == null
            todos = r ?: emptyList()
        }
    }
    LaunchedEffect(Unit) { reload() }

    // The notification is the point of a to-do: ask once, when one is being set.
    var notifyAllowed by remember {
        mutableStateOf(android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
            context, "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> notifyAllowed = granted }

    if (adding) TodoDialog(onDismiss = { adding = false }, onSaved = { adding = false; reload() })

    Box(Modifier.fillMaxSize()) {
        val list = todos
        when {
            list == null -> JournalSkeleton()
            failed -> EmptyState(EmptyArt.BOX, stringResource(R.string.journal_offline), modifier = Modifier.align(Alignment.Center))
            list.isEmpty() -> EmptyState(EmptyArt.BOX, stringResource(R.string.journal_no_todos), stringResource(R.string.journal_add_hint),
                Modifier.align(Alignment.Center))
            else -> {
                val open = list.filter { it.doneAtMillis == null }
                val done = list.filter { it.doneAtMillis != null }
                val now = System.currentTimeMillis()
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
                ) {
                    items(open, key = { it.id }) { t ->
                        val overdue = t.dueAtMillis < now
                        JournalRow(
                            icon = Icons.Default.RadioButtonUnchecked,
                            iconLabel = stringResource(R.string.journal_mark_done),
                            onIcon = { scope.launch { if (ReminderRepository.markDone(t.id, context)) reload() } },
                            title = t.text,
                            subtitle = dueLabel(t.dueAtMillis) + (t.staffName?.let { " · $it" } ?: ""),
                            trailing = if (overdue) {
                                { JournalChip(stringResource(R.string.journal_todo_overdue), GsTheme.colors.danger) }
                            } else null
                        )
                    }
                    if (done.isNotEmpty()) {
                        item {
                            Text(stringResource(R.string.journal_todo_done_section), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
                        }
                        items(done, key = { it.id }) { t ->
                            JournalRow(
                                icon = Icons.Default.CheckCircle,
                                iconTint = GsTheme.colors.success,
                                title = t.text,
                                titleDone = true,
                                subtitle = dueLabel(t.dueAtMillis) + (t.staffName?.let { " · $it" } ?: ""),
                                trailing = { JournalChip(stringResource(R.string.journal_done), GsTheme.colors.success) }
                            )
                        }
                    }
                }
            }
        }
        JournalAddButton(stringResource(R.string.journal_add_todo)) {
            if (!notifyAllowed) askNotify.launch("android.permission.POST_NOTIFICATIONS")
            adding = true
        }
    }
}

/** "Today 15:00", "Tomorrow 09:00", else "Oct 20, 15:00" (this terminal's time zone). */
@Composable
private fun dueLabel(millis: Long): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
    val now = System.currentTimeMillis()
    return when (day.format(Date(millis))) {
        day.format(Date(now)) -> stringResource(R.string.journal_today, time)
        day.format(Date(now + 24 * 3_600_000L)) -> stringResource(R.string.journal_tomorrow, time)
        else -> stampFormat.format(Date(millis))
    }
}

@Composable
private fun TodoDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Starts at the next full hour.
    val start = remember {
        Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0) }.time
    }
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(start)) }
    var time by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.US).format(start)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val badTime = stringResource(R.string.journal_bad_time)
    val pastTime = stringResource(R.string.journal_time_past)
    val notSaved = stringResource(R.string.journal_save_failed)

    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.journal_add_todo), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface)
                OutlinedTextField(text, { text = it.take(300) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.journal_todo_text)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(date, { date = it.take(10) }, Modifier.weight(1.4f),
                        label = { Text(stringResource(R.string.journal_todo_date)) }, singleLine = true)
                    OutlinedTextField(time, { time = it.take(5) }, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.journal_todo_time)) }, singleLine = true)
                }
                error?.let { Text(it, color = GsTheme.colors.danger) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.retail_cancel))
                    }
                    Button(
                        enabled = !saving && text.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val at = runCatching {
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }.parse("$date $time")?.time
                            }.getOrNull()
                            if (at == null || !Regex("""\d{4}-\d{2}-\d{2}""").matches(date) || !Regex("""\d{1,2}:\d{2}""").matches(time)) { error = badTime; return@Button }
                            if (at <= System.currentTimeMillis()) { error = pastTime; return@Button }
                            saving = true
                            scope.launch {
                                val ok = ReminderRepository.create(context, text, at, StaffRepository.current)
                                saving = false
                                if (ok) onSaved() else error = notSaved
                            }
                        }
                    ) { Text(stringResource(R.string.journal_save), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
