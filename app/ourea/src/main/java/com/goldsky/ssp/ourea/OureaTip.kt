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
import com.goldsky.ssp.payment.hardware.IPaymentProvider

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
    cardNext: Boolean = false,
    // Dine-in: the bill the customer checks and confirms first.
    review: com.goldsky.ssp.common.Tipping.Review? = null,
    onTip: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var askHere by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        provider.requestTip(basisCents, totalCents, cardNext, review) { answer ->
            when (answer) {
                is IPaymentProvider.TipAnswer.Chosen -> onTip(answer.tipCents)
                IPaymentProvider.TipAnswer.Unavailable -> askHere = true
            }
        }
        // Answered already: nothing to cancel (the provider ignores it).
        onDispose { provider.cancelTip() }
    }

    if (askHere) {
        com.goldsky.ssp.feature.retail.ui.CustomerTipDialog(
            basisCents = basisCents,
            totalCents = totalCents,
            onTip = onTip,
            onCancel = onCancel,
            staffNote = stringResource(R.string.ourea_tip_turn_screen),
            review = review
        )
    } else {
        // Staff side while the customer chooses on the Eye: progress only.
        Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(24.dp), color = OureaSurface) {
                Column(Modifier.padding(28.dp).widthIn(min = 320.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(color = OureaGold)
                    Text(stringResource(if (review != null) R.string.ourea_review_waiting_title else R.string.ourea_tip_waiting_title), color = OureaTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
