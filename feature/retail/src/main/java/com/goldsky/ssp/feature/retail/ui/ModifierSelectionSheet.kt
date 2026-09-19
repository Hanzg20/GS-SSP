package com.goldsky.ssp.feature.retail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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

@OptIn(ExperimentalMaterial3Api::class)
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
                shape = RoundedCornerShape(16.dp)
            ) {
                val extraPrice = selectedModifiers.sumOf { it.price_cents }
                Text(
                    text = "ADD TO CART (+$${"%.2f".format(extraPrice / 100.0)})",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ModifierGroupSection(
    group: ModifierGroup,
    selected: List<ProductModifier>,
    onToggle: (ProductModifier) -> Unit
) {
    Column {
        Text(
            text = group.name.uppercase(),
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            group.options.forEach { modifier ->
                val isSelected = selected.contains(modifier)
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(modifier) },
                    label = { Text(modifier.name) }
                )
            }
        }
    }
}
