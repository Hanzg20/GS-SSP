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
    // Set: the customer first checks the bill and confirms it, then picks the tip.
    review: Tipping.Review? = null,
) {
    var custom by remember { mutableStateOf(false) }
    var reviewed by remember(review) { mutableStateOf(review == null) }
    BoxWithConstraints(modifier) {
        // 480dp square on the Eye, a phone on Iris, a dialog on Ourea: the tiles take the room there is.
        val compact = maxWidth < 420.dp || maxHeight < 520.dp
        if (!reviewed && review != null) ReviewBill(review, palette, compact) { reviewed = true }
        else if (custom) CustomTip(basisCents, palette, compact, onBack = { custom = false }, onChosen = onChosen)
        else TipOptions(basisCents, totalCents, palette, compact, onCustom = { custom = true }, onChosen = onChosen)
    }
}

/**
 * Calm, not pushy (owner's call 2026-10-14, after the research on tip screens: Angus
 * Reid 2026 -- 93% annoyed by tip prompts at the counter; Quebec Bill 72 -- options
 * shown uniformly, neutral words): the total is the big figure, the tip is a small
 * optional add-on under it. Five buttons of one size and style -- 10% 12% 15%, then
 * Custom and No Tip -- moderate in size with room around them, nothing preselected.
 * The owner keeps one quiet thank-you line for local staff under the buttons.
 */
@Composable
private fun TipOptions(basisCents: Int, totalCents: Int, palette: TipPalette, compact: Boolean, onCustom: () -> Unit, onChosen: (Int) -> Unit) {
    val gap = if (compact) 10.dp else 14.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Every button one column of a 3-column grid, whatever the screen.
        val cell = ((maxWidth - gap * 2) / 3).coerceAtMost(if (compact) 120.dp else 150.dp)
        val height = if (compact) 64.dp else 84.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.tip_total, money(totalCents)), color = palette.text, fontSize = if (compact) 26.sp else 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            Text(stringResource(R.string.tip_title), color = palette.subtext, fontSize = if (compact) 15.sp else 18.sp)
            Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Tipping.options(basisCents).forEach { o ->
                    Tile(palette, Modifier.width(cell).height(height), onClick = { onChosen(o.tipCents) }) {
                        if (o.percent != null) {
                            Text("${o.percent}%", color = palette.text, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(money(o.tipCents), color = palette.subtext, fontSize = if (compact) 12.sp else 14.sp, maxLines = 1)
                        } else {
                            Text(money(o.tipCents).removeSuffix(".00"), color = palette.text, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(gap))
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Tile(palette, Modifier.width(cell).height(height), onClick = onCustom) {
                    Text(stringResource(R.string.tip_custom), color = palette.text, fontSize = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Tile(palette, Modifier.width(cell).height(height), onClick = { onChosen(0) }) {
                    Text(stringResource(R.string.tip_no_tip), color = palette.text, fontSize = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
            Text(stringResource(R.string.tip_thanks), color = palette.subtext, fontSize = if (compact) 13.sp else 15.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * "Please check your bill": what is being paid, line by line, and the total, with one
 * plain Confirm. Wrong? The customer tells the staff, who cancel on their screen --
 * nothing here changes the bill.
 */
@Composable
private fun ReviewBill(review: Tipping.Review, palette: TipPalette, compact: Boolean, onConfirm: () -> Unit) {
    val small = if (compact) 13.sp else 15.sp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.tip_review_title), color = palette.text, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Bold)
            if (review.title.isNotBlank()) Text(review.title, color = palette.subtext, fontSize = small)
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(palette.tile)
                .border(1.dp, palette.tileBorder, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            items(review.lines.size) { i ->
                val l = review.lines[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${l.quantity} × ${l.name}", color = palette.text, fontSize = small, modifier = Modifier.weight(1f), maxLines = 2)
                    Text(money(l.amountCents), color = palette.text, fontSize = small)
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tip_review_subtotal), color = palette.subtext, fontSize = small, modifier = Modifier.weight(1f))
                Text(money(review.subtotalCents), color = palette.subtext, fontSize = small)
            }
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tip_review_tax), color = palette.subtext, fontSize = small, modifier = Modifier.weight(1f))
                Text(money(review.taxCents), color = palette.subtext, fontSize = small)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tip_review_total), color = palette.text, fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(money(review.totalCents), color = palette.text, fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            Modifier.fillMaxWidth().height(if (compact) 52.dp else 64.dp).clip(RoundedCornerShape(14.dp))
                .background(palette.accent).clickable(onClick = onConfirm),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.tip_review_confirm, money(review.totalCents)), color = palette.onAccent, fontSize = if (compact) 17.sp else 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(stringResource(R.string.tip_review_help), color = palette.subtext, fontSize = if (compact) 12.sp else 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
