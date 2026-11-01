package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.payment.OfflineCardManager
import com.goldsky.ssp.payment.hardware.IPaymentProvider
import com.goldsky.ssp.ui.theme.GsTheme
import kotlinx.coroutines.delay

/**
 * Sends offline-approved card sales to the bank once the network is back: tries
 * every minute while any are waiting (OfflineCardManager). A card the bank refused
 * is said on screen -- that sale was never paid.
 */
@Composable
fun OfflineCardUploader(provider: IPaymentProvider) {
    val context = LocalContext.current
    LaunchedEffect(provider) {
        while (true) {
            delay(60_000)
            if (OfflineCardManager.pending(context).isEmpty()) continue
            val r = runCatching { OfflineCardManager.upload(context, provider) }.getOrNull() ?: continue
            if (r.declined.isNotEmpty()) {
                android.widget.Toast.makeText(context, context.getString(R.string.offline_card_refused, r.declined.size, r.declined.joinToString()),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Settings > Offline card payments: off by default; the risk, a per-sale limit, a count limit, what's waiting. */
@Composable
fun OfflineCardSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(OfflineCardManager.isEnabled(context)) }
    var limit by remember { mutableStateOf((OfflineCardManager.limitCents(context) / 100).toString()) }
    var max by remember { mutableStateOf(OfflineCardManager.maxCount(context).toString()) }
    val waiting = remember { OfflineCardManager.pending(context) }

    fun save() {
        OfflineCardManager.configure(context, enabled,
            (limit.toIntOrNull() ?: (OfflineCardManager.DEFAULT_LIMIT_CENTS / 100)) * 100,
            max.toIntOrNull() ?: OfflineCardManager.DEFAULT_MAX_COUNT)
    }

    Text(stringResource(R.string.offline_card_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.offline_card_switch), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.offline_card_desc), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; save() })
            }
            if (enabled) {
                Text(stringResource(R.string.offline_card_risk), style = MaterialTheme.typography.bodySmall, color = GsTheme.colors.danger)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(limit, { limit = it.filter { c -> c.isDigit() }.take(4); save() }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.offline_card_limit)) }, prefix = { Text("$") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(max, { max = it.filter { c -> c.isDigit() }.take(3); save() }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.offline_card_max)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            if (waiting.isNotEmpty()) {
                Text(stringResource(R.string.offline_card_waiting, waiting.size, "%.2f".format(waiting.sumOf { it.amountCents } / 100.0)),
                    style = MaterialTheme.typography.bodySmall, color = GsTheme.colors.warning)
            }
        }
    }
}
