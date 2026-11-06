package com.goldsky.ssp.flex.ui

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
import com.goldsky.ssp.flex.ui.theme.*

/**
 * Technician panel (four corners + the store's PIN, see MainActivity). Staff-facing, so in
 * Chinese like Aegis Vend's. Shows the partner controller and opens the shared tools; the
 * payment tests there run real card transactions.
 */
@Composable
fun FlexTechScreen(
    version: String,
    deviceSn: String,
    controller: String,
    /** The last check: true ready, false not, null none set up. */
    controllerReady: Boolean?,
    controllerDetail: String,
    controllerUrl: String,
    controllerToken: String,
    onSetController: (url: String, token: String) -> Unit,
    onTestController: () -> Unit,
    /** The card is held at the price and settled after the hand-over, instead of charged at once. */
    preauth: Boolean,
    onPreauth: (Boolean) -> Unit,
    products: Int,
    onSystemCheck: () -> Unit,
    onPaymentTests: () -> Unit,
    onExit: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(KioskBg).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("技术员 · Aegis Flex $version", color = KioskAmber, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text("SN $deviceSn", color = TextSecondary, fontSize = 12.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
            Text("出货控制器", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(controller, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            if (controllerReady != null) Text(
                (if (controllerReady) "● " else "● 暂停售卖 · ") + controllerDetail,
                color = if (controllerReady) KioskEmerald else KioskRed, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        TechButton("设置控制器地址", { editing = true })
        // The shared simulator on Cael: tests anywhere, a person answers on the page.
        TechButton("使用 Cael 测试模拟器", { onSetController(com.goldsky.ssp.flex.logic.FlexController.CAEL_SIM, "") })
        if (controllerReady != null) TechButton("测试连接", onTestController)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
            Text("收款方式", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (preauth) "预授权：按标价冻结，出货后按控制器给的金额结算（不超过标价），没出货就释放"
                else "直接扣款：按标价扣款，没出货就撤销",
                color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        TechButton(if (preauth) "改为直接扣款" else "改为预授权", { onPreauth(!preauth) })
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
            Text("商品", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (products > 0) "$products 个（Cael 中本店的「合作方机器 FLEX」商品）" else "没有商品：请在 Cael 中给本店添加「合作方机器 FLEX」商品",
                color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        TechButton("系统检查", onSystemCheck)
        TechButton("支付测试（真实交易）", onPaymentTests)
        TechButton("返回售卖界面", onExit, primary = true)
    }
    if (editing) {
        ControllerDialog(controllerUrl, controllerToken, onDismiss = { editing = false }) { url, token ->
            editing = false
            onSetController(url, token)
        }
    }
}

/**
 * The controller's address on the site LAN (e.g. 192.168.1.50:8080) and its shared token, if
 * it asks for one. Blank address = no controller (every sale given back, the TEST strip).
 */
@Composable
private fun ControllerDialog(url: String, token: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var u by remember { mutableStateOf(url) }
    var t by remember { mutableStateOf(token) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(300.dp).background(KioskCard, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("出货控制器", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("地址，如 192.168.1.50:8080；cael = Cael 测试模拟器；留空 = 不接控制器", color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            Field(u, "控制器地址") { u = it }
            Text("令牌（控制器要求时填写）", color = TextSecondary, fontSize = 12.sp)
            Field(t, "令牌") { t = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { TechButton("取消", onDismiss) }
                Box(Modifier.weight(1f)) { TechButton("保存", { onSave(u, t) }, primary = true) }
            }
        }
    }
}

@Composable
private fun Field(value: String, hint: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 16.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(KioskAmber),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(KioskBg).padding(10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, color = TextSecondary, fontSize = 16.sp)
            inner()
        },
    )
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

/** The technician PIN pad (same keys and rules as Aegis Vend / Timer). [onSubmit] false = wrong PIN. */
@Composable
fun FlexPinDialog(onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
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
