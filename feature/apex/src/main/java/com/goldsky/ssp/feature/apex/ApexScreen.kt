package com.goldsky.ssp.feature.apex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.payment.ApexRepository
import com.goldsky.ssp.payment.StaffMember
import com.goldsky.ssp.ui.theme.GsTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Same brand colours as everywhere else (core/ui theme/Color.kt); this was a second, different blue.
val GoldSkyBlue = com.goldsky.ssp.ui.theme.BrandBlue
val GoldSkyCyan = com.goldsky.ssp.ui.theme.BrandCyan

/** The mascot's name in the interface language (res: apex_name -- 小海狸 / Beaver). */
fun apexName(context: android.content.Context): String = context.getString(com.goldsky.ssp.core.data.R.string.apex_name)

/**
 * Apex (小海狸), the manager's assistant. The numbers at the top are the
 * store's own (apex_store_stats, computed in SQL) and show even when no model
 * is configured; the brief and the answers come from the apex-assistant Edge
 * Function, which grounds them in those same numbers. Replaces the old
 * Insights page, whose "AI recommendations" were hard-coded text.
 */
@Composable
fun ApexScreen(staff: StaffMember) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 小海狸 answers in the terminal's interface language (Settings > Language).
    val lang = remember { com.goldsky.ssp.payment.UiLanguage.current(context) }
    fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    var loading by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf<JsonObject?>(null) }
    var brief by remember { mutableStateOf<String?>(null) }
    var modelOff by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val chat = remember { mutableStateListOf<Pair<Boolean, String>>() } // (fromUser, text)
    // An expense / reminder 小海狸 understood, waiting for the manager's OK.
    var proposal by remember { mutableStateOf<JsonObject?>(null) }
    var notifyAllowed by remember {
        mutableStateOf(android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
            context, "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> notifyAllowed = granted }
    var question by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Hold-to-talk: speech -> text in the input box (edited, then sent by hand).
    val recorder = remember { VoiceRecorder(context) }
    DisposableEffect(Unit) { onDispose { recorder.stopQuietly() } }
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    var micAllowed by remember {
        mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> micAllowed = granted }

    fun stopAndTranscribe() {
        if (!recording) return
        recording = false
        val audio = recorder.stop() ?: run {
            voiceNote = if (recorder.lastProblem == VoiceRecorder.Problem.SILENT) str(R.string.apex_didn_t_hear_anything_speak)
                        else str(R.string.apex_hold_to_talk_release_when)
            return
        }
        transcribing = true
        voiceNote = null
        scope.launch {
            when (val r = ApexRepository.transcribe(context, staff.id, audio)) {
                is ApexRepository.Result.Transcript -> question = (if (question.isBlank()) "" else "$question ") + r.text
                is ApexRepository.Result.Failed -> voiceNote = r.message
                else -> {}
            }
            transcribing = false
        }
    }

    fun load(refresh: Boolean) {
        loading = true
        scope.launch {
            when (val r = ApexRepository.brief(context, staff.id, refresh)) {
                is ApexRepository.Result.Brief -> { stats = r.stats; brief = r.text; modelOff = r.modelOff; loadError = null }
                is ApexRepository.Result.Failed -> loadError = r.message
                else -> {}
            }
            loading = false
        }
    }
    LaunchedEffect(lang) { load(refresh = false) }

    // Earlier conversation (kept in the cloud per staff member), shown above anything new.
    LaunchedEffect(Unit) {
        val r = ApexRepository.history(context, staff.id)
        if (r is ApexRepository.Result.History && r.turns.isNotEmpty()) {
            chat.addAll(0, r.turns.map { it.fromUser to it.text })
            // After the list has laid the new items out: stats + brief come first, then the chat.
            kotlinx.coroutines.delay(300)
            listState.scrollToItem(2 + chat.size - 1)
        }
    }

    // The Q2 has no back key or navigation bar: nothing closes the keyboard
    // unless the screen does -- on send, on the keyboard's Send key, on a tap
    // outside the field, and when recording starts.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun hideKeyboard() { keyboard?.hide(); focusManager.clearFocus() }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || asking) return
        hideKeyboard()
        chat.add(true to q)
        question = ""
        asking = true
        scope.launch {
            val answer = when (val r = ApexRepository.ask(context, staff.id, q)) {
                is ApexRepository.Result.Reply -> r.text
                is ApexRepository.Result.Proposal -> {
                    proposal = r.data
                    if (r.data["kind"]?.jsonPrimitive?.contentOrNull == "reminder" && !notifyAllowed) askNotify.launch("android.permission.POST_NOTIFICATIONS")
                    str(R.string.apex_please_check_the_card_below)
                }
                is ApexRepository.Result.Failed -> "⚠ ${r.message}"
                else -> ""
            }
            chat.add(false to answer)
            asking = false
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .pointerInput(Unit) { detectTapGestures(onTap = { hideKeyboard() }) }) {
        // Header: one slim row (mascot, name, what it is, refresh). Was two lines
        // with a 44dp avatar -- too much of the Q2's portrait screen.
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ApexAvatar(28.dp)
            Spacer(Modifier.width(8.dp))
            Text(apexName(context), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(str(R.string.apex_your_store_assistant), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            IconButton(onClick = { load(refresh = true) }, enabled = !loading, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = str(R.string.apex_refresh), modifier = Modifier.size(20.dp))
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                when {
                    stats != null -> StatsPanel(stats!!, onReminderDone = { id ->
                        scope.launch { if (com.goldsky.ssp.payment.ReminderRepository.markDone(id)) load(refresh = false) }
                    })
                    loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    loadError != null -> Notice("⚠ $loadError")
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(str(R.string.apex_s_brief, apexName(context)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        when {
                            loading && brief == null -> Text(str(R.string.apex_beaver_is_looking_over_today), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            modelOff -> Text(str(R.string.apex_the_ai_brief_isn_t), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            brief != null -> Text(brief!!, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp)
                            loadError != null -> Text("⚠ $loadError", color = GsTheme.colors.danger)
                        }
                    }
                }
            }
            items(chat) { (fromUser, text) -> ChatBubble(fromUser, text, lang) }
            if (asking) item { ChatBubble(false, "…", lang) }
        }

        proposal?.let { p ->
            ProposalCard(p,
                onCancel = { proposal = null; chat.add(false to str(R.string.apex_ok_cancelled)) },
                onSaved = { message -> proposal = null; chat.add(false to message); load(refresh = false) },
                staff = staff)
        }

        // Quick questions + input
        val quick = androidx.compose.ui.res.stringArrayResource(R.array.apex_quick_questions).toList()
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quick) { q -> AssistChip(onClick = { send(q) }, label = { Text(q) }, enabled = !asking) }
        }
        val note = when {
            recording -> str(R.string.apex_listening_release_when_done)
            transcribing -> str(R.string.apex_transcribing)
            else -> voiceNote
        }
        if (note != null) {
            Text(note, Modifier.padding(start = 16.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = if (recording) GsTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Press and hold to record; release to transcribe.
            Surface(
                shape = CircleShape,
                color = if (recording) GsTheme.colors.danger else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(52.dp).pointerInput(micAllowed, asking, transcribing) {
                    detectTapGestures(onPress = {
                        if (!micAllowed) { askMic.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                        if (asking || transcribing) return@detectTapGestures
                        hideKeyboard()
                        recording = recorder.start()
                        if (!recording) voiceNote = str(R.string.apex_couldn_t_open_the_microphone)
                        tryAwaitRelease()
                        stopAndTranscribe()
                    })
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (transcribing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Mic, contentDescription = str(R.string.apex_hold_to_talk),
                        tint = if (recording) Color.White else MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = question,
                onValueChange = { question = it.take(500) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(str(R.string.apex_ask_beaver)) },
                maxLines = 3,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send(question) })
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { send(question) }, enabled = question.isNotBlank() && !asking) {
                Icon(Icons.Default.Send, contentDescription = str(R.string.apex_send))
            }
        }
    }
}

/** 小海狸's avatar -- the same character as JustWeDo's support beaver (public/mascots/beaver.png). */
@Composable
fun ApexAvatar(size: androidx.compose.ui.unit.Dp) {
    Image(
        painterResource(R.drawable.apex_beaver), contentDescription = null,
        modifier = Modifier.size(size).clip(CircleShape).background(Color.White)
    )
}

@Composable
private fun ChatBubble(fromUser: Boolean, text: String, lang: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!fromUser) { ApexAvatar(28.dp); Spacer(Modifier.width(6.dp)) }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Text(
                text, Modifier.padding(12.dp, 8.dp),
                color = if (fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                lineHeight = 21.sp
            )
        }
    }
}

@Composable
private fun Notice(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))

private fun money(cents: Double) = "$%.2f".format(cents / 100.0)
private fun JsonObject.num(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** The store's live numbers -- straight from the database, no model involved. */
@Composable
private fun StatsPanel(stats: JsonObject, onReminderDone: (String) -> Unit) {
    val context = LocalContext.current
    fun str(id: Int, vararg args: Any): String = context.getString(id, *args)
    val today = stats.obj("today")
    val lastWeek = stats.obj("same_time_last_week")
    val total = today?.num("total_cents") ?: 0.0
    val before = lastWeek?.num("total_cents") ?: 0.0
    val change = if (before > 0) ((total - before) / before * 100).toInt() else null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(str(R.string.apex_sales_today), money(total), Modifier.weight(1f),
                sub = when {
                    change == null -> str(R.string.apex_no_sales_same_time_last)
                    change >= 0 -> str(R.string.apex_vs_last_week, change)
                    else -> str(R.string.apex_vs_last_week_2, change)
                },
                subColor = when { change == null -> null; change >= 0 -> GsTheme.colors.success; else -> GsTheme.colors.danger })
            StatCard(str(R.string.apex_sales_avg), "${(today?.num("sales") ?: 0.0).toInt()} · ${money(today?.num("avg_basket_cents") ?: 0.0)}", Modifier.weight(1f),
                sub = str(R.string.apex_cash_card, money(today?.num("cash_cents") ?: 0.0), money(today?.num("card_cents") ?: 0.0)))
        }
        val peaks = stats.arr("hourly_last_28_days").sortedByDescending { it.num("avg_sales") }.take(3).filter { it.num("avg_sales") > 0 }
        if (peaks.isNotEmpty()) {
            StatCard(str(R.string.apex_busiest_hours_28_days),
                peaks.joinToString("  ") { "%02d:00".format(it.num("hour").toInt()) }, Modifier.fillMaxWidth())
        }
        val top = stats.arr("top_items_7_days").take(5)
        if (top.isNotEmpty()) {
            StatCard(str(R.string.apex_best_sellers_7_days),
                top.joinToString("\n") { "${it["name"]?.jsonPrimitive?.contentOrNull} × ${it.num("qty").toInt()}" }, Modifier.fillMaxWidth(), small = true)
        }
        val due = stats.arr("reminders_due")
        if (due.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(str(R.string.apex_to_dos_due_today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    due.forEach { r ->
                        val overdue = r["overdue"]?.jsonPrimitive?.contentOrNull == "true"
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("${r["due"]?.jsonPrimitive?.contentOrNull?.takeLast(5) ?: ""}  ${r["text"]?.jsonPrimitive?.contentOrNull ?: ""}",
                                Modifier.weight(1f), color = if (overdue) GsTheme.colors.danger else MaterialTheme.colorScheme.onSurface)
                            TextButton(onClick = { r["id"]?.jsonPrimitive?.contentOrNull?.let(onReminderDone) }) { Text(str(R.string.apex_done)) }
                        }
                    }
                }
            }
        }
        // Loss prevention first: rule-based flags from the database, worded here.
        val flags = stats.obj("loss_signals")?.arr("flags").orEmpty()
        if (flags.isNotEmpty()) {
            StatCard(str(R.string.apex_worth_a_look_last_7),
                flags.joinToString("\n") { "• " + lossFlagText(context, it) }, Modifier.fillMaxWidth(), small = true,
                accent = GsTheme.colors.warning)
        }
        val perishable = (stats["perishable_not_sold_3_days"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (perishable.isNotEmpty()) {
            StatCard(str(R.string.apex_fresh_items_unsold_for_3),
                perishable.take(8).joinToString("、"), Modifier.fillMaxWidth(), small = true)
        }
        val unsold = (stats["not_sold_14_days"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }.orEmpty()
        if (unsold.isNotEmpty()) {
            StatCard(str(R.string.apex_not_sold_in_14_days),
                str(R.string.apex_items, unsold.size), Modifier.fillMaxWidth(),
                sub = unsold.take(5).joinToString("、"))
        }
    }
}

/** One loss-prevention flag (apex_loss_signals) in plain words. Never an accusation: "check this". */
private fun lossFlagText(context: android.content.Context, flag: JsonObject): String {
    val who = flag["staff"]?.jsonPrimitive?.contentOrNull ?: ""
    val d = flag.obj("detail")
    fun n(k: String) = d?.num(k) ?: 0.0
    fun i(k: String) = n(k).toInt()
    return when (flag["kind"]?.jsonPrimitive?.contentOrNull) {
        "HIGH_REFUND_RATE" -> context.getString(R.string.apex_flag_high_refund_rate, who, i("refunds"), n("rate_pct").toString(), n("store_rate_pct").toString(), money(n("refund_cents")))
        "SELF_APPROVED" -> context.getString(R.string.apex_flag_self_approved, who, i("count"))
        "CASH_REFUNDS" -> context.getString(R.string.apex_flag_cash_refunds, who, i("count"))
        "HIGH_CASH_SHARE" -> context.getString(R.string.apex_flag_high_cash_share, who, n("cash_share_pct").toString(), n("store_cash_share_pct").toString())
        "DECLINES_TODAY" -> context.getString(R.string.apex_flag_declines_today, i("count"))
        else -> flag["kind"]?.jsonPrimitive?.contentOrNull ?: ""
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier, sub: String? = null, subColor: Color? = null, small: Boolean = false, accent: Color? = null) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(if (accent != null) 1.dp else 0.5.dp, accent ?: MaterialTheme.colorScheme.outline), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = if (small) 15.sp else 22.sp, fontWeight = if (small) FontWeight.Medium else FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, lineHeight = if (small) 21.sp else 28.sp)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = subColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 小海狸's understanding of a spoken expense or reminder, editable, saved only
 * on 确认. Fields the server flagged (amount re-read from the words didn't match,
 * date out of range, no time) are marked "请核对" and must be filled in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProposalCard(p: JsonObject, staff: StaffMember, onCancel: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun str(id: Int, vararg args: Any): String = context.getString(id, *args)
    fun field(k: String) = p[k]?.jsonPrimitive?.contentOrNull ?: ""
    val check = (p["check"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val isExpense = field("kind") == "expense"
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // expense fields
    var vendor by remember(p) { mutableStateOf(field("vendor")) }
    var amount by remember(p) { mutableStateOf(p["amount"]?.jsonPrimitive?.doubleOrNull?.let { "%.2f".format(it) } ?: "") }
    var category by remember(p) { mutableStateOf(field("category").ifBlank { "OTHER" }) }
    var spentOn by remember(p) { mutableStateOf(field("spentOn")) }
    var note by remember(p) { mutableStateOf(field("note")) }
    // reminder fields (store-local date and time)
    val dueMillis = field("dueAt").takeIf { it.isNotBlank() }?.let { com.goldsky.ssp.payment.DayReportRepository.parseIsoMillis(it) }
    var text by remember(p) { mutableStateOf(field("text")) }
    var dueDate by remember(p) { mutableStateOf(dueMillis?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it)) } ?: "") }
    var dueTime by remember(p) { mutableStateOf(dueMillis?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(it)) } ?: "") }

    @Composable
    fun flagged(field: String, label: String): @Composable () -> Unit = {
        Text(if (field in check) "$label · ${str(R.string.apex_please_check)}" else label,
            color = if (field in check) GsTheme.colors.warning else androidx.compose.ui.graphics.Color.Unspecified)
    }

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isExpense) str(R.string.apex_record_an_expense) else str(R.string.apex_set_a_reminder),
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (isExpense) {
                val said = listOf(field("amountWords"), field("dateWords")).filter { it.isNotBlank() }.joinToString("，")
                if (said.isNotBlank()) Text(str(R.string.apex_you_said) + said, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, Modifier.weight(1f),
                        label = flagged("amount", str(R.string.apex_amount)), singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    OutlinedTextField(spentOn, { spentOn = it.take(10) }, Modifier.weight(1f), label = flagged("date", str(R.string.apex_date)), singleLine = true)
                }
                OutlinedTextField(vendor, { vendor = it.take(120) }, Modifier.fillMaxWidth(), label = { Text(str(R.string.apex_paid_to)) }, singleLine = true)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(com.goldsky.ssp.payment.ExpenseRepository.CATEGORIES) { c ->
                        FilterChip(selected = category == c, onClick = { category = c },
                            label = { Text(com.goldsky.ssp.payment.ExpenseRepository.label(context, c)) })
                    }
                }
                OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(str(R.string.apex_note)) }, singleLine = true)
            } else {
                OutlinedTextField(text, { text = it.take(300) }, Modifier.fillMaxWidth(), label = { Text(str(R.string.apex_what)) })
                if (field("whenWords").isNotBlank()) Text(str(R.string.apex_you_said_2) + field("whenWords"), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(dueDate, { dueDate = it.take(10) }, Modifier.weight(1f), label = flagged("due", str(R.string.apex_date_yyyy_mm_dd)), singleLine = true)
                    OutlinedTextField(dueTime, { dueTime = it.take(5) }, Modifier.weight(1f), label = flagged("due", str(R.string.apex_time_hh_mm)), singleLine = true)
                }
            }
            error?.let { Text(it, color = GsTheme.colors.danger, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel, enabled = !saving) { Text(str(R.string.apex_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !saving, onClick = {
                    error = null
                    if (isExpense) {
                        val cents = amount.toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
                        if (cents == null || cents <= 0) { error = str(R.string.apex_enter_the_amount); return@Button }
                        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(spentOn)) { error = str(R.string.apex_date_as_2026_10_10); return@Button }
                        saving = true
                        scope.launch {
                            val online = com.goldsky.ssp.payment.ExpenseRepository.record(context, vendor, category, cents, spentOn, note, "voice", staff)
                            saving = false
                            val what = "${com.goldsky.ssp.payment.ExpenseRepository.label(context, category)} $${"%.2f".format(cents / 100.0)}" +
                                (if (vendor.isNotBlank()) " · $vendor" else "")
                            onSaved(if (online) str(R.string.apex_booked, what) else str(R.string.apex_saved_offline_uploads_when_back, what))
                        }
                    } else {
                        val at = runCatching {
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply { isLenient = false }.parse("$dueDate $dueTime")?.time
                        }.getOrNull()
                        if (text.isBlank()) { error = str(R.string.apex_enter_what_to_do); return@Button }
                        if (at == null || at < System.currentTimeMillis()) { error = str(R.string.apex_enter_a_future_date_and); return@Button }
                        saving = true
                        scope.launch {
                            val ok = com.goldsky.ssp.payment.ReminderRepository.create(context, text, at, staff)
                            saving = false
                            if (ok) onSaved(str(R.string.apex_ok_i_ll_remind_you, dueDate, dueTime, text))
                            else error = str(R.string.apex_couldn_t_save_check_the)
                        }
                    }
                }) { Text(if (saving) str(R.string.apex_saving) else str(R.string.apex_confirm), fontWeight = FontWeight.Bold) }
            }
        }
    }
}
