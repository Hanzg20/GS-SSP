package com.goldsky.ssp.ourea.eye.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.ourea.eye.BuildConfig
import com.goldsky.ssp.ourea.eye.CfdDisplayState
import com.goldsky.ssp.ourea.eye.CfdItemData
import com.goldsky.ssp.ourea.eye.SocketServerManager
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.HeartbeatWorker
import com.goldsky.ssp.ui.theme.DangerOnDark
import com.goldsky.ssp.ui.theme.Navy600
import com.goldsky.ssp.ui.theme.Navy800
import com.goldsky.ssp.ui.theme.Navy950
import com.goldsky.ssp.ui.theme.ScanFrameGold
import com.goldsky.ssp.ui.theme.Slate100
import com.goldsky.ssp.ui.theme.Slate400
import com.goldsky.ssp.ui.theme.SuccessOnDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Ourea Eye technician mode, the same pattern as the Aegis kiosks: a hidden
 * gesture (5 taps in 3 s on the top-left corner), then the store's technician
 * PIN (kiosk settings' maintenance_pin, from CMP; 1234 until the store sets
 * one). Shows what an installer needs to pair Ourea with this display --
 * the LAN IP above all -- and a few tests.
 */

/** Hidden entry: 5 taps within 3 s on an invisible 72dp square in the top-left corner. */
@Composable
fun TechnicianHotspot(onTriggered: () -> Unit) {
    var count by remember { mutableStateOf(0) }
    var first by remember { mutableStateOf(0L) }
    Box(
        Modifier.size(72.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            val now = System.currentTimeMillis()
            if (now - first > 3000) { first = now; count = 0 }
            count++
            if (count >= 5) { count = 0; onTriggered() }
        }
    )
}

@Composable
fun TechnicianPinPad(onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Navy950.copy(alpha = 0.97f)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("Technician PIN", color = Slate100, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(if (pin.isEmpty()) "————" else "●".repeat(pin.length), color = ScanFrameGold, fontSize = 24.sp, letterSpacing = 6.sp)
        if (wrong) Text("Wrong PIN", color = DangerOnDark, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("✕", "0", "OK")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(width = 96.dp, height = 52.dp).background(if (k == "OK") ScanFrameGold else Navy800, RoundedCornerShape(12.dp))
                            .border(0.5.dp, Navy600, RoundedCornerShape(12.dp))
                            .clickable {
                                wrong = false
                                when (k) {
                                    "✕" -> if (pin.isEmpty()) onDismiss() else pin = pin.dropLast(1)
                                    "OK" -> if (!onSubmit(pin)) { wrong = true; pin = "" }
                                    else -> if (pin.length < 8) pin += k
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(k, color = if (k == "OK") Navy950 else Slate100, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

private data class Diag(
    val ips: List<String>, val payWizard: String, val sn: String, val org: String, val listening: Boolean,
    val lastClient: String, val heartbeat: String, val ads: String, val storage: String, val time: String
)

private fun ago(at: Long): String {
    if (at <= 0) return "—"
    val s = (System.currentTimeMillis() - at) / 1000
    return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60} min ago"; else -> "${s / 3600} h ago" }
}

private fun lanIps(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { nif ->
        nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress}  (${nif.name})" }
    }
} catch (e: Exception) { emptyList() }

/** This Q3mini's own PAYWizard -- the service Ourea charges through. */
private fun payWizardPort(): String {
    for (port in listOf(6031, 6666)) {
        try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }; return "✓ up on port $port" } catch (_: Exception) {}
    }
    return "✗ not reachable on 6031 / 6666"
}

private suspend fun collect(context: Context): Diag = withContext(Dispatchers.IO) {
    val adsDir = AdManager.getAdsDir(context)
    val files = adsDir.listFiles()?.filter { !it.name.endsWith(".part") }.orEmpty()
    val freeMb = context.filesDir.freeSpace / (1024 * 1024)
    Diag(
        ips = lanIps(),
        payWizard = payWizardPort(),
        sn = DeviceRepository.getPersistedDeviceSn() ?: "—",
        org = DeviceRepository.getPersistedOrgId()?.let { "Linked · ${it.take(8)}…" } ?: "✗ Not linked (assign in CMP)",
        listening = SocketServerManager.isListening,
        lastClient = SocketServerManager.lastClientIp?.let { "$it · ${SocketServerManager.lastCommand} · ${ago(SocketServerManager.lastCommandAt)}" } ?: "No register has connected yet",
        heartbeat = ago(HeartbeatWorker.lastSentAt),
        ads = "${AdManager.getCachedPlaylist(context).size} in playlist · ${files.size} files, ${files.sumOf { it.length() } / (1024 * 1024)} MB",
        storage = "$freeMb MB free",
        time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date())
    )
}

@Composable
fun TechnicianPanel(
    rotation: Int,
    onRotate: (Int) -> Unit,
    onPreview: (CfdDisplayState) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var diag by remember { mutableStateOf<Diag?>(null) }
    LaunchedEffect(Unit) { while (true) { diag = collect(context); delay(2000) } }

    Column(
        Modifier.fillMaxSize().background(Navy950).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Technician Mode", color = ScanFrameGold, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Text("v${BuildConfig.VERSION_NAME}", color = Slate400, fontSize = 11.sp)
        }
        val d = diag
        if (d == null) {
            Text("Loading…", color = Slate400)
        } else {
            Section("Network · Pairing") {
                Line("LAN IP", if (d.ips.isEmpty()) "✗ No network" else d.ips.joinToString("\n"), big = true,
                    good = d.ips.isNotEmpty())
                Line("Display port", "${SocketServerManager.LISTEN_PORT} " + if (d.listening) "✓ listening" else "✗ not listening", good = d.listening)
                Line("Last client", d.lastClient)
                Line("PAYWizard", d.payWizard, good = d.payWizard.startsWith("✓"))
                Text("In Ourea: Settings > Hardware, set the payment terminal IP to the LAN IP above.", color = Slate400, fontSize = 11.sp)
            }
            Section("Device · Cloud") {
                Line("Serial", d.sn)
                Line("Store", d.org, good = !d.org.startsWith("✗"))
                Line("Heartbeat", d.heartbeat)
                Line("Ads", d.ads)
                Line("Storage", d.storage)
                Line("Time", d.time)
                Line("System", "${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            }
        }
        Section("Volume") { VolumeRow() }
        Section("Tests") {
            val demo = CfdDisplayState.Cart(
                listOf(CfdItemData("Test item A", 2, 199), CfdItemData("Test item B", 1, 1250)),
                subtotalCents = 1648, taxCents = 214, totalCents = 1862
            )
            ToolRow(listOf(
                "Preview cart" to { onPreview(demo) },
                "Preview thank-you" to { onPreview(CfdDisplayState.ThankYou(23)) },
            ))
            ToolRow(listOf(
                "Preview payment" to { onPreview(CfdDisplayState.Payment(1862)) },
                "Back to ads" to { onPreview(CfdDisplayState.Idle) },
            ))
            ToolRow(listOf(
                "Sync ads now" to { AdManager.syncNow(context) },
                "Send heartbeat" to { AdManager.onlineNow(context, force = true) },
            ))
            ToolRow(listOf(
                (if (rotation == 180) "Rotate to 0°" else "Rotate to 180°") to { onRotate(if (rotation == 180) 0 else 180) },
                "Open PAYWizard" to { openPayWizard(context) },
            ))
            ToolRow(listOf(
                "Wi-Fi settings" to { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                "Exit" to onExit,
            ))
        }
    }
}

/**
 * Media volume (ad videos, sounds): the system's own, so it holds across
 * restarts. Steps of the stream's real range, shown as a bar.
 */
@Composable
private fun VolumeRow() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var level by remember { mutableStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    // Follows changes made elsewhere too (hardware keys, PAYWizard).
    LaunchedEffect(Unit) { while (true) { delay(1000); level = audio.getStreamVolume(AudioManager.STREAM_MUSIC) } }
    fun set(v: Int) {
        level = v.coerceIn(0, max)
        // FLAG_PLAY_SOUND: a click at the new level, so the technician hears it.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_PLAY_SOUND)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VolumeButton("−") { set(level - 1) }
        Box(Modifier.weight(1f).height(10.dp).background(Navy600, RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxWidth(if (max > 0) level / max.toFloat() else 0f).fillMaxHeight().background(ScanFrameGold, RoundedCornerShape(5.dp)))
        }
        VolumeButton("+") { set(level + 1) }
        Text(if (level == 0) "Mute" else "${level * 100 / max.coerceAtLeast(1)}%", color = Slate100, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
    }
    ToolRow(listOf(
        "Mute" to { set(0) },
        "50%" to { set((max + 1) / 2) },
        "Max" to { set(max) },
    ))
}

@Composable
private fun VolumeButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).background(Navy600.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .border(0.5.dp, ScanFrameGold.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Slate100, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

private fun openPayWizard(context: Context) {
    val pm = context.packageManager
    listOf("com.wizarpos.paywizard", "com.wizarpos.paywizard.demo").firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Navy800, RoundedCornerShape(12.dp)).border(0.5.dp, Navy600, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = ScanFrameGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Line(label: String, value: String, big: Boolean = false, good: Boolean? = null) {
    Row {
        Text(label, color = Slate400, fontSize = 12.sp, modifier = Modifier.width(92.dp))
        Text(
            value, fontSize = if (big) 16.sp else 12.sp, fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (big) FontFamily.Monospace else FontFamily.Default,
            color = when (good) { true -> if (big) SuccessOnDark else Slate100; false -> DangerOnDark; null -> Slate100 }
        )
    }
}

@Composable
private fun ToolRow(items: List<Pair<String, () -> Unit>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items.forEach { (label, action) ->
            Box(
                Modifier.weight(1f).height(44.dp).background(Navy600.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .border(0.5.dp, ScanFrameGold.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).clickable { action() },
                contentAlignment = Alignment.Center
            ) { Text(label, color = Slate100, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

