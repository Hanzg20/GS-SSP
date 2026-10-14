package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Done
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goldsky.ssp.feature.retail.R
import com.goldsky.ssp.model.ModifierGroup
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.ProductModifier

/**
 * A dish's options before it goes into the cart (spice level, add-ons,
 * sweetness): a "pick one" group behaves as radio buttons, other groups as
 * toggles up to their max; a required group must be answered before Add.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModifierSelectionSheet(
    product: Product,
    onConfirm: (List<ProductModifier>) -> Unit,
    onDismiss: () -> Unit
) {
    val groups = remember(product) { product.modifierGroups }
    // Selected options per group, in the menu's order.
    val picked = remember(product) { mutableStateMapOf<String, List<ProductModifier>>() }
    val missing = groups.filter { it.required && picked[it.id].isNullOrEmpty() }
    val extra = picked.values.flatten().sumOf { it.price_cents }

    fun toggle(group: ModifierGroup, option: ProductModifier) {
        val now = picked[group.id].orEmpty()
        val max = group.max_select
        picked[group.id] = when {
            option in now -> if (group.required && max == 1) now else now - option
            max == 1 -> listOf(option)
            max != null && now.size >= max -> now
            else -> now + option
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 8.dp) {
            Column(Modifier.widthIn(max = 560.dp).padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(product.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(money(product.price_cents), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    groups.forEach { group ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(group.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                val max = group.max_select
                                val hint = when {
                                    group.required && max != null && max > 1 -> stringResource(R.string.retail_mod_required_up_to, max)
                                    group.required -> stringResource(R.string.retail_mod_required)
                                    max == 1 -> stringResource(R.string.retail_mod_pick_one)
                                    max != null && max > 1 -> stringResource(R.string.retail_mod_up_to, max)
                                    else -> stringResource(R.string.retail_mod_optional)
                                }
                                Text(hint, fontSize = 12.sp,
                                    color = if (group in missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                group.options.forEach { option ->
                                    FilterChip(
                                        selected = option in picked[group.id].orEmpty(),
                                        onClick = { toggle(group, option) },
                                        // Clearly on or off from a step back: filled when picked.
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        leadingIcon = if (option in picked[group.id].orEmpty()) {
                                            { Icon(androidx.compose.material.icons.Icons.Filled.Done, null, Modifier.size(18.dp)) }
                                        } else null,
                                        label = {
                                            Text(if (option.price_cents > 0) "${option.name}  +${money(option.price_cents)}" else option.name,
                                                modifier = Modifier.padding(vertical = 8.dp))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Narrow padding and one line each: on a 360 dp handheld (Q2) the default 24 dp
                    // padding wrapped "Cancel" into "Canc / el" and the Add label onto two lines.
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        OneLineText(stringResource(R.string.retail_mod_cancel))
                    }
                    Button(
                        onClick = { onConfirm(groups.flatMap { g -> picked[g.id].orEmpty() }) },
                        enabled = missing.isEmpty(),
                        modifier = Modifier.weight(2f).height(52.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        OneLineText(stringResource(R.string.retail_mod_add, money(product.price_cents + extra)), FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun money(cents: Int) = "$" + "%.2f".format(cents / 100.0)

/** A button label on one line, shrinking a little (down to 11 sp) when it doesn't fit. */
@Composable
private fun OneLineText(text: String, weight: FontWeight? = null) {
    var size by remember(text) { mutableStateOf(14.sp) }
    Text(text, fontWeight = weight, fontSize = size, maxLines = 1, softWrap = false,
        onTextLayout = { if (it.didOverflowWidth && size > 11.sp) size = (size.value - 1).sp })
}
