package com.goldsky.ssp.feature.timer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.feature.timer.HoldTestViewModel.Circuit
import com.goldsky.ssp.feature.timer.HoldTestViewModel.HoldMode

// 70s straddles the ~65.5s a 16-bit ms counter would wrap at.
private val HOLD_PRESETS_S = listOf(10L, 70L, 240L, 300L)

@Composable
fun HoldTestScreen(vm: HoldTestViewModel) {
    val s by vm.state.collectAsState()
    // One LazyColumn for the whole page: the Q3mini's screen is ~480x480, so
    // a fixed Column clipped everything below the second button row.
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("Aegis Timer 输出实测 · vendor=${s.vendor}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }
        // Countdown + FORCE OFF stay pinned at the top while scrolling.
        stickyHeaderCompat {
            Column(Modifier.background(Color(0xFF101418)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CountdownPanel(s)
                Button(
                    onClick = vm::forceOff,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                ) { Text("FORCE OFF（继电器 port ${s.port}）", fontWeight = FontWeight.Bold) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("端口")
                listOf(0, 1).forEach { p -> Chip("port $p", s.port == p, enabled = !s.running) { vm.setPort(p) } }
                Spacer(Modifier.width(8.dp))
                Label("脉冲 voltage")
                listOf(0, 1).forEach { v -> Chip("$v", s.pulseVoltage == v, enabled = !s.running) { vm.setPulseVoltage(v) } }
            }
            Text(
                "voltage: 0 = 待机高电平/输出低，1 = 待机低电平/输出高。切换会改变 PIN1/2 的待机电平，先用万用表确认。",
                color = Color(0xFF9AA4AE), fontSize = 11.sp,
            )
        }
        HoldMode.values().forEach { mode ->
            item {
                Label(mode.label)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HOLD_PRESETS_S.forEach { sec ->
                        OutlinedButton(onClick = { vm.startHold(mode, sec * 1000) }, enabled = !s.running, modifier = Modifier.weight(1f)) {
                            Text("${sec}s")
                        }
                    }
                }
            }
        }
        item {
            Label("模拟投币脉冲（若接的是投币器信号线）")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Circuit.PULSE, Circuit.RELAY).forEach { c ->
                    listOf(100L, 500L).forEach { w ->
                        OutlinedButton(
                            onClick = { vm.startPulseTrain(c, 4, w) }, enabled = !s.running,
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp),
                        ) {
                            Text("${if (c == Circuit.PULSE) "PIN1" else "RLY"}\n4×${w}ms", fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("日志（logcat -s ${HoldTestViewModel.TAG} WizarPosGpio）")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::clearLog) { Text("清空") }
            }
        }
        items(s.log) { line ->
            Text(
                line, color = Color(0xFFB9F6CA), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 6.dp),
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.stickyHeaderCompat(content: @Composable () -> Unit) =
    stickyHeader { content() }

@Composable
private fun CountdownPanel(s: HoldTestViewModel.UiState) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1C2630)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (s.running) {
            Text(formatMmSs(s.remainingMs), color = Color(0xFF4FC3F7), fontSize = 56.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("已运行 ${"%.1f".format(s.elapsedMs / 1000.0)}s", color = Color.White, fontSize = 14.sp)
            Text(s.runningLabel.orEmpty(), color = Color(0xFF9AA4AE), fontSize = 12.sp)
        } else {
            Text("空闲", color = Color(0xFF9AA4AE), fontSize = 28.sp)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Color(0xFFCFD8DC), fontSize = 13.sp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(text) })
}

private fun formatMmSs(ms: Long): String {
    val total = (ms + 999) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
