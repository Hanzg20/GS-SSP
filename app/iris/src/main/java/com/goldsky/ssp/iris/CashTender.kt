package com.goldsky.ssp.iris

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Cash at the counter / table (Iris, 2026-11-01): what the customer handed
 * over, and the change. Quick amounts are the exact total and the next bills
 * a customer is likely to hand over for it.
 */
object CashTender {
    private val BILLS = listOf(5_00, 10_00, 20_00, 50_00, 100_00)

    /** Exact total first, then the next whole $5/$10/$20/$50/$100 above it, ascending, at most [max]. */
    fun quickAmounts(totalCents: Int, max: Int = 4): List<Int> {
        if (totalCents <= 0) return emptyList()
        val above = BILLS.map { bill -> (totalCents + bill - 1) / bill * bill }.filter { it > totalCents }
        return (listOf(totalCents) + above).distinct().sorted().take(max)
    }

    /** Keypad digit appended to an amount in cents; null past $100,000. */
    fun append(cents: Int, digit: Int): Int? = (cents.toLong() * 10 + digit).takeIf { it <= 100_000_00 }?.toInt()
}

private fun money(cents: Int) = "$" + "%.2f".format(cents / 100.0)

@Composable
fun CashTenderDialog(totalCents: Int, onConfirm: (receivedCents: Int) -> Unit, onDismiss: () -> Unit) {
    var received by remember { mutableStateOf(0) }
    val change = received - totalCents
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 8.dp) {
            // Scrolls: title + amount + quick row + keypad + buttons is taller than a
            // 360 dp handheld's screen.
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.iris_cash_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.iris_cash_due, money(totalCents)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(money(received), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (change >= 0) stringResource(R.string.iris_cash_change, money(change))
                    else stringResource(R.string.iris_cash_short, money(-change)),
                    color = if (change >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    fontWeight = if (change >= 0) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CashTender.quickAmounts(totalCents).forEach { amt ->
                        OutlinedButton(
                            onClick = { received = amt },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                        ) { Text(if (amt == totalCents) stringResource(R.string.iris_cash_exact) else money(amt), fontSize = 13.sp, maxLines = 1) }
                    }
                }
                com.goldsky.ssp.ui.components.NumericKeypad(
                    onNumberClick = { d -> CashTender.append(received, d.toInt())?.let { received = it } },
                    onBackSpace = { received /= 10 },
                    onConfirm = { if (received >= totalCents) onConfirm(received) }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.iris_cancel)) }
                    Button(onClick = { onConfirm(received) }, enabled = received >= totalCents, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.iris_cash_take), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Card or cash, for a counter sale (a delivery card collection and a table's split already chose). */
@Composable
fun PayMethodDialog(totalCents: Int, onCard: () -> Unit, onCash: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.iris_pay_how, money(totalCents))) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCard, modifier = Modifier.weight(1f).height(64.dp)) {
                    Text(stringResource(R.string.iris_pay_card), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(onClick = onCash, modifier = Modifier.weight(1f).height(64.dp)) {
                    Text(stringResource(R.string.iris_pay_cash), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.iris_cancel)) } }
    )
}
