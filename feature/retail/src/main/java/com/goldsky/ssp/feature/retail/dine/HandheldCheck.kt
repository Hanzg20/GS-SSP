package com.goldsky.ssp.feature.retail.dine

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.feature.retail.screens.RetailStoreScreen
import com.goldsky.ssp.feature.retail.ui.ManagerApprovalDialog
import com.goldsky.ssp.feature.retail.ui.OrderNoteDialog
import com.goldsky.ssp.feature.retail.viewmodel.RetailViewModel

private fun money(cents: Int) = "$" + "%.2f".format(cents / 100.0)

/**
 * A table's check on a handheld (Iris / Q2): "Ordered" (what the kitchen has,
 * bill, pay) and "Order" (the product list for the next round, sent with
 * one button). [round] is the round's own cart, never the counter's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandheldCheck(
    dine: DineViewModel,
    round: RetailViewModel,
    staffName: String?,
    onPay: () -> Unit,
    onScan: (() -> Unit)? = null,
    isScanning: Boolean = false
) {
    val detail = dine.check ?: return
    val context = LocalContext.current
    var tab by remember { mutableStateOf(if (detail.items.isEmpty()) 1 else 0) }
    var voiding by remember { mutableStateOf<com.goldsky.ssp.payment.DineRepository.Item?>(null) }
    var editingNote by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val pending = round.cart.sumOf { it.quantity }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (round.cart.isNotEmpty()) leaving = true else dine.closeCheck() }) {
                Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.dine_back_to_floor))
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.dine_table_header, detail.table?.name ?: "?", detail.check.guests, detail.check.check_no),
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text(stringResource(R.string.dine_tab_ordered, money(detail.totalCents))) })
            FilterChip(selected = tab == 1, onClick = { tab = 1 },
                label = { Text(if (pending > 0) stringResource(R.string.dine_tab_order_n, pending) else stringResource(R.string.dine_tab_order)) })
        }
        if (tab == 0) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                checkItemRows(detail) { voiding = it }
                if (detail.items.isEmpty()) item {
                    Text(stringResource(R.string.dine_nothing_yet_handheld), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            Surface(shadowElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    CheckTotals(detail)
                    Spacer(Modifier.height(8.dp))
                    when {
                        detail.items.isEmpty() && pending == 0 -> OutlinedButton(onClick = { dine.cancelEmptyCheck() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.dine_cancel_table))
                        }
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { dine.printBill(context) }, enabled = detail.items.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp)) {
                                Icon(Icons.Default.Receipt, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.dine_print_bill))
                            }
                            Button(onClick = onPay, enabled = detail.balanceCents > 0, modifier = Modifier.weight(1.3f).height(48.dp)) {
                                Text(stringResource(R.string.dine_pay_check, money(detail.balanceCents)), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f)) {
                RetailStoreScreen(
                    onCheckout = { dine.sendRound(context, round, staffName); tab = 0 },
                    viewModel = round,
                    onScan = onScan,
                    isScanning = isScanning,
                    checkoutLabel = stringResource(R.string.dine_send_kitchen, pending),
                    bottomBar = {
                        Row(
                            Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .clickable { editingNote = true }.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(round.orderNote.ifBlank { stringResource(R.string.dine_round_note) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (round.orderNote.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                )
            }
        }
    }

    if (editingNote) OrderNoteDialog(initial = round.orderNote, onDone = { round.orderNote = it.trim(); editingNote = false })
    voiding?.let { item ->
        ManagerApprovalDialog(
            action = stringResource(R.string.dine_void_title, "${item.quantity} × ${item.displayName}"),
            onApproved = { approver -> voiding = null; dine.voidItem(item, approver.name) },
            onDismiss = { voiding = null }
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.dine_round_not_sent_title, pending)) },
            text = { Text(stringResource(R.string.dine_round_not_sent_desc)) },
            confirmButton = { Button(onClick = { leaving = false; dine.sendRound(context, round, staffName); tab = 0 }) { Text(stringResource(R.string.dine_round_send)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaving = false; round.clearCart(); dine.closeCheck() }) {
                        Text(stringResource(R.string.dine_round_discard), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.dine_round_stay)) }
                }
            }
        )
    }
}
