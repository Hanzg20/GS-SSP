package com.goldsky.ssp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldsky.ssp.model.ModifierGroup
import com.goldsky.ssp.model.Product
import com.goldsky.ssp.model.ProductModifier

/**
 * Premium bottom sheet for selecting product modifiers (e.g. coffee size, toppings).
 * Obsidian & Gold aesthetic.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModifierSelectionSheet(
    product: Product,
    onConfirm: (List<ProductModifier>) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedModifiers = remember { mutableStateListOf<ProductModifier>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "CUSTOMIZE ${product.name.uppercase()}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                val groups = product.modifier_groups ?: emptyList()
                items(groups) { group ->
                    ModifierGroupSection(
                        group = group,
                        selected = selectedModifiers,
                        onToggle = { mod ->
                            if (selectedModifiers.contains(mod)) {
                                selectedModifiers.remove(mod)
                            } else {
                                // For MVP, we'll allow multi-select unless we add "single choice" logic
                                selectedModifiers.add(mod)
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onConfirm(selectedModifiers.toList()) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                val extraPrice = selectedModifiers.sumOf { it.price_cents }
                Text(
                    text = "ADD TO CART (+$${"%.2f".format(extraPrice / 100.0)})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun ModifierGroupSection(
    group: ModifierGroup,
    selected: List<ProductModifier>,
    onToggle: (ProductModifier) -> Unit
) {
    Column {
        Text(
            text = group.name.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            mainAxisSpacing = 8.dp,
            crossAxisSpacing = 8.dp
        ) {
            group.options.forEach { modifier ->
                val isSelected = selected.contains(modifier)
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(modifier) },
                    label = { 
                        Text(
                            text = if (modifier.price_cents > 0) 
                                "${modifier.name} (+$${"%.2f".format(modifier.price_cents / 100.0)})" 
                                else modifier.name 
                        ) 
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
    }
}

// Simple FlowRow polyfill for older Compose versions if needed
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    mainAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    crossAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(mainAxisSpacing),
        verticalArrangement = Arrangement.spacedBy(crossAxisSpacing),
        content = { content() }
    )
}
