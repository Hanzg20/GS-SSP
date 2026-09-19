package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.ui.components.NumericKeypad

/**
 * Premium Staff Login screen.
 */
@Composable
fun StaffPinLockScreen(
    onUnlock: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val maxPinLength = 4

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("STAFF LOGIN", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(64.dp))

        NumericKeypad(
            onNumberClick = { num ->
                if (pin.length < maxPinLength) {
                    pin += num
                    if (pin.length == maxPinLength) {
                        onUnlock(pin)
                    }
                }
            },
            onBackSpace = {
                if (pin.isNotEmpty()) pin = pin.dropLast(1)
            },
            onConfirm = {
                if (pin.length == maxPinLength) onUnlock(pin)
            }
        )
    }
}
