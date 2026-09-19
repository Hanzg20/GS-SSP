package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.db.OrderEntity

@Composable
fun ReceiptDetailDialog(
    transaction: OrderEntity,
    onPrint: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receipt Details") },
        text = {
            Column {
                Text("Ref: ${transaction.ecrRefNum}")
                Text("Amount: $${transaction.amountCents / 100.0}")
            }
        },
        confirmButton = {
            TextButton(onClick = onPrint) {
                Icon(Icons.Default.Print, null)
                Text("Print")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
