package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.ui.tip.CustomerTipPanel
import com.goldsky.ssp.ui.tip.TipPalette

/**
 * The customer's tip on this terminal: Iris handed over, or Ourea without an Eye
 * (design: https://claude.ai/artifact/B4cXVxmX1HRKfHdRcxo34q, "Tip"). Nearly full
 * screen, so it reads as the customer's screen, not a staff prompt; the only staff
 * control is a small Cancel. [basisCents]: before tax, after discounts (a split
 * check: this payment's part); [totalCents]: due before the tip. [staffNote]: the line
 * above the panel, telling staff to hand the device over (Ourea: turn the screen).
 */
@Composable
fun CustomerTipDialog(
    basisCents: Int,
    totalCents: Int,
    onTip: (Int) -> Unit,
    onCancel: () -> Unit,
    staffNote: String = stringResource(R.string.retail_tip_hand_over),
    review: com.goldsky.ssp.common.Tipping.Review? = null,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BoxWithConstraints {
            // A phone (Iris on a Q2) gets the whole screen; a counter screen a large card.
            val phone = maxWidth < 600.dp
            Surface(
                shape = if (phone) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
                color = colors.surface,
                modifier = if (phone) Modifier.fillMaxSize()
                else Modifier.widthIn(max = 720.dp).fillMaxWidth(0.9f).fillMaxHeight(0.88f)
            ) {
                Column(Modifier.padding(if (phone) 16.dp else 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(staffNote, color = colors.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.retail_tip_cancel), color = colors.onSurfaceVariant) }
                    }
                    CustomerTipPanel(
                        review = review,
                        basisCents = basisCents,
                        totalCents = totalCents,
                        palette = TipPalette(
                            tile = colors.background,
                            tileBorder = colors.surfaceVariant,
                            text = colors.onSurface,
                            subtext = colors.onSurfaceVariant,
                            accent = colors.primary,
                            onAccent = colors.onPrimary,
                        ),
                        onChosen = onTip,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
