package com.goldsky.ssp.vending.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.goldsky.ssp.vending.ShowcaseViewModel
import com.goldsky.ssp.vending.ShowcaseViewModel.Item
import com.goldsky.ssp.vending.ShowcaseViewModel.State
import com.goldsky.ssp.vending.ui.components.NfcRadar
import com.goldsky.ssp.vending.ui.theme.*

/**
 * Showcase sale mode (see [ShowcaseViewModel]): six numbered items to a screen (more scroll up), pick one, PAY,
 * tap the card. A DEMO strip is always on top: the card is authorized and voided. The VIP page's black and
 * gold, big bold words, no explanations (owner, 2026-11-03). Sized for the Q3mini's ~320dp square screen.
 */
@Composable
fun ShowcaseContainer(viewModel: ShowcaseViewModel, french: Boolean, onToggleLanguage: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val items by viewModel.items.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val money = { cents: Int -> VendStrings.money(cents, french) }
    VendingTheme {
        Column(Modifier.fillMaxSize().background(KioskPage)) {
            Text(
                if (french) "DÉMO · AUCUN DÉBIT" else "DEMO · NOT CHARGED",
                color = KioskBg, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = SPACED,
                textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.fillMaxWidth().background(KioskAmber).padding(vertical = 2.dp),
            )
            Crossfade(targetState = state, label = "showcase", modifier = Modifier.weight(1f)) { st ->
                when (st) {
                    State.Choosing -> Choosing(items, selected, french, money, viewModel::select, viewModel::pay, onToggleLanguage)
                    is State.Authorizing -> Message(st.item, money) {
                        NfcRadar(size = 64.dp)
                        Spacer(Modifier.height(12.dp))
                        Big(if (french) "PRÉSENTEZ VOTRE CARTE" else "TAP YOUR CARD")
                    }
                    is State.Dispensing -> Message(st.item, money) {
                        CircularProgressIndicator(color = KioskEmerald, strokeWidth = 5.dp, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Big(if (french) "DISTRIBUTION…" else "DISPENSING…")
                    }
                    is State.Done -> Message(st.item, money) {
                        if (st.voided) {
                            Big(if (french) "APPROUVÉ" else "APPROVED", KioskEmerald)
                            Line(if (french) "Annulé · aucun débit" else "Voided · not charged")
                        } else {
                            Big(if (french) "ANNULATION IMPOSSIBLE" else "VOID FAILED", KioskRed)
                            Line(if (french) "Remboursement par le personnel" else "Staff will refund")
                        }
                    }
                    is State.Declined -> Message(st.item, money) {
                        Big(if (french) "NON PAYÉ" else "NOT PAID", KioskAmber)
                    }
                }
            }
        }
    }
}

@Composable
private fun Choosing(
    items: List<Item>, selected: Item?, french: Boolean, money: (Int) -> String,
    onSelect: (Item) -> Unit, onPay: () -> Unit, onToggleLanguage: () -> Unit,
) {
    // Six to a screen (3 x 2); more items scroll up.
    val grid = rememberLazyGridState()
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (french) "CHOISISSEZ" else "SELECT YOUR ITEM",
                color = KioskAmber, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = SPACED,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            // More below: an arrow, no words.
            if (grid.canScrollForward) {
                Text("▼", color = KioskAmber, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
            }
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(KioskCard).border(1.dp, KioskCardEdge, RoundedCornerShape(18.dp))
                    .clickable(onClick = onToggleLanguage).padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(if (french) "EN" else "FR", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tileHeight = (maxHeight - GAP) / 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(GAP), verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                itemsIndexed(items, key = { i, item -> "$i ${item.number}" }) { _, item ->
                    Tile(item, item == selected, money, Modifier.height(tileHeight)) { onSelect(item) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val ready = selected != null
        val bar = RoundedCornerShape(16.dp)
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(bar).background(kioskTile(best = ready))
                .border(1.dp, if (ready) KioskAmber else KioskCardEdge, bar).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.let { "${it.number} · ${money(it.cents)}" } ?: if (french) "CHOISISSEZ" else "PICK ONE",
                color = if (ready) KioskAmber else TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.height(44.dp).widthIn(min = 96.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (ready) KioskAmber else KioskAmber.copy(alpha = 0.3f))
                    .clickable(enabled = ready, onClick = onPay).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (french) "PAYER" else "PAY", color = KioskBg, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = SPACED) }
        }
    }
}

private val GAP = 6.dp
private val SPACED = 0.08.em

/** Like a VIP plan tile: dark, gold-edged when picked; number, picture, name, gold price. */
@Composable
private fun Tile(item: Item, selected: Boolean, money: (Int) -> String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(kioskTile(best = selected))
            .border(if (selected) 2.dp else 1.dp, if (selected) KioskAmber else KioskCardEdge, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val fallback: @Composable () -> Unit = {
                Text(item.emoji.ifEmpty { item.name.take(1).uppercase() }, fontSize = 44.sp, color = KioskAmber,
                    fontWeight = FontWeight.Bold)
            }
            if (item.imageUrl.isNullOrBlank()) fallback()
            else SubcomposeAsyncImage(
                model = item.imageUrl, contentDescription = item.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(), error = { fallback() }, loading = { fallback() },
            )
            Text(
                item.number, color = KioskBg, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
                    .clip(RoundedCornerShape(8.dp)).background(KioskAmber).padding(horizontal = 6.dp),
            )
        }
        Text(item.name, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(money(item.cents), color = KioskAmber, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun Message(item: Item, money: (Int) -> String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(money(item.cents), color = KioskAmber, fontSize = 52.sp, fontWeight = FontWeight.Bold)
        Text("${item.number} · ${item.name}", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun Big(text: String, color: Color = TextPrimary) =
    Text(text, color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = SPACED,
        textAlign = TextAlign.Center, lineHeight = 32.sp)

@Composable
private fun Line(text: String) =
    Text(text, color = TextSecondary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 6.dp))
