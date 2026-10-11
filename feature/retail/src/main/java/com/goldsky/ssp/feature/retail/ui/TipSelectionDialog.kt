package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.BorderStroke
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
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
import com.goldsky.ssp.payment.DeviceRepository
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
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 6.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.retail_add_a_tip),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 15, 18, 20).forEach { pct ->
                        FilterChip(
                            selected = !isCustomMode && selectedPercentage == pct,
                            onClick = { isCustomMode = false; selectedPercentage = pct },
                            label = { Text(if (pct == 0) "No tip" else "$pct%") }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                val tipCents = if (isCustomMode) {
                    customAmountStr.replace(".", "").toIntOrNull() ?: 0
                } else {
                    Math.round(subtotalCents * selectedPercentage / 100.0).toInt()
                }
                val total = subtotalCents + tipCents

                Text(
                    "TOTAL: $%.2f".format(total / 100.0),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = { onConfirm(total) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Text(stringResource(R.string.retail_authorize), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

