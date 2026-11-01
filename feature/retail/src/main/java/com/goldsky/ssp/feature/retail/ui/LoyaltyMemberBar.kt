package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Loyalty
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
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel
import com.goldsky.ssp.payment.LoyaltyRepository
import com.goldsky.ssp.ui.theme.GsTheme
import kotlinx.coroutines.launch

private fun money(cents: Int) = "$%.2f".format(cents / 100.0)

/**
 * The cart's loyalty member (counter sales, when the store has loyalty on in Cael):
 * "Member" to find or sign up the customer by phone; then their points and, when
 * they have enough, a switch to use them for the reward (an order discount).
 */
@Composable
fun LoyaltyMemberBar(viewModel: RetailViewModel, modifier: Modifier = Modifier) {
    var settings by remember { mutableStateOf(LoyaltyRepository.settings()) }
    LaunchedEffect(Unit) { settings = LoyaltyRepository.refreshSettings() }
    if (!settings.enabled) return
    var finding by remember { mutableStateOf(false) }
    val m = viewModel.member

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Loyalty, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        if (m == null) {
            TextButton(onClick = { finding = true }) { Text(stringResource(R.string.loyalty_add_member), fontWeight = FontWeight.Bold) }
        } else {
            Column(Modifier.weight(1f)) {
                Text(m.label + " · " + stringResource(R.string.loyalty_points, m.points), fontWeight = FontWeight.Bold, maxLines = 1)
                val (pts, cents) = viewModel.availableReward()
                if (pts > 0) Text(stringResource(R.string.loyalty_use, pts, money(cents)), style = MaterialTheme.typography.bodySmall,
                    color = if (viewModel.usePoints) GsTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(stringResource(R.string.loyalty_need, settings.reward_points, money(settings.reward_cents)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (viewModel.availableReward().first > 0) Switch(checked = viewModel.usePoints, onCheckedChange = { viewModel.useMemberPoints(it) })
            IconButton(onClick = { viewModel.chooseMember(null) }) { Icon(Icons.Default.Close, stringResource(R.string.loyalty_remove)) }
        }
    }

    if (finding) FindMemberDialog(onPicked = { viewModel.chooseMember(it); finding = false }, onDismiss = { finding = false })
}

/** Phone -> the member, or sign them up (name optional). */
@Composable
fun FindMemberDialog(onPicked: (LoyaltyRepository.Member) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // null = not looked up yet; true = no such member (offer to join)
    var notFound by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun find() {
        busy = true; error = null
        scope.launch {
            LoyaltyRepository.lookup(phone).fold(
                onSuccess = { m -> if (m != null) onPicked(m) else notFound = true },
                onFailure = { error = context.getString(R.string.retail_can_t_reach_the_server) }
            )
            busy = false
        }
    }

    fun join() {
        busy = true; error = null
        scope.launch {
            val (m, why) = LoyaltyRepository.join(phone, name)
            busy = false
            if (m != null) onPicked(m)
            else error = when (why) {
                null -> context.getString(R.string.retail_can_t_reach_the_server)
                "bad_phone" -> context.getString(R.string.loyalty_bad_phone)
                "loyalty_off" -> context.getString(R.string.loyalty_off)
                else -> why
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Loyalty, null) },
        title = { Text(stringResource(R.string.loyalty_find_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c in "+-() " }.take(20); notFound = false },
                    label = { Text(stringResource(R.string.loyalty_phone)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                if (notFound) {
                    Text(stringResource(R.string.loyalty_not_found), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.loyalty_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { if (notFound) join() else find() }, enabled = !busy && LoyaltyRepository.validPhone(phone)) {
                Text(stringResource(if (notFound) R.string.loyalty_join else R.string.loyalty_find), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retail_cancel_2)) } }
    )
}
