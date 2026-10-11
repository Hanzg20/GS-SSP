package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.layout.*
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
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
        title = { Text(stringResource(R.string.retail_receipt_details)) },
        text = {
            Column {
                Text("Ref: ${transaction.ecrRefNum}")
                Text("Amount: $${transaction.amountCents / 100.0}")
            }
        },
        confirmButton = {
            TextButton(onClick = onPrint) {
                Icon(Icons.Default.Print, null)
                Text(stringResource(R.string.retail_print))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.retail_close_2))
            }
        }
    )
}
