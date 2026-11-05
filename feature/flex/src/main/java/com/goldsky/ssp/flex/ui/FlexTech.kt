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
    products: Int,
    onSystemCheck: () -> Unit,
    onPaymentTests: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(KioskBg).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("技术员 · Aegis Flex $version", color = KioskAmber, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text("SN $deviceSn", color = TextSecondary, fontSize = 12.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
            Text("出货控制器", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(controller, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KioskCard).padding(10.dp)) {
            Text("商品", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (products > 0) "$products 个（Cael 中本店的 VEND 商品）" else "没有商品：请在 Cael 中给本店添加 VEND 商品",
                color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        TechButton("系统检查", onSystemCheck)
        TechButton("支付测试（真实交易）", onPaymentTests)
        TechButton("返回售卖界面", onExit, primary = true)
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
