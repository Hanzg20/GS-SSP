package com.goldsky.ssp.ourea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goldsky.ssp.payment.Discount
import com.goldsky.ssp.payment.DiscountPreset
import com.goldsky.ssp.payment.DiscountPresetRepository

private fun money(cents: Int) = "$" + "%.2f".format(cents / 100.0)

/**
 * The discount panel for one cart line or the whole order (design:
 * https://claude.ai/artifact/B4cXVxmX1HRKfHdRcxo34q, "Ourea · Discount panel").
 * Left: the store's presets (Cael > Catalog > Discounts). Right: a typed-in
 * discount, $ or %, with a reason. A preset marked "manager", or a typed-in one
 * over 10% / $10, asks for a manager PIN first; the approver is kept on the
 * discount (transactions.discounts).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OureaDiscountDialog(
    target: String,
    baseCents: Int,
    forOrder: Boolean,
    current: Discount?,
    onApply: (Discount?) -> Unit,
    onDismiss: () -> Unit,
) {
    val allPresets by DiscountPresetRepository.presets.collectAsState()
    val presets = remember(allPresets, forOrder) { allPresets.filter { if (forOrder) it.appliesToOrder() else it.appliesToLine() } }
    var picked by remember { mutableStateOf<DiscountPreset?>(presets.firstOrNull { it.id == current?.presetId }) }
    var percent by remember { mutableStateOf(current?.percentBps != null && current.presetId == null) }
    var digits by remember {
        mutableStateOf(
            when {
                current == null || current.presetId != null -> ""
                current.percentBps != null -> (current.percentBps!! / 100).toString()
                else -> current.amountCents.toString()
            }
        )
    }
    var reason by remember { mutableStateOf(current?.takeIf { it.presetId == null }?.reason) }
    var approving by remember { mutableStateOf<Discount?>(null) }

    val reasons = listOf(
        stringResource(R.string.ourea_disc_reason_service),
        stringResource(R.string.ourea_disc_reason_price_match),
        stringResource(R.string.ourea_disc_reason_damaged),
        stringResource(R.string.ourea_disc_reason_other),
    )
    val customLabel = stringResource(R.string.ourea_disc_custom)

    val custom: Discount? = digits.toIntOrNull()?.takeIf { it > 0 }?.let { n ->
        if (percent) n.takeIf { it <= 100 }?.let { Discount(customLabel, percentBps = it * 100, reason = reason) }
        else Discount(customLabel, amountCents = n, reason = reason)
    }
    val chosen: Discount? = picked?.toDiscount() ?: custom
    val off = chosen?.offCents(baseCents) ?: 0
    val customNeedsReason = picked == null && custom != null && reason == null
    val needsManager = when {
        picked != null -> picked!!.requires_manager
        custom != null -> DiscountPresetRepository.needsManager(custom, baseCents)
        else -> false
    }

    fun press(key: String) {
        picked = null
        digits = when (key) {
            "⌫" -> digits.dropLast(1)
            else -> (digits + key).trimStart('0').take(if (percent) 3 else 6)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = OureaSurface, modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth(0.92f)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ourea_disc_title), color = OureaTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.ourea_disc_applies_to, target, money(baseCents)), color = OureaTextSecondary, fontSize = 14.sp)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.ourea_cancel), tint = OureaTextSecondary) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.heightIn(max = 460.dp)) {
                    // Presets
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ourea_disc_presets), color = OureaTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (presets.isEmpty()) Text(stringResource(R.string.ourea_disc_no_presets), color = OureaTextSecondary, fontSize = 14.sp)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(presets, key = { it.id }) { p ->
                                val selected = picked?.id == p.id
                                val value = p.percent_bps?.let { "${it / 100}%" } ?: money(p.amount_cents ?: 0)
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                                        .border(if (selected) 2.dp else 1.dp, if (selected) OureaGold else OureaSurfaceVariant, RoundedCornerShape(14.dp))
                                        .clickable { picked = if (selected) null else p; if (!selected) digits = "" }
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(p.name, color = OureaTextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    if (p.requires_manager) {
                                        Icon(Icons.Default.Lock, stringResource(R.string.ourea_disc_manager), tint = OureaAmber, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(if (selected) "$value · −${money(p.toDiscount().offCents(baseCents))}" else value,
                                        color = if (selected) OureaTextPrimary else OureaTextSecondary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // Typed-in
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.ourea_disc_custom), color = OureaTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = !percent, onClick = { percent = false; digits = ""; picked = null },
                                label = { Text("$", fontSize = 18.sp, fontWeight = FontWeight.Bold) })
                            FilterChip(selected = percent, onClick = { percent = true; digits = ""; picked = null },
                                label = { Text("%", fontSize = 18.sp, fontWeight = FontWeight.Bold) })
                            val shown = if (percent) "${digits.ifEmpty { "0" }}%" else money(digits.toIntOrNull() ?: 0)
                            Text(shown, color = OureaTextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(OureaBackground).padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫")).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { key ->
                                    OutlinedButton(onClick = { press(key) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, OureaTextSecondary.copy(alpha = 0.35f))) {
                                        if (key == "⌫") Icon(Icons.Default.Backspace, stringResource(R.string.ourea_disc_delete), tint = OureaTextPrimary)
                                        else Text(key, color = OureaTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        Text(stringResource(R.string.ourea_disc_reason), color = OureaTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            reasons.forEach { r ->
                                FilterChip(selected = reason == r, onClick = { reason = if (reason == r) null else r }, label = { Text(r) })
                            }
                        }
                    }
                }

                Divider(color = OureaSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Lock, null, tint = OureaAmber, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.ourea_disc_limit_hint), color = OureaTextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (current != null) {
                        TextButton(onClick = { onApply(null) }) { Text(stringResource(R.string.ourea_disc_remove), color = OureaRed) }
                    }
                    Button(
                        onClick = { chosen?.let { if (needsManager) approving = it else onApply(it) } },
                        enabled = chosen != null && off > 0 && !customNeedsReason,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(52.dp)
                    ) {
                        if (needsManager) { Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                        Text(
                            if (customNeedsReason) stringResource(R.string.ourea_disc_pick_reason)
                            else stringResource(R.string.ourea_disc_apply, money(off)),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    approving?.let { d ->
        com.goldsky.ssp.feature.retail.ui.ManagerApprovalDialog(
            action = stringResource(R.string.ourea_disc_approve_action, d.label, money(d.offCents(baseCents)), target),
            onApproved = { manager -> approving = null; onApply(d.copy(approvedBy = manager.name)) },
            onDismiss = { approving = null }
        )
    }
}
