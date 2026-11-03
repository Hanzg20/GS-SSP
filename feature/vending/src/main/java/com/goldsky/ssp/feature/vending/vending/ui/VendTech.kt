package com.goldsky.ssp.vending.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.vending.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Technician panel (four corners + the store's PIN, see MainActivity). Staff-facing, so in
 * Chinese like Aegis Timer's. Shows what the terminal sees of the vending machine and opens
 * the shared tools; the payment tests there run real card transactions.
 */
@Composable
fun VendTechScreen(
    version: String,
    deviceSn: String,
    mdbStatus: () -> String,
    onSystemCheck: () -> Unit,
    onPaymentTests: () -> Unit,
    bigScreen: BigScreen,
    onCastSettings: () -> Unit,
    onPairScreen: suspend (code: String) -> String?,
    purchaseFlow: com.goldsky.ssp.vending.logic.PurchaseFlow,
    onPurchaseFlow: (com.goldsky.ssp.vending.logic.PurchaseFlow) -> Unit,
    saleMode: com.goldsky.ssp.vending.SaleMode.Mode,
    onSaleMode: (com.goldsky.ssp.vending.SaleMode.Mode) -> Unit,
    onExit: () -> Unit,
) {
    val showcase = saleMode == com.goldsky.ssp.vending.SaleMode.Mode.SHOWCASE
    var pairing by remember { mutableStateOf(false) }
    var pairedScreen by remember { mutableStateOf(bigScreen.pairedScreen) }
    var role by remember { mutableStateOf(bigScreen.role) }
    var screenMode by remember { mutableStateOf(bigScreen.mode) }
    var screenStatus by remember { mutableStateOf(bigScreen.describe()) }
    var status by remember { mutableStateOf("读取中…") }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        status = "读取中…"
        status = withContext(Dispatchers.IO) { runCatching(mdbStatus).getOrElse { "读取失败: ${it.message}" } }
        screenStatus = bigScreen.describe()
    }
    Column(
        Modifier.fillMaxSize().background(KioskBg).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("技术员 · Aegis Vend $version", color = KioskAmber, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text("SN $deviceSn", color = TextSecondary, fontSize = 12.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
            Text("售货机 (MDB)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(status, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        TechButton("刷新售货机状态", { refresh++ })
        if (!showcase) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
                Text("购买方式", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (purchaseFlow) {
                        com.goldsky.ssp.vending.logic.PurchaseFlow.BOTH -> "两种都可：顾客可先点 START 刷卡再选货，也可先在售货机上选货再付款"
                        com.goldsky.ssp.vending.logic.PurchaseFlow.CARD_FIRST -> "只先刷卡：点 START 刷卡（预授权）后在售货机上选货；没刷卡就选货会被拒绝"
                        com.goldsky.ssp.vending.logic.PurchaseFlow.ITEM_FIRST -> "只先选货：先在售货机上选货，本机显示价格后点 PAY 刷卡；没有 START"
                    },
                    color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        com.goldsky.ssp.vending.logic.PurchaseFlow.BOTH to "两种都可",
                        com.goldsky.ssp.vending.logic.PurchaseFlow.CARD_FIRST to "先刷卡",
                        com.goldsky.ssp.vending.logic.PurchaseFlow.ITEM_FIRST to "先选货",
                    ).forEach { (f, label) ->
                        val on = f == purchaseFlow
                        Box(
                            Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (on) KioskAmber else KioskBg).clickable { onPurchaseFlow(f) },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (on) KioskBg else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
            Text("销售模式", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (showcase)
                    "终端选货（展示）：本机显示 6 宫格，顾客点选后刷卡；卡授权后立即撤销，不扣款、不出货。售货机的 MDB 销售暂停"
                else "MDB 售货机（默认）：顾客在售货机上选货，本机收款",
                color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        TechButton(
            if (showcase) "改回 MDB 售货机" else "改为终端选货（展示）",
            { onSaleMode(if (showcase) com.goldsky.ssp.vending.SaleMode.Mode.MDB else com.goldsky.ssp.vending.SaleMode.Mode.SHOWCASE) },
        )
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
            Text("本机角色", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (role == BigScreen.Role.PRIMARY) "主机（带大屏）：购买信息同步到大屏" else "单机（无大屏）",
                color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        TechButton(if (role == BigScreen.Role.PRIMARY) "改为单机（无大屏）" else "改为主机（带大屏）", {
            bigScreen.role = if (role == BigScreen.Role.PRIMARY) BigScreen.Role.STANDALONE else BigScreen.Role.PRIMARY
            role = bigScreen.role
            screenStatus = bigScreen.describe()
        })
        if (role == BigScreen.Role.PRIMARY) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
                Text("网络大屏（Aegis Vend Screen，从机）", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    pairedScreen?.let { "已配对：$it" } ?: "未配对。大屏装 Aegis Vend Screen 后会显示 6 位配对码",
                    color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            TechButton("配对网络大屏", { pairing = true })
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
                Text("投屏大屏（无线 / 第二显示）", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(screenStatus, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                Text(
                    if (screenMode == BigScreen.Mode.DUAL) "双屏：大屏显示大字购买信息，顾客在本机刷卡"
                    else "镜像：大屏和本机显示一样",
                    color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            TechButton("投屏设置（连接大屏）", onCastSettings)
            TechButton(if (screenMode == BigScreen.Mode.DUAL) "改为镜像模式" else "改为双屏模式", {
                bigScreen.mode = if (screenMode == BigScreen.Mode.DUAL) BigScreen.Mode.MIRROR else BigScreen.Mode.DUAL
                screenMode = bigScreen.mode
                screenStatus = bigScreen.describe()
            })
        }
        TechButton("系统检查", onSystemCheck)
        TechButton("支付测试（真实交易）", onPaymentTests)
        TechButton("返回售卖界面", onExit, primary = true)
    }
    if (pairing) {
        VendPairDialog(onDismiss = { pairing = false }, pair = onPairScreen) { screen ->
            bigScreen.pairedScreen = screen
            pairedScreen = screen
        }
    }
}

/**
 * Pairs a network big screen: the 6-digit code it shows goes in here, the terminal sends its
 * SN to that code over the cloud and the screen answers with its own.
 */
@Composable
private fun VendPairDialog(onDismiss: () -> Unit, pair: suspend (String) -> String?, onPaired: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("输入大屏上显示的 6 位配对码") }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.width(280.dp).background(KioskCard, RoundedCornerShape(16.dp)).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("配对网络大屏", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(code.ifEmpty { "______" }, color = KioskAmber, fontSize = 24.sp, letterSpacing = 4.sp, modifier = Modifier.padding(vertical = 4.dp))
            Text(message, color = if (done) KioskEmerald else TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Keypad { k ->
                if (busy) return@Keypad
                when (k) {
                    "✕" -> if (code.isEmpty() || done) onDismiss() else code = code.dropLast(1)
                    "OK" -> if (code.length == 6) {
                        busy = true
                        message = "配对中…（最多 20 秒）"
                        scope.launch {
                            val screen = runCatching { pair(code) }.getOrNull()
                            busy = false
                            if (screen != null) {
                                done = true
                                message = "配对成功：${screen.ifBlank { "大屏" }}"
                                onPaired(screen.ifBlank { "大屏" })
                            } else {
                                message = "没有大屏回应：请核对配对码，并确认两台设备都已联网"
                            }
                        }
                    }
                    else -> if (code.length < 6 && !done) code += k
                }
            }
        }
    }
}

@Composable
private fun Keypad(onKey: (String) -> Unit) {
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✕", "0", "OK").chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 3.dp)) {
            row.forEach { k ->
                Box(
                    Modifier.size(width = 76.dp, height = 42.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (k == "OK") KioskAmber else KioskBg)
                        .clickable { onKey(k) },
                    contentAlignment = Alignment.Center,
                ) { Text(k, color = if (k == "OK") KioskBg else TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun TechButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (primary) KioskAmber else KioskCard).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) KioskBg else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** The technician PIN pad (same keys and rules as Aegis Timer's). [onSubmit] false = wrong PIN. */
@Composable
fun VendPinDialog(onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(280.dp).background(KioskCard, RoundedCornerShape(16.dp)).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("技术员 PIN", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (pin.isEmpty()) "————" else "●".repeat(pin.length),
                color = if (wrong) KioskRed else KioskAmber, fontSize = 24.sp, modifier = Modifier.padding(vertical = 6.dp),
            )
            Keypad { k ->
                wrong = false
                when (k) {
                    "✕" -> if (pin.isEmpty()) onDismiss() else pin = pin.dropLast(1)
                    "OK" -> if (!onSubmit(pin)) { wrong = true; pin = "" }
                    else -> if (pin.length < 8) pin += k
                }
            }
        }
    }
}
