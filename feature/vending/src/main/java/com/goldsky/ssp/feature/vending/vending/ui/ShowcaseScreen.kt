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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.goldsky.ssp.vending.ShowcaseViewModel
import com.goldsky.ssp.vending.ShowcaseViewModel.Item
import com.goldsky.ssp.vending.ShowcaseViewModel.State
import com.goldsky.ssp.vending.ui.components.NfcRadar
import com.goldsky.ssp.vending.ui.theme.*

/**
 * Showcase sale mode (see [ShowcaseViewModel]): six numbered items to a screen (more scroll up), pick one, PAY,
 * tap the card. A DEMO strip is always on top: the card is authorized and voided.
 * Sized for the Q3mini's ~320dp square screen.
 */
@Composable
fun ShowcaseContainer(viewModel: ShowcaseViewModel, french: Boolean, onToggleLanguage: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val items by viewModel.items.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val money = { cents: Int -> VendStrings.money(cents, french) }
    VendingTheme {
        Surface(Modifier.fillMaxSize(), color = KioskBg) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    if (french) "DÉMO · carte autorisée puis annulée, jamais débitée"
                    else "DEMO · card authorized then voided, never charged",
                    color = KioskBg, fontSize = 10.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().background(KioskAmber).padding(vertical = 2.dp),
                )
                Crossfade(targetState = state, label = "showcase", modifier = Modifier.weight(1f)) { st ->
                    when (st) {
                        State.Choosing -> Choosing(items, selected, french, money, viewModel::select, viewModel::pay, onToggleLanguage)
                        is State.Authorizing -> Message(st.item, money) {
                            CircularProgressIndicator(color = KioskAmber, strokeWidth = 4.dp, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(10.dp))
                            Big(if (french) "Présentez votre carte" else "Tap your card")
                            Small(if (french) "Sur le lecteur, sous l'écran" else "On the reader below the screen")
                        }
                        is State.Dispensing -> Message(st.item, money) {
                            CircularProgressIndicator(color = KioskEmerald, strokeWidth = 4.dp, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(10.dp))
                            Big(if (french) "Distribution…" else "Dispensing…")
                            Small(if (french) "Démo : aucun article ne tombe" else "Demo: no item drops")
                        }
                        is State.Done -> Message(st.item, money) {
                            if (st.voided) {
                                Big(if (french) "Approuvé · annulé" else "Approved · voided", KioskEmerald)
                                Small(if (french) "Démo : rien n'a été débité" else "Demo: nothing was charged")
                            } else {
                                Big(if (french) "Annulation impossible" else "Could not void", KioskRed)
                                Small(if (french) "Le personnel vous remboursera" else "Staff will refund this sale")
                            }
                        }
                        is State.Declined -> Message(st.item, money) {
                            Big(if (french) "Paiement non effectué" else "Not paid", KioskAmber)
                            Small(if (french) "Rien n'a été débité" else "Nothing was charged")
                        }
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
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (french) "CHOISISSEZ" else "SELECT YOUR ITEM",
                color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f),
            )
            if (grid.canScrollForward) {
                Text(
                    if (french) "▲ Glissez : plus" else "▲ Swipe for more",
                    color = KioskAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp),
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable(onClick = onToggleLanguage).padding(horizontal = 10.dp, vertical = 3.dp),
            ) { Text(if (french) "EN" else "FR", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tileHeight = (maxHeight - GAP) / 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(GAP), verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                itemsIndexed(items, key = { i, item -> "$i ${item.number}" }) { i, item ->
                    Tile(item, i, item == selected, money, Modifier.height(tileHeight)) { onSelect(item) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(KioskCard).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NfcRadar(size = 28.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                selected?.let { "${it.number} · ${it.name} · ${money(it.cents)}" } ?: if (french) "Touchez un article" else "Tap an item",
                color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            val ready = selected != null
            Box(
                Modifier.height(34.dp).widthIn(min = 76.dp).clip(RoundedCornerShape(17.dp))
                    .background(if (ready) KioskAmber else KioskAmber.copy(alpha = 0.3f))
                    .clickable(enabled = ready, onClick = onPay).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (french) "PAYER" else "PAY", color = KioskBg, fontSize = 16.sp, fontWeight = FontWeight.Black) }
        }
    }
}

private val GAP = 6.dp

@Composable
private fun Tile(item: Item, index: Int, selected: Boolean, money: (Int) -> String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clip(shape).background(Color.White)
            .border(if (selected) 3.dp else 0.dp, if (selected) KioskAmber else Color.Transparent, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Brush.verticalGradient(tint(index))),
            contentAlignment = Alignment.Center,
        ) {
            val fallback: @Composable () -> Unit = {
                Text(item.emoji.ifEmpty { item.name.take(1).uppercase() }, fontSize = 46.sp, color = KioskBg)
            }
            if (item.imageUrl.isNullOrBlank()) fallback()
            else SubcomposeAsyncImage(
                model = item.imageUrl, contentDescription = item.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(), error = { fallback() }, loading = { fallback() },
            )
            Text(
                item.number, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xCC1E293B)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Text(item.name, color = Color(0xFF1E293B), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp))
        Text(money(item.cents), color = Color(0xFF1E293B), fontSize = 12.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** A soft background per tile, so tiles without a picture still differ. */
private fun tint(index: Int): List<Color> {
    val palette = listOf(
        Color(0xFFFFE0B2) to Color(0xFFFFB74D), Color(0xFFE3F2FD) to Color(0xFF90CAF9),
        Color(0xFFE0F7FA) to Color(0xFF80DEEA), Color(0xFFFCE4EC) to Color(0xFFF48FB1),
        Color(0xFFECEFF1) to Color(0xFFB0BEC5), Color(0xFFF3E5F5) to Color(0xFFCE93D8),
    )
    val (a, b) = palette[index % palette.size]
    return listOf(a, b)
}

@Composable
private fun Message(item: Item, money: (Int) -> String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("${item.name} · ${money(item.cents)}", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Big(text: String, color: Color = TextPrimary) =
    Text(text, color = color, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)

@Composable
private fun Small(text: String) =
    Text(text, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
