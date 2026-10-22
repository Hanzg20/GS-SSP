package com.goldsky.ssp.ui

import com.goldsky.ssp.payment.hardware.IScannerProvider
import com.goldsky.ssp.payment.hardware.HardwareFactory
import android.widget.Toast
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The technician's system check (Aegis Wash / Timer tech panel, 2026-10-20): what usually
 * goes wrong on site at a glance -- network, card service (OPC), default HOME, the overlay
 * permission that lets the kiosk take the screen back after a boot, clock, screen sleep,
 * volume -- each with a button to the Android page that fixes it. An ordinary app can't
 * reboot the terminal, set the clock, switch Wi-Fi or make itself HOME: it opens the page,
 * the technician changes it there and comes back with Back (the list re-checks itself).
 */
class SystemCheckActivity : ComponentActivity() {
    private val bg = Color(0xFF0F141A)
    private val card = Color(0xFF1A222C)
    private val amber = Color(0xFFFFB800)
    private val green = Color(0xFF2ECC71)
    private val red = Color(0xFFFF5A5F)
    private val muted = Color(0xFF8A96A3)

    /**
     * One line of the check. [ok] null: for information only. [fix]: the page that changes it.
     * [test]: a button that tries the thing itself (label to action).
     */
    private data class Row(val label: String, val value: String, val ok: Boolean?, val fix: Fix? = null, val test: Pair<String, () -> Unit>? = null)

    private enum class Fix(val label: String) {
        NETWORK("网络 Wi-Fi / SIM / 有线"), HOME("默认桌面 Home app"), OVERLAY("悬浮窗权限 Overlay"),
        DATE("日期和时间 Date & time"), DISPLAY("显示 Display"), SOUND("声音 Sound"), PAYWIZARD("PAYWizard (OPC)")
    }

    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var rows by remember { mutableStateOf<List<Row>?>(null) }
            LaunchedEffect(refresh) { rows = null; rows = check() }
            Column(Modifier.fillMaxSize().background(bg).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("系统检查 System check", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { refresh++ }) { Text("刷新 Refresh", color = amber) }
                    TextButton(onClick = { finish() }) { Text("返回 Back", color = amber) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val list = rows
                    if (list == null) Text("检查中… Checking…", color = muted, modifier = Modifier.padding(8.dp))
                    else list.forEach { r -> CheckRow(r) }
                    Spacer(Modifier.height(8.dp))
                    Text("系统设置 System settings", color = muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Fix.values().filter { it != Fix.PAYWIZARD || opcInstalled() }.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { f ->
                                OutlinedButton(onClick = { open(f) }, modifier = Modifier.weight(1f).height(48.dp)) {
                                    Text(f.label, color = Color.White, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text("按返回键回到这里，会自动重新检查。Press Back to come back; the list re-checks.", color = muted, fontSize = 11.sp)
                }
            }
        }
    }

    // Back from a settings page: check again.
    override fun onRestart() { super.onRestart(); refresh++ }

    @Composable
    private fun CheckRow(r: Row) {
        Row(
            Modifier.fillMaxWidth().background(card, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).background(when (r.ok) { true -> green; false -> red; null -> muted }, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(r.value, color = if (r.ok == false) red else muted, fontSize = 12.sp)
            }
            r.test?.let { (label, action) ->
                OutlinedButton(onClick = action, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(label, color = amber, fontSize = 12.sp)
                }
            }
            if (r.fix != null && r.ok == false) {
                Button(onClick = { open(r.fix) }, colors = ButtonDefaults.buttonColors(containerColor = amber, contentColor = bg),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("去设置 Fix", fontSize = 12.sp) }
            }
        }
    }

    // ---------------------------------------------------------------- checks

    private suspend fun check(): List<Row> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Row>()
        rows += network()
        rows += cloud()
        if (opcInstalled() && DeviceRepository.getPersistedHardwareVendor().uppercase() == "WIZARPOS") {
            val up = PayWizardGuard.portOpen()
            rows += Row("刷卡服务 Card service (PAYWizard / OPC)", if (up) "运行中 Running (port 6031)" else "没有运行 Not running — 刷卡会失败 card sales will fail", up, Fix.PAYWIZARD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val held = runCatching { getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
            rows += Row("默认桌面 Default home", if (held) "是本应用 This app" else "不是本应用 — 重启后不会回到收款界面 Not this app: a reboot won't come back here", held, Fix.HOME)
        }
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@SystemCheckActivity)
        if (DeviceRepository.getPersistedHardwareVendor().uppercase() == "WIZARPOS") rows += scanner()
        rows += Row("悬浮窗权限 Overlay permission", if (overlay) "已允许 Allowed" else "未允许 — 开机被 OPC 盖住时拉不回来 Not allowed: can't take the screen back from OPC after a boot", overlay, Fix.OVERLAY)
        rows += clock()
        val timeout = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
        rows += Row("屏幕休眠 Screen sleep", timeout?.let { if (it >= 24 * 3_600_000) "从不 Never" else "${it / 60_000} 分钟 min" } ?: "?",
            timeout?.let { it >= 10 * 60_000 }, Fix.DISPLAY)
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC); val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        rows += Row("语音音量 Voice volume", if (vol == 0) "静音 Muted — 顾客听不到语音提示 customers hear no voice prompts" else "$vol / $max", vol > 0, Fix.SOUND)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val up = SystemClock.elapsedRealtime() / 60_000
        rows += Row("版本 / 开机时长 Version / up time", "$packageName $version · " + if (up >= 60 * 24) "${up / 1440} 天 days" else if (up >= 60) "${up / 60} 小时 h ${up % 60} 分 min" else "$up 分钟 min", null)
        rows
    }

    private fun network(): Row {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return Row("网络 Network", "没有网络 No connection", false, Fix.NETWORK)
        val caps = cm.getNetworkCapabilities(net)
        val ip = cm.getLinkProperties(net)?.linkAddresses?.map { it.address.hostAddress ?: "" }?.firstOrNull { it.contains('.') } ?: "?"
        val kind = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "有线 Ethernet"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                @Suppress("DEPRECATION")
                val rssi = runCatching { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.rssi }.getOrNull()
                "Wi-Fi" + (rssi?.let { " · 信号 signal ${WifiManager.calculateSignalLevel(it, 5)}/4 ($it dBm)" } ?: "")
            }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                val op = runCatching { (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName }.getOrNull()
                "SIM 移动数据 mobile data" + (op?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            }
            else -> "?"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return Row("网络 Network", "$kind · IP $ip" + if (validated) "" else " · 连不上互联网 no internet", validated, Fix.NETWORK)
    }

    /** The cloud the terminal reports to: its host answers on 443 within 3 s. */
    /**
     * The camera scanner (coupon / VIP codes): the scan service, the camera, the last good
     * read -- and a test that opens it. 253 refused scans for ten minutes (2026-10-21).
     */
    private fun scanner(): Row {
        val provider = runCatching { HardwareFactory.getScannerProvider(this, DeviceRepository.getPersistedHardwareVendor()) }.getOrNull()
        val info = provider?.diagnostics().orEmpty().ifEmpty { "?" }
        val bad = provider == null || info.contains("NOT INSTALLED") || info.contains("DISABLED") || info.contains("DENIED")
        return Row("扫码器 Scanner (coupon / VIP)", info, if (provider == null) false else !bad, test = "测试 Test" to { testScan() })
    }

    /** Opens the scanner like a customer scan; says what came back. */
    private fun testScan() {
        val provider = runCatching { HardwareFactory.getScannerProvider(this, DeviceRepository.getPersistedHardwareVendor()) }.getOrNull()
            ?: return Toast.makeText(this, "没有扫码器 No scanner", Toast.LENGTH_LONG).show()
        val stop = Runnable { provider.stopScan() }
        window.decorView.postDelayed(stop, 15_000)
        provider.startScan(object : IScannerProvider.ScanCallback {
            override fun onScanSuccess(result: String) {
                window.decorView.removeCallbacks(stop)
                runOnUiThread { Toast.makeText(this@SystemCheckActivity, "扫码正常 Scanner OK: ${result.take(12)}", Toast.LENGTH_LONG).show(); refresh++ }
            }
            override fun onScanFailure(errorMsg: String) {
                window.decorView.removeCallbacks(stop)
                runOnUiThread { Toast.makeText(this@SystemCheckActivity, "扫码失败 Scan failed: $errorMsg", Toast.LENGTH_LONG).show(); refresh++ }
            }
        })
    }

    private fun cloud(): Row {
        val host = runCatching { Uri.parse(SupabaseConfig.URL).host }.getOrNull()
        val ok = host != null && runCatching { Socket().use { it.connect(InetSocketAddress(host, 443), 3_000) }; true }.getOrDefault(false)
        return Row("云端 Cloud", if (ok) "能连上 Reachable" else "连不上 Unreachable — 记录会先存在本机 records wait on the terminal", ok, Fix.NETWORK)
    }

    private fun clock(): Row {
        val auto = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME) == 1 }.getOrDefault(false)
        val tz = TimeZone.getDefault()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        // A clock far off (a dead clock battery, no network time) breaks receipts, reports and secure connections.
        val plausible = now >= "2026-01-01"
        return Row("时间 Clock", "$now · ${tz.id}" + if (auto) " · 自动对时 auto" else " · 未开自动对时 auto time off", auto && plausible, Fix.DATE)
    }

    // ---------------------------------------------------------------- fixes

    private fun opcInstalled() = runCatching { packageManager.getPackageInfo("com.wizarpos.opc", 0); true }.getOrDefault(false)

    private fun open(f: Fix) {
        if (f == Fix.NETWORK) { NetworkSettings.open(this); return }
        val intent = when (f) {
            Fix.HOME -> Intent(Settings.ACTION_HOME_SETTINGS)
            Fix.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            Fix.DATE -> Intent(Settings.ACTION_DATE_SETTINGS)
            Fix.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            Fix.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
            Fix.PAYWIZARD -> packageManager.getLaunchIntentForPackage("com.wizarpos.opc")
            Fix.NETWORK -> null
        }
        val target = intent?.takeIf { it.resolveActivity(packageManager) != null } ?: Intent(Settings.ACTION_SETTINGS)
        runCatching { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w("SystemCheck", "Could not open ${f.name}: ${it.message}") }
    }
}
