package com.goldsky.ssp.ui.tip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.common.Tipping
import com.goldsky.ssp.core.ui.R

/** The colours of the screen the panel sits on (Eye: navy and gold; Ourea / Iris: their theme). */
data class TipPalette(
    val tile: Color,
    val tileBorder: Color,
    val text: Color,
    val subtext: Color,
    val accent: Color,
    val onAccent: Color,
)

private fun money(cents: Int) = "$" + "%.2f".format(cents / 100.0)

/**
 * The customer's tip choice, on the screen the customer holds or faces (Ourea Eye,
 * Iris handed over, or Ourea turned round when there's no Eye). Design:
 * https://claude.ai/artifact/B4cXVxmX1HRKfHdRcxo34q, "Tip". So that a small tip or
 * none never feels awkward: the wording is neutral ("Tip (optional)"), "No tip" is
 * a tile like the rest, nothing is preselected, no countdown, and the next screen
 * is the same whatever was picked. Options are [Tipping.options] of [basisCents]
 * (before tax, after discounts); [totalCents] is what's due before the tip.
 * Tapping a tile is the choice: [onChosen] with the tip in cents (0 = no tip).
 */
@Composable
fun CustomerTipPanel(
    basisCents: Int,
    totalCents: Int,
    palette: TipPalette,
    onChosen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var custom by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        // 480dp square on the Eye, a phone on Iris, a dialog on Ourea: the tiles take the room there is.
        val compact = maxWidth < 420.dp || maxHeight < 520.dp
        if (custom) CustomTip(basisCents, palette, compact, onBack = { custom = false }, onChosen = onChosen)
        else TipOptions(basisCents, totalCents, palette, compact, onCustom = { custom = true }, onChosen = onChosen)
    }
}

@Composable
private fun TipOptions(basisCents: Int, totalCents: Int, palette: TipPalette, compact: Boolean, onCustom: () -> Unit, onChosen: (Int) -> Unit) {
    val gap = if (compact) 10.dp else 14.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.tip_title), color = palette.text, fontSize = if (compact) 24.sp else 30.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.tip_total, money(totalCents)), color = palette.subtext, fontSize = if (compact) 15.sp else 17.sp)
        }
        // 10% 12% 15% Custom in one row; "No tip" under them, as wide as the row.
        Row(Modifier.fillMaxWidth().weight(1.5f), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Tipping.options(basisCents).forEach { o ->
                Tile(palette, Modifier.weight(1f).fillMaxHeight(), onClick = { onChosen(o.tipCents) }) {
                    if (o.percent != null) {
                        Text("${o.percent}%", color = palette.text, fontSize = if (compact) 26.sp else 36.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(money(o.tipCents), color = palette.subtext, fontSize = if (compact) 13.sp else 17.sp, maxLines = 1)
                    } else {
                        Text(money(o.tipCents).removeSuffix(".00"), color = palette.text, fontSize = if (compact) 26.sp else 36.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Tile(palette, Modifier.weight(1f).fillMaxHeight(), onClick = onCustom) {
                Text(stringResource(R.string.tip_custom), color = palette.text, fontSize = if (compact) 17.sp else 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Tile(palette, Modifier.fillMaxWidth().weight(0.8f), onClick = { onChosen(0) }) {
            Text(stringResource(R.string.tip_no_tip), color = palette.text, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            stringResource(R.string.tip_thanks), color = palette.subtext, fontSize = if (compact) 14.sp else 16.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun Tile(palette: TipPalette, modifier: Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(palette.tile)
            .border(1.dp, palette.tileBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun CustomTip(basisCents: Int, palette: TipPalette, compact: Boolean, onBack: () -> Unit, onChosen: (Int) -> Unit) {
    var percent by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    val n = digits.toIntOrNull() ?: 0
    val tip = if (percent) Tipping.percentOf(basisCents, n * 100) else n
    val max = Tipping.maxCustomCents(basisCents)

    fun press(key: String) {
        val next = if (key == "⌫") digits.dropLast(1) else (digits + key).trimStart('0')
        val value = next.toIntOrNull() ?: 0
        // Past the limit the key does nothing: a slip, not a choice.
        if (if (percent) value <= Tipping.MAX_CUSTOM_PERCENT else value <= max) digits = next
    }

    val gap = if (compact) 6.dp else 10.dp
    val keyHeight: Dp = if (compact) 44.dp else 60.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tip_custom_title), color = palette.text, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            listOf(false, true).forEach { p ->
                val on = percent == p
                Box(
                    Modifier.padding(start = 8.dp).size(if (compact) 44.dp else 52.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) palette.accent else palette.tile)
                        .border(1.dp, if (on) palette.accent else palette.tileBorder, RoundedCornerShape(12.dp))
                        .clickable { if (!on) { percent = p; digits = "" } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (p) "%" else "$", color = if (on) palette.onAccent else palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (percent) "${digits.ifEmpty { "0" }}%" else money(n),
                color = palette.text, fontSize = if (compact) 32.sp else 44.sp, fontWeight = FontWeight.Bold
            )
            Text(
                when {
                    percent && n > 0 -> stringResource(R.string.tip_of_amount, money(tip))
                    percent -> stringResource(R.string.tip_enter)
                    else -> stringResource(R.string.tip_max, money(max))
                },
                color = palette.subtext, fontSize = 13.sp, textAlign = TextAlign.Center
            )
        }
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (percent) "" else "00", "0", "⌫"))
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { key ->
                    Box(
                        Modifier.weight(1f).height(keyHeight).clip(RoundedCornerShape(12.dp))
                            .then(if (key.isEmpty()) Modifier else Modifier.background(palette.tile).clickable { press(key) }),
                        contentAlignment = Alignment.Center
                    ) {
                        if (key == "⌫") Icon(Icons.Default.Backspace, stringResource(R.string.tip_delete), tint = palette.text)
                        else Text(key, color = palette.text, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Box(
                Modifier.weight(1f).height(keyHeight).clip(RoundedCornerShape(14.dp))
                    .border(1.dp, palette.tileBorder, RoundedCornerShape(14.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.tip_back), color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.weight(2f).height(keyHeight).clip(RoundedCornerShape(14.dp))
                    .background(if (tip > 0) palette.accent else palette.tile)
                    .clickable(enabled = tip > 0) { onChosen(tip) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (tip > 0) stringResource(R.string.tip_add, money(tip)) else stringResource(R.string.tip_enter),
                    color = if (tip > 0) palette.onAccent else palette.subtext, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
