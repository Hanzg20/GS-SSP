package com.goldsky.ssp.ui.screens

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
 * Forces a 4-digit PIN entry to unlock the POS.
 */
@Composable
fun StaffPinLockScreen(
    onUnlock: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val maxPinLength = 4

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock, 
            contentDescription = null, 
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            "STAFF LOGIN",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp
        )
        
        Text(
            "Enter your 4-digit PIN to access terminal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(48.dp))

        // PIN Indicators (Dots)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(maxPinLength) { index ->
                val isFilled = index < pin.length
                Surface(
                    modifier = Modifier.size(20.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (isFilled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (!isFilled) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                ) {}
            }
        }

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
