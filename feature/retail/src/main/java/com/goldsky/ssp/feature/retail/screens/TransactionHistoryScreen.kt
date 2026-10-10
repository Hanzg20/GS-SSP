package com.goldsky.ssp.feature.retail.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.db.OrderEntity
import com.goldsky.ssp.feature.retail.ui.ManagerApprovalDialog
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.StaffMember
import com.goldsky.ssp.payment.TransactionItem
import com.goldsky.ssp.payment.TransactionRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun money(cents: Int) = "$%.2f".format(cents / 100.0)
private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

private fun methodLabel(m: String) = when (m) {
    "CASH" -> "Cash"; "CREDIT_CARD" -> "Card"; "DEBIT_CARD" -> "Debit"; "VIP_CARD" -> "VIP card"; else -> m
}

/** Whole-sale refund is offered for paid card and cash sales. */
private fun refundable(o: OrderEntity) = o.status == "PAID" && o.paymentMethod in setOf("CASH", "CREDIT_CARD", "DEBIT_CARD")

/**
 * Sales recorded on this terminal, newest first. Tap one for its lines (from
 * the cloud) and, for a paid card/cash sale, a whole-sale refund -- which
 * needs a manager's PIN on the spot ([onRefund] does the money part).
 */
@Composable
fun TransactionHistoryScreen(
    // Set for non-managers: only the sales this person took (older rows with
    // no staff recorded are hidden from them too).
    onlyStaffId: String? = null,
    // Null hides refunds (e.g. a shell without them). Called after approval.
    onRefund: ((OrderEntity, StaffMember) -> Unit)? = null,
    // Callback to open Day Report screen (managers only)
    onOpenDayReport: (() -> Unit)? = null,
    // Changes after a refund so the list reloads with the new status.
    reloadKey: Int = 0
) {
    val context = LocalContext.current
    var transactions by remember { mutableStateOf(emptyList<OrderEntity>()) }
    var selected by remember { mutableStateOf<OrderEntity?>(null) }

    LaunchedEffect(reloadKey) {
        transactions = TransactionRepository.getAllLocal(context)
            .let { all -> if (onlyStaffId == null) all else all.filter { it.staffId == onlyStaffId } }
        selected = selected?.let { s -> transactions.find { it.ecrRefNum == s.ecrRefNum } }
    }

    val isCyber = DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER

    Column(
        Modifier
            .fillMaxSize()
            .background(if (isCyber) Color(0xFF0B0F19) else MaterialTheme.colorScheme.background)
    ) {
        if (onOpenDayReport != null && onlyStaffId == null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (isCyber) Color(0xFF162032) else MaterialTheme.colorScheme.primaryContainer,
                border = if (isCyber) androidx.compose.foundation.BorderStroke(1.dp, com.goldsky.ssp.feature.retail.ui.GoldSkyCyan) else null,
                onClick = { onOpenDayReport() }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Assessment,
                            contentDescription = null,
                            tint = if (isCyber) com.goldsky.ssp.feature.retail.ui.GoldSkyCyan else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Day Settlement Report",
                            fontWeight = FontWeight.Bold,
                            color = if (isCyber) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (isCyber) com.goldsky.ssp.feature.retail.ui.GoldSkyCyan else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        if (transactions.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No sales yet", color = if (isCyber) Color.LightGray else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                items(transactions, key = { it.ecrRefNum }) { tx ->
                    if (isCyber) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = tx },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = Color(0xFF162032),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF26354F))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (tx.paymentMethod == "CASH") Icons.Default.Payments else Icons.Default.CreditCard,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(money(tx.amountCents), fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("${timeFmt.format(Date(tx.createdAt))} · ${methodLabel(tx.paymentMethod)}", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                                    }
                                }
                                StatusChip(tx.status)
                            }
                        }
                    } else {
                        ListItem(
                            modifier = Modifier.clickable { selected = tx },
                            headlineContent = { Text(money(tx.amountCents), fontWeight = FontWeight.Bold) },
                            supportingContent = { Text("${timeFmt.format(Date(tx.createdAt))} · ${methodLabel(tx.paymentMethod)}") },
                            leadingContent = { Icon(if (tx.paymentMethod == "CASH") Icons.Default.Payments else Icons.Default.CreditCard, null) },
                            trailingContent = { StatusChip(tx.status) }
                        )
                        Divider()
                    }
                }
            }
        }
    }

    selected?.let { tx ->
        SaleDetailDialog(
            tx = tx,
            canRefund = onRefund != null && refundable(tx),
            onRefund = { approver -> onRefund?.invoke(tx, approver); selected = null },
            onDismiss = { selected = null }
        )
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        "PAID" -> "Paid" to Color(0xFF2E7D32)
        "VOIDED" -> "Voided" to Color(0xFF6D4C41)
        "REFUNDED" -> "Refunded" to Color(0xFF6D4C41)
        "PENDING" -> "Pending" to Color(0xFFF9A825)
        else -> status.lowercase().replaceFirstChar { it.uppercase() } to Color(0xFFC62828)
    }
    Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun SaleDetailDialog(tx: OrderEntity, canRefund: Boolean, onRefund: (StaffMember) -> Unit, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf<List<TransactionItem>?>(null) }
    var itemsFailed by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf(false) }
    val isCyber = DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER
    val textColor = if (isCyber) Color.White else MaterialTheme.colorScheme.onSurface
    val subTextColor = if (isCyber) Color.LightGray else MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(tx.ecrRefNum) {
        val r = TransactionRepository.fetchItems(tx.ecrRefNum)
        items = r; itemsFailed = r == null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = if (isCyber) Color(0xFF162032) else MaterialTheme.colorScheme.surface,
            border = if (isCyber) androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF26354F)) else null,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(money(tx.amountCents), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                        color = textColor, modifier = Modifier.weight(1f))
                    StatusChip(tx.status)
                }
                Text("${timeFmt.format(Date(tx.createdAt))} · ${methodLabel(tx.paymentMethod)}", style = MaterialTheme.typography.bodySmall, color = subTextColor)
                Text(tx.ecrRefNum, style = MaterialTheme.typography.labelSmall, color = subTextColor)
                Divider(Modifier.padding(vertical = 10.dp), color = if (isCyber) Color(0xFF26354F) else MaterialTheme.colorScheme.outlineVariant)
                when {
                    items == null && !itemsFailed -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = if (isCyber) com.goldsky.ssp.feature.retail.ui.GoldSkyCyan else MaterialTheme.colorScheme.primary)
                    itemsFailed -> Text("Items unavailable offline", style = MaterialTheme.typography.bodySmall, color = subTextColor)
                    items!!.isEmpty() -> Text("No item detail for this sale", style = MaterialTheme.typography.bodySmall, color = subTextColor)
                    else -> items!!.forEach { i ->
                        Row {
                            Text("${i.quantity} × ${i.name}" + if (i.tax_rate_bps > 0) "  H" else "", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, color = textColor)
                            Text(money(i.line_total_cents), style = MaterialTheme.typography.bodyMedium, color = textColor)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row { Text("Subtotal", Modifier.weight(1f), color = subTextColor); Text(money(tx.subtotalCents), color = textColor) }
                Row { Text("Tax", Modifier.weight(1f), color = subTextColor); Text(money(tx.taxCents), color = textColor) }
                if (tx.tipCents > 0) Row { Text("Tip", Modifier.weight(1f), color = subTextColor); Text(money(tx.tipCents), color = textColor) }
                Row { Text("Total", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = textColor); Text(money(tx.amountCents), fontWeight = FontWeight.Bold, color = textColor) }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canRefund) {
                        OutlinedButton(
                            onClick = { approving = true },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                        ) {
                            Icon(Icons.Default.Undo, null); Spacer(Modifier.width(6.dp)); Text("REFUND", fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = if (isCyber) ButtonDefaults.buttonColors(containerColor = com.goldsky.ssp.feature.retail.ui.GoldSkyCyan, contentColor = Color.Black) else ButtonDefaults.buttonColors()
                    ) { Text("CLOSE", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    if (approving) {
        val how = if (tx.paymentMethod == "CASH") "hand back ${money(tx.amountCents)} in cash" else "refund ${money(tx.amountCents)} to the card"
        ManagerApprovalDialog(
            action = "Refund the whole sale: $how.",
            onApproved = { approving = false; onRefund(it) },
            onDismiss = { approving = false }
        )
    }
}

