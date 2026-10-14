package com.goldsky.ssp.ourea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import com.goldsky.ssp.ui.tip.CustomerTipPanel
import com.goldsky.ssp.ui.tip.TipPalette

private fun money(cents: Int) = "$" + "%.2f".format(cents / 100.0)

/**
 * The tip, chosen by the customer (design: https://claude.ai/artifact/B4cXVxmX1HRKfHdRcxo34q,
 * "Tip"). With an Ourea Eye the choice is made there and this screen only says the
 * customer is choosing -- never which tip, so nobody behind the counter watches it.
 * Without one (or if it stops answering) the same customer panel opens here, for the
 * screen to be turned round. [basisCents]: before tax, after discounts.
 */
@Composable
fun OureaTipStep(
    provider: IPaymentProvider,
    basisCents: Int,
    totalCents: Int,
    onTip: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var askHere by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        provider.requestTip(basisCents, totalCents) { answer ->
            when (answer) {
                is IPaymentProvider.TipAnswer.Chosen -> onTip(answer.tipCents)
                IPaymentProvider.TipAnswer.Unavailable -> askHere = true
            }
        }
        // Answered already: nothing to cancel (the provider ignores it).
        onDispose { provider.cancelTip() }
    }

    if (askHere) {
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(24.dp), color = OureaSurface, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.9f).fillMaxHeight(0.88f)) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ourea_tip_turn_screen), color = OureaTextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.ourea_tip_cancel), color = OureaTextSecondary) }
                    }
                    CustomerTipPanel(
                        basisCents = basisCents,
                        totalCents = totalCents,
                        palette = TipPalette(
                            tile = OureaBackground,
                            tileBorder = OureaSurfaceVariant,
                            text = OureaTextPrimary,
                            subtext = OureaTextSecondary,
                            accent = OureaGold,
                            onAccent = MaterialTheme.colorScheme.onPrimary,
                        ),
                        onChosen = onTip,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    } else {
        // Staff side while the customer chooses on the Eye: progress only.
        Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(24.dp), color = OureaSurface) {
                Column(Modifier.padding(28.dp).widthIn(min = 320.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(color = OureaGold)
                    Text(stringResource(R.string.ourea_tip_waiting_title), color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ourea_tip_waiting_desc, money(totalCents)), color = OureaTextSecondary, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.ourea_tip_cancel), color = OureaTextSecondary) }
                        OutlinedButton(onClick = { provider.cancelTip(); askHere = true }) { Text(stringResource(R.string.ourea_tip_ask_here)) }
                    }
                }
            }
        }
    }
}
