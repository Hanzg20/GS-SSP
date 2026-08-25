package com.goldsky.ssp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.ui.components.NumericKeypad

/**
 * Shared screen for manual amount entry.
 */
@Composable
fun ManualAmountScreen(onInitiatePayment: (String) -> Unit) {
    var amount by remember { mutableStateOf("0.00") }
    val currency = remember { DeviceRepository.getCurrencySymbol() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp), // Reduced from 16.dp
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Amount Display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(
                text = "ENTER AMOUNT ($currency)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "$$amount",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Numeric Keypad
        NumericKeypad(
            onNumberClick = { num ->
                val raw = amount.replace(".", "").replace(Regex("^0+"), "") + num
                val padded = raw.padStart(3, '0')
                amount = "${padded.dropLast(2)}.${padded.takeLast(2)}"
            },
            onBackSpace = {
                val raw = amount.replace(".", "").dropLast(1)
                val padded = raw.padStart(3, '0')
                amount = "${padded.dropLast(2)}.${padded.takeLast(2)}"
            },
            onConfirm = {
                onInitiatePayment(amount)
            }
        )
    }
}
