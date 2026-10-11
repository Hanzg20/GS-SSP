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

/** The mascot's name, by language: 小海狸 / Beaver (one place to rename it). */
fun apexName(lang: String) = if (lang == "zh") "小海狸" else "Beaver"

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
    var lang by remember { mutableStateOf(ApexRepository.language(context)) }
    fun t(zh: String, en: String) = if (lang == "zh") zh else en

    var loading by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf<JsonObject?>(null) }
    var brief by remember { mutableStateOf<String?>(null) }
    var modelOff by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val chat = remember { mutableStateListOf<Pair<Boolean, String>>() } // (fromUser, text)
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
            voiceNote = if (recorder.lastProblem == VoiceRecorder.Problem.SILENT) t("没听到声音，请靠近一点再说", "Didn't hear anything -- speak a little closer")
                        else t("按住说话，松开结束", "Hold to talk, release when done")
            return
        }
        transcribing = true
        voiceNote = null
        scope.launch {
            when (val r = ApexRepository.transcribe(staff.id, lang, audio)) {
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
            when (val r = ApexRepository.brief(staff.id, lang, refresh)) {
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
        val r = ApexRepository.history(staff.id, lang)
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
            val answer = when (val r = ApexRepository.ask(staff.id, lang, q)) {
                is ApexRepository.Result.Reply -> r.text
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
        // Header: mascot, name, language, refresh
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ApexAvatar(44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(apexName(lang), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                Text(t("门店经营助手", "Your store assistant"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = {
                lang = if (lang == "zh") "en" else "zh"
                ApexRepository.setLanguage(context, lang)
            }) { Text(if (lang == "zh") "EN" else "中文", fontWeight = FontWeight.Bold) }
            IconButton(onClick = { load(refresh = true) }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = t("刷新", "Refresh"))
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
                    stats != null -> StatsPanel(stats!!, lang)
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
                        Text(t("${apexName(lang)}的今日简报", "${apexName(lang)}'s brief"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        when {
                            loading && brief == null -> Text(t("小海狸正在看今天的数据…", "Beaver is looking over today's numbers..."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            modelOff -> Text(t("AI 简报还没开通（云端未配置模型）。上面的数字是实时的。", "The AI brief isn't switched on yet (no model configured). The numbers above are live."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            brief != null -> Text(brief!!, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp)
                            loadError != null -> Text("⚠ $loadError", color = GsTheme.colors.danger)
                        }
                    }
                }
            }
            items(chat) { (fromUser, text) -> ChatBubble(fromUser, text, lang) }
            if (asking) item { ChatBubble(false, "…", lang) }
        }

        // Quick questions + input
        val quick = if (lang == "zh") listOf("今天生意怎么样？", "这周什么卖得最好？", "哪些商品卖不动？", "高峰期是几点？", "最近有哪些退款？")
                    else listOf("How's today going?", "Best sellers this week?", "What isn't selling?", "When are we busiest?", "Any recent refunds?")
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quick) { q -> AssistChip(onClick = { send(q) }, label = { Text(q) }, enabled = !asking) }
        }
        val note = when {
            recording -> t("正在听…松开结束", "Listening... release when done")
            transcribing -> t("正在识别…", "Transcribing...")
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
                        if (!recording) voiceNote = t("麦克风打不开", "Couldn't open the microphone")
                        tryAwaitRelease()
                        stopAndTranscribe()
                    })
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (transcribing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Mic, contentDescription = t("按住说话", "Hold to talk"),
                        tint = if (recording) Color.White else MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = question,
                onValueChange = { question = it.take(500) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(t("问问小海狸…", "Ask Beaver...")) },
                maxLines = 3,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send(question) })
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { send(question) }, enabled = question.isNotBlank() && !asking) {
                Icon(Icons.Default.Send, contentDescription = t("发送", "Send"))
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
private fun StatsPanel(stats: JsonObject, lang: String) {
    fun t(zh: String, en: String) = if (lang == "zh") zh else en
    val today = stats.obj("today")
    val lastWeek = stats.obj("same_time_last_week")
    val total = today?.num("total_cents") ?: 0.0
    val before = lastWeek?.num("total_cents") ?: 0.0
    val change = if (before > 0) ((total - before) / before * 100).toInt() else null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(t("今日销售", "Sales today"), money(total), Modifier.weight(1f),
                sub = when {
                    change == null -> t("上周同期无数据", "No sales same time last week")
                    change >= 0 -> t("比上周同期 +$change%", "+$change% vs last week")
                    else -> t("比上周同期 $change%", "$change% vs last week")
                },
                subColor = when { change == null -> null; change >= 0 -> GsTheme.colors.success; else -> GsTheme.colors.danger })
            StatCard(t("笔数 / 客单价", "Sales / avg"), "${(today?.num("sales") ?: 0.0).toInt()} · ${money(today?.num("avg_basket_cents") ?: 0.0)}", Modifier.weight(1f),
                sub = t("现金 ${money(today?.num("cash_cents") ?: 0.0)} · 刷卡 ${money(today?.num("card_cents") ?: 0.0)}",
                        "Cash ${money(today?.num("cash_cents") ?: 0.0)} · Card ${money(today?.num("card_cents") ?: 0.0)}"))
        }
        val peaks = stats.arr("hourly_last_28_days").sortedByDescending { it.num("avg_sales") }.take(3).filter { it.num("avg_sales") > 0 }
        if (peaks.isNotEmpty()) {
            StatCard(t("高峰时段（近 28 天）", "Busiest hours (28 days)"),
                peaks.joinToString("  ") { "%02d:00".format(it.num("hour").toInt()) }, Modifier.fillMaxWidth())
        }
        val top = stats.arr("top_items_7_days").take(5)
        if (top.isNotEmpty()) {
            StatCard(t("近 7 天畅销", "Best sellers (7 days)"),
                top.joinToString("\n") { "${it["name"]?.jsonPrimitive?.contentOrNull} × ${it.num("qty").toInt()}" }, Modifier.fillMaxWidth(), small = true)
        }
        // Loss prevention first: rule-based flags from the database, worded here.
        val flags = stats.obj("loss_signals")?.arr("flags").orEmpty()
        if (flags.isNotEmpty()) {
            StatCard(t("防损提醒（近 7 天，值得核对）", "Worth a look (last 7 days)"),
                flags.joinToString("\n") { "• " + lossFlagText(it, lang) }, Modifier.fillMaxWidth(), small = true,
                accent = GsTheme.colors.warning)
        }
        val perishable = (stats["perishable_not_sold_3_days"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (perishable.isNotEmpty()) {
            StatCard(t("生鲜 3 天没卖出（考虑打折）", "Fresh items unsold for 3 days (mark down?)"),
                perishable.take(8).joinToString("、"), Modifier.fillMaxWidth(), small = true)
        }
        val unsold = (stats["not_sold_14_days"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }.orEmpty()
        if (unsold.isNotEmpty()) {
            StatCard(t("14 天没卖出", "Not sold in 14 days"),
                t("${unsold.size} 个商品", "${unsold.size} items"), Modifier.fillMaxWidth(),
                sub = unsold.take(5).joinToString("、"))
        }
    }
}

/** One loss-prevention flag (apex_loss_signals) in plain words. Never an accusation: "check this". */
private fun lossFlagText(flag: JsonObject, lang: String): String {
    val zh = lang == "zh"
    val who = flag["staff"]?.jsonPrimitive?.contentOrNull ?: ""
    val d = flag.obj("detail")
    fun n(k: String) = d?.num(k) ?: 0.0
    fun i(k: String) = n(k).toInt()
    return when (flag["kind"]?.jsonPrimitive?.contentOrNull) {
        "HIGH_REFUND_RATE" -> if (zh) "$who 经手的销售退款/撤销 ${i("refunds")} 笔（${n("rate_pct")}%，全店 ${n("store_rate_pct")}%），共 ${money(n("refund_cents"))}"
            else "$who: ${i("refunds")} refunds/voids on their sales (${n("rate_pct")}% vs store ${n("store_rate_pct")}%), ${money(n("refund_cents"))}"
        "SELF_APPROVED" -> if (zh) "$who 批准了自己经手的销售退款 ${i("count")} 笔" else "$who approved refunds of their own sales: ${i("count")}"
        "CASH_REFUNDS" -> if (zh) "$who 经手的现金销售被退款 ${i("count")} 笔" else "$who: ${i("count")} cash refunds on their sales"
        "HIGH_CASH_SHARE" -> if (zh) "$who 收现金比例 ${n("cash_share_pct")}%，明显高于全店 ${n("store_cash_share_pct")}%"
            else "$who takes ${n("cash_share_pct")}% in cash vs store ${n("store_cash_share_pct")}%"
        "DECLINES_TODAY" -> if (zh) "今天被拒付 ${i("count")} 笔，留意读卡器或可疑卡" else "${i("count")} declined card payments today -- check the reader or suspicious cards"
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
