package com.goldsky.ssp.feature.laundry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.payment.TestSale

private val TBg = Color(0xF20E141D)
private val TCard = Color(0xFF1E2836)
private val TEdge = Color(0xFF33415A)
private val TText = Color(0xFFF1F5F9)
private val TSoft = Color(0xFF93A1B8)
private val TTeal = Color(0xFF14C2AE)
private val TAmber = Color(0xFFF5B23D)
private val TRose = Color(0xFFF0506E)

/** Maintenance PIN (Cael settings.maintenance_pin). [onSubmit] true = right PIN. */
@Composable
fun LaundryPinPad(onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(TBg).swallowTouches(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Technician", color = TText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(4) { i -> Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) TTeal else TEdge)) }
            }
            Text(if (wrong) "Wrong PIN" else " ", color = TRose, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("✕", "0", "⌫")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(width = 88.dp, height = 52.dp).clip(RoundedCornerShape(12.dp)).background(TCard)
                                .clickable {
                                    when (k) {
                                        "✕" -> onDismiss()
                                        "⌫" -> pin = pin.dropLast(1)
                                        else -> if (pin.length < 4) {
                                            pin += k
                                            if (pin.length == 4) { wrong = !onSubmit(pin); if (wrong) pin = "" }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) { Text(k, color = TText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

/** Card test, relay module check and test pulses, what couldn't be sold. */
@Composable
fun LaundryTechPanel(
    s: LaundryViewModel.UiState,
    version: String,
    onArm: (Int) -> Unit,
    onDisarm: () -> Unit,
    onCheckModule: () -> Unit,
    onTestPulse: (Int) -> Unit,
    onExit: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(TBg).swallowTouches()) {
        Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Technician", color = TText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Aegis Laundry $version · ${if (s.demoMode) "demo machines" else "${s.machines.size} machines"}", color = TSoft, fontSize = 12.sp)
                }
                Pill("Exit", TTeal, onExit)
            }

            Section("Card test (real card, small amount)") {
                Text(
                    "The next sale charges this amount on a real card and is recorded as TEST_ (kept out of revenue in Cael). " +
                        if (s.demoMode) "Demo machines: the machine start is simulated." else "The chosen machine really starts.",
                    color = TSoft, fontSize = 12.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TestSale.AMOUNTS.forEach { c ->
                        val on = s.testArmedCents == c
                        Pill(TestSale.format(c), if (on) TAmber else TEdge, { onArm(c) }, filled = on)
                    }
                    if (s.testArmedCents != null) Pill("Cancel", TRose, onDisarm)
                }
                if (s.testArmedCents != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Armed: exit, choose a machine and pay ${TestSale.format(s.testArmedCents)}", color = TAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Section("Relay module (RS485 Modbus)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (s.moduleOk) { true -> "● Answering"; false -> "● Not answering"; null -> "● Not checked" },
                        color = when (s.moduleOk) { true -> TTeal; false -> TRose; null -> TSoft },
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    )
                    Pill("Check", TTeal, onCheckModule)
                }
                Spacer(Modifier.height(8.dp))
                Text("Test pulse (one coin) on a channel:", color = TSoft, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..LaundryMachineConfig.MAX_RELAY).forEach { ch ->
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(TCard).border(1.dp, TEdge, RoundedCornerShape(10.dp)).clickable { onTestPulse(ch) },
                            contentAlignment = Alignment.Center
                        ) { Text("$ch", color = TText, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            s.techMessage?.let { Text(it, color = TText, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }

            if (s.problems.isNotEmpty()) {
                Section("Products not sold (fix in Cael)") {
                    s.problems.forEach { Text("• $it", color = TAmber, fontSize = 12.sp) }
                }
            }
        }
    }
}

/**
 * An overlay must take every touch on its background: otherwise a tap there reaches the
 * machine grid underneath (clickable(enabled = false) does not consume).
 */
private fun Modifier.swallowTouches() = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TCard).padding(12.dp)) {
        Text(title.uppercase(), color = TSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Pill(text: String, color: Color, onClick: () -> Unit, filled: Boolean = false) {
    Text(
        text, color = if (filled) Color(0xFF0E141D) else color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (filled) color else Color.Transparent)
            .border(1.5.dp, color, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    )
}
