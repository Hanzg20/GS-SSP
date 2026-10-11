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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || asking) return
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

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
        Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it.take(500) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(t("问问小海狸…", "Ask Beaver...")) },
                maxLines = 3
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
        val unsold = (stats["not_sold_14_days"] as? JsonArray)?.size ?: 0
        if (unsold > 0) {
            StatCard(t("14 天没卖出", "Not sold in 14 days"),
                t("$unsold 个商品", "$unsold items"), Modifier.fillMaxWidth(),
                sub = (stats["not_sold_14_days"] as JsonArray).take(5).joinToString("、") { it.jsonPrimitive.contentOrNull ?: "" })
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier, sub: String? = null, subColor: Color? = null, small: Boolean = false) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = if (small) 15.sp else 22.sp, fontWeight = if (small) FontWeight.Medium else FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, lineHeight = if (small) 21.sp else 28.sp)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = subColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
