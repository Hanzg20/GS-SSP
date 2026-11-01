package com.goldsky.ssp.feature.retail.screens

import com.goldsky.ssp.feature.retail.ui.EmptyArt
import com.goldsky.ssp.feature.retail.ui.EmptyState
import com.goldsky.ssp.feature.retail.ui.glassCard
import com.goldsky.ssp.feature.retail.ui.shimmer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import com.goldsky.ssp.feature.retail.R
import androidx.compose.ui.res.stringResource
import com.goldsky.ssp.ui.theme.GsTheme
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
import com.goldsky.ssp.payment.PartialRefund
import com.goldsky.ssp.payment.StaffMember
import com.goldsky.ssp.payment.leftToRefund
import com.goldsky.ssp.payment.TransactionItem
import com.goldsky.ssp.payment.TransactionRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun money(cents: Int) = "$%.2f".format(cents / 100.0)
private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

@Composable
private fun methodLabel(m: String) = when (m) {
    "CASH" -> stringResource(R.string.retail_method_cash); "CREDIT_CARD" -> stringResource(R.string.retail_method_card)
    "DEBIT_CARD" -> stringResource(R.string.retail_method_debit); "VIP_CARD" -> stringResource(R.string.retail_method_vip)
    com.goldsky.ssp.payment.OnlinePaymentRepository.METHOD -> stringResource(R.string.retail_method_online); else -> m
}

/** Whole-sale refund is offered for paid card, cash and Ourea Scan online payments with nothing refunded yet. */
private fun refundable(o: OrderEntity) = o.status == "PAID" && o.refundedCents == 0 &&
    o.paymentMethod in setOf("CASH", "CREDIT_CARD", "DEBIT_CARD", com.goldsky.ssp.payment.OnlinePaymentRepository.METHOD)

/** Some items back: paid card / cash sales (not online -- the server refunds those whole), something left. */
private fun partlyRefundable(o: OrderEntity) = o.status == "PAID" && o.paymentMethod in setOf("CASH", "CREDIT_CARD", "DEBIT_CARD") &&
    leftToRefund(o.amountCents, o.tipCents, o.refundedCents) > 0

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
    // Some items back; null hides it. Called after approval.
    onPartialRefund: ((OrderEntity, StaffMember, PartialRefund) -> Unit)? = null,
    // Callback to open Day Report screen (managers only)
    onOpenDayReport: (() -> Unit)? = null,
    // Changes after a refund so the list reloads with the new status.
    reloadKey: Int = 0
) {
    val context = LocalContext.current
    var transactions by remember { mutableStateOf(emptyList<OrderEntity>()) }
    var selected by remember { mutableStateOf<OrderEntity?>(null) }
    // Null until the first read: an empty list then means "no sales", not "still loading".
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        val local = TransactionRepository.getAllLocal(context)
            .let { all -> if (onlyStaffId == null) all else all.filter { it.staffId == onlyStaffId } }
        // Managers also see this terminal's Ourea Scan online payments (cloud only; none offline).
        val online = if (onlyStaffId == null) com.goldsky.ssp.payment.OnlinePaymentRepository.recent().orEmpty() else emptyList()
        transactions = (local + online).sortedByDescending { it.createdAt }
        selected = selected?.let { s -> transactions.find { it.ecrRefNum == s.ecrRefNum } }
        loaded = true
    }


    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (onOpenDayReport != null && onlyStaffId == null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
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
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.retail_day_settlement_report),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (!loaded) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
                repeat(6) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).glassCard(RoundedCornerShape(12.dp), elevation = 1.dp).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(28.dp).shimmer(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.size(width = 90.dp, height = 16.dp).shimmer())
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.size(width = 140.dp, height = 11.dp).shimmer())
                        }
                        Box(Modifier.size(width = 48.dp, height = 14.dp).shimmer())
                    }
                }
            }
        } else if (transactions.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(EmptyArt.RECEIPT, stringResource(R.string.retail_no_sales_yet))
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                items(transactions, key = { it.ecrRefNum }) { tx ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = tx },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when (tx.paymentMethod) {
                                        "CASH" -> Icons.Default.Payments
                                        com.goldsky.ssp.payment.OnlinePaymentRepository.METHOD -> Icons.Default.QrCode
                                        else -> Icons.Default.CreditCard
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(money(tx.amountCents), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("${timeFmt.format(Date(tx.createdAt))} · ${methodLabel(tx.paymentMethod)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            StatusChip(tx.status, partlyRefunded = tx.refundedCents > 0)
                        }
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
            canRefundItems = onPartialRefund != null && partlyRefundable(tx),
            onRefundItems = { approver, refund -> onPartialRefund?.invoke(tx, approver, refund); selected = null },
            onDismiss = { selected = null }
        )
    }
}

@Composable
private fun StatusChip(status: String, partlyRefunded: Boolean = false) {
    val (label, color) = when (status) {
        "PAID" -> if (partlyRefunded) stringResource(R.string.retail_part_refunded) to GsTheme.colors.warning else "Paid" to GsTheme.colors.success
        "VOIDED" -> "Voided" to MaterialTheme.colorScheme.onSurfaceVariant
        "REFUNDED" -> "Refunded" to MaterialTheme.colorScheme.onSurfaceVariant
        "PENDING" -> "Pending" to GsTheme.colors.warning
        else -> status.lowercase().replaceFirstChar { it.uppercase() } to GsTheme.colors.danger
    }
    Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun SaleDetailDialog(
    tx: OrderEntity, canRefund: Boolean, onRefund: (StaffMember) -> Unit,
    canRefundItems: Boolean, onRefundItems: (StaffMember, PartialRefund) -> Unit, onDismiss: () -> Unit
) {
    var items by remember { mutableStateOf<List<TransactionItem>?>(null) }
    var itemsFailed by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf(false) }
    // Picking items to refund: line_no -> units; null = not picking.
    var picked by remember { mutableStateOf<Map<Int, Int>?>(null) }
    var approvingItems by remember { mutableStateOf<PartialRefund?>(null) }
    val partial = picked?.let { p -> items?.let { PartialRefund.of(it, p, tx.subtotalCents, leftToRefund(tx.amountCents, tx.tipCents, tx.refundedCents)) } }
    val textColor = MaterialTheme.colorScheme.onSurface
    val subTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(tx.ecrRefNum) {
        val r = TransactionRepository.fetchItems(tx.ecrRefNum)
        items = r; itemsFailed = r == null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(money(tx.amountCents), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                        color = textColor, modifier = Modifier.weight(1f))
                    StatusChip(tx.status, partlyRefunded = tx.refundedCents > 0)
                }
                Text("${timeFmt.format(Date(tx.createdAt))} · ${methodLabel(tx.paymentMethod)}", style = MaterialTheme.typography.bodySmall, color = subTextColor)
                Text(tx.ecrRefNum, style = MaterialTheme.typography.labelSmall, color = subTextColor)
                Divider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
                when {
                    items == null && !itemsFailed -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    itemsFailed -> Text(stringResource(R.string.retail_items_unavailable_offline), style = MaterialTheme.typography.bodySmall, color = subTextColor)
                    items!!.isEmpty() -> Text(stringResource(R.string.retail_no_item_detail_for_this), style = MaterialTheme.typography.bodySmall, color = subTextColor)
                    picked != null -> {
                        Text(stringResource(R.string.retail_refund_items_title), fontWeight = FontWeight.Bold, color = textColor)
                        items!!.forEach { i ->
                            val left = i.quantity - i.refunded_qty
                            val n = picked!![i.line_no] ?: 0
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(i.name + if (i.tax_rate_bps > 0) "  H" else "", Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium, color = if (left > 0) textColor else subTextColor, maxLines = 2)
                                IconButton(onClick = { picked = picked!! + (i.line_no to (n - 1).coerceAtLeast(0)) }, enabled = n > 0) { Icon(Icons.Default.Remove, null) }
                                Text("$n/$left", fontWeight = FontWeight.Bold, color = textColor)
                                IconButton(onClick = { picked = picked!! + (i.line_no to n + 1) }, enabled = n < left) { Icon(Icons.Default.Add, null) }
                            }
                        }
                    }
                    else -> items!!.forEach { i ->
                        Row {
                            Text("${i.quantity} × ${i.name}" + if (i.tax_rate_bps > 0) "  H" else "", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, color = textColor)
                            if (i.refunded_qty > 0) Text(stringResource(R.string.retail_back_qty, i.refunded_qty) + "  ",
                                style = MaterialTheme.typography.bodySmall, color = GsTheme.colors.warning)
                            Text(money(i.line_total_cents), style = MaterialTheme.typography.bodyMedium, color = textColor)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row { Text(stringResource(R.string.retail_subtotal), Modifier.weight(1f), color = subTextColor); Text(money(tx.subtotalCents), color = textColor) }
                Row { Text(stringResource(R.string.retail_tax), Modifier.weight(1f), color = subTextColor); Text(money(tx.taxCents), color = textColor) }
                if (tx.tipCents > 0) Row { Text(stringResource(R.string.retail_tip), Modifier.weight(1f), color = subTextColor); Text(money(tx.tipCents), color = textColor) }
                Row { Text(stringResource(R.string.retail_total), Modifier.weight(1f), fontWeight = FontWeight.Bold, color = textColor); Text(money(tx.amountCents), fontWeight = FontWeight.Bold, color = textColor) }
                if (tx.refundedCents > 0) Row {
                    Text(stringResource(R.string.retail_refunded_so_far), Modifier.weight(1f), color = GsTheme.colors.warning)
                    Text("-" + money(tx.refundedCents), color = GsTheme.colors.warning)
                }
                Spacer(Modifier.height(16.dp))
                if (picked != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { picked = null }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.retail_cancel)) }
                    Button(
                        onClick = { approvingItems = partial },
                        enabled = partial != null,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = GsTheme.colors.danger)
                    ) { Text(partial?.let { stringResource(R.string.retail_refund_items_amount, money(it.amountCents)) } ?: stringResource(R.string.retail_refund_items_none), fontWeight = FontWeight.Bold, maxLines = 2) }
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRefundItems && !items.isNullOrEmpty()) {
                    OutlinedButton(
                        onClick = { picked = emptyMap() },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GsTheme.colors.danger),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GsTheme.colors.danger)
                    ) { Text(stringResource(R.string.retail_refund_items), fontWeight = FontWeight.Bold) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canRefund) {
                        OutlinedButton(
                            onClick = { approving = true },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GsTheme.colors.danger),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GsTheme.colors.danger)
                        ) {
                            Icon(Icons.Default.Undo, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.retail_refund), fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors()
                    ) { Text(stringResource(R.string.retail_close), fontWeight = FontWeight.Bold) }
                }
                }
            }
        }
    }

    approvingItems?.let { r ->
        ManagerApprovalDialog(
            action = if (tx.paymentMethod == "CASH") stringResource(R.string.retail_refund_items_cash_action, money(r.amountCents))
                     else stringResource(R.string.retail_refund_items_card_action, money(r.amountCents)),
            onApproved = { approvingItems = null; onRefundItems(it, r) },
            onDismiss = { approvingItems = null }
        )
    }

    if (approving) {
        ManagerApprovalDialog(
            action = if (tx.paymentMethod == "CASH") stringResource(R.string.retail_refund_cash_action, money(tx.amountCents))
                     else stringResource(R.string.retail_refund_card_action, money(tx.amountCents)),
            onApproved = { approving = false; onRefund(it) },
            onDismiss = { approving = false }
        )
    }
}

