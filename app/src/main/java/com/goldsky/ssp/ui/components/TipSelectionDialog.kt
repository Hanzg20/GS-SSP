package com.goldsky.ssp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * North American style Tip selector for restaurants.
 */
@Composable
fun TipSelectionDialog(
    subtotalCents: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPercentage by remember { mutableStateOf(18) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ADD A TIP?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 18, 20).forEach { pct ->
                        FilterChip(
                            selected = selectedPercentage == pct,
                            onClick = { selectedPercentage = pct },
                            label = { Text("$pct%") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                val tipAmount = (subtotalCents * selectedPercentage / 100.0).toInt()
                val total = subtotalCents + tipAmount
                
                Text(
                    "Tip: $${"%.2f".format(tipAmount / 100.0)}",
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "TOTAL: $${"%.2f".format(total / 100.0)}",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = { onConfirm(total) },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("AUTHORIZE PAYMENT", fontWeight = FontWeight.Bold)
                }
                
                TextButton(onClick = { onConfirm(subtotalCents) }) {
                    Text("No Tip, Thanks")
                }
            }
        }
    }
}
