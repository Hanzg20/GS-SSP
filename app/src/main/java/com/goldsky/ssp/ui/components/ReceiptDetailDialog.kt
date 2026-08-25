package com.goldsky.ssp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.db.OrderEntity
import com.goldsky.ssp.payment.DeviceRepository
import java.text.SimpleDateFormat
import java.util.*

/**
 * Digital Receipt Preview with Reprint capability.
 * Obsidian aesthetic with "Thermal Paper" layout.
 */
@Composable
fun ReceiptDetailDialog(
    order: OrderEntity,
    onReprint: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(order.createdAt))
    val currencySymbol = remember { DeviceRepository.getCurrencySymbol().split(" ").last() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "RECEIPT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 4.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Thermal Paper Look-alike
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(DeviceRepository.getStoreName(), fontWeight = FontWeight.Bold, color = Color.Black)
                        Text(dateStr, fontSize = 10.sp, color = Color.Gray)
                        Text("--------------------------------", color = Color.LightGray)
                        
                        ReceiptLine("SUBTOTAL", order.subtotalCents, currencySymbol)
                        ReceiptLine("SALES TAX", order.taxCents, currencySymbol)
                        ReceiptLine("TIP", order.tipCents, currencySymbol)
                        
                        Text("--------------------------------", color = Color.LightGray)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("TOTAL", fontWeight = FontWeight.Black, color = Color.Black)
                            Text("$currencySymbol${"%.2f".format(order.amountCents / 100.0)}", fontWeight = FontWeight.Black, color = Color.Black)
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("REF: ${order.ecrRefNum.take(12)}", fontSize = 8.sp, color = Color.DarkGray)
                        Text("STATUS: ${order.status}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onReprint,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Print, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("REPRINT RECEIPT")
                }
                
                TextButton(onClick = onDismiss) {
                    Text("CLOSE", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun ReceiptLine(label: String, cents: Int, currency: String = "$") {
    if (cents <= 0) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = Color.DarkGray)
        Text("$currency${"%.2f".format(cents / 100.0)}", fontSize = 12.sp, color = Color.Black)
    }
}
