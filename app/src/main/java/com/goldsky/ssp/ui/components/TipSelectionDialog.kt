package com.goldsky.ssp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
    var customAmountStr by remember { mutableStateOf("0.00") }
    var isCustomMode by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ADD A TIP?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                
                // Tip Options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val options = listOf(15, 18, 20)
                    options.forEach { pct ->
                        FilterChip(
                            selected = !isCustomMode && selectedPercentage == pct,
                            onClick = { 
                                isCustomMode = false
                                selectedPercentage = pct 
                            },
                            label = { Text("$pct%") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    FilterChip(
                        selected = isCustomMode,
                        onClick = { isCustomMode = true },
                        label = { Text("Custom") },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Logic to calculate final tip and total
                val tipCents = if (isCustomMode) {
                    customAmountStr.replace(".", "").toIntOrNull() ?: 0
                } else {
                    (subtotalCents * selectedPercentage / 100.0).toInt()
                }
                val total = subtotalCents + tipCents

                if (isCustomMode) {
                    // Simple Custom Amount Input
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ENTER TIP AMOUNT", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = "$$customAmountStr",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        NumericKeypad(
                            modifier = Modifier.scale(0.8f), // Scale down to fit dialog
                            onNumberClick = { num ->
                                val raw = customAmountStr.replace(".", "").replace(Regex("^0+"), "") + num
                                val padded = raw.padStart(3, '0')
                                customAmountStr = "${padded.dropLast(2)}.${padded.takeLast(2)}"
                            },
                            onBackSpace = {
                                val raw = customAmountStr.replace(".", "").dropLast(1)
                                val padded = raw.padStart(3, '0')
                                customAmountStr = "${padded.dropLast(2)}.${padded.takeLast(2)}"
                            },
                            onConfirm = { /* Just closes the keypad focus if any */ }
                        )
                    }
                } else {
                    Text(
                        "Tip: $${"%.2f".format(tipCents / 100.0)}",
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "TOTAL: $${"%.2f".format(total / 100.0)}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = { onConfirm(total) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("AUTHORIZE $${"%.2f".format(total / 100.0)}", fontWeight = FontWeight.Bold)
                }
                
                TextButton(onClick = { onConfirm(subtotalCents) }) {
                    Text("No Tip, Thanks", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}
