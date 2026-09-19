package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.ui.components.NumericKeypad

@OptIn(ExperimentalMaterial3Api::class)
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
                Text("ADD A TIP?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 18, 20).forEach { pct ->
                        FilterChip(
                            selected = !isCustomMode && selectedPercentage == pct,
                            onClick = { isCustomMode = false; selectedPercentage = pct },
                            label = { Text("$pct%") }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                val tipCents = if (isCustomMode) {
                    customAmountStr.replace(".", "").toIntOrNull() ?: 0
                } else {
                    (subtotalCents * selectedPercentage / 100.0).toInt()
                }
                val total = subtotalCents + tipCents

                Text("TOTAL: $${total / 100.0}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = { onConfirm(total) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("AUTHORIZE", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
