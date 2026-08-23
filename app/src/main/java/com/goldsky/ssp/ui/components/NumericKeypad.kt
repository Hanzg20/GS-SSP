package com.goldsky.ssp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Premium industrial-grade Numeric Keypad.
 * Obsidian & Gold aesthetic with haptic feedback.
 */
@Composable
fun NumericKeypad(
    onNumberClick: (String) -> Unit,
    onBackSpace: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("BACK", "0", "OK")
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { item ->
                    KeypadButton(
                        modifier = Modifier.weight(1f),
                        text = if (item in listOf("BACK", "OK")) "" else item,
                        icon = when (item) {
                            "BACK" -> Icons.Default.Backspace
                            "OK" -> Icons.Default.Check
                            else -> null
                        },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            when (item) {
                                "BACK" -> onBackSpace()
                                "OK" -> onConfirm()
                                else -> onNumberClick(item)
                            }
                        },
                        isSpecial = item in listOf("BACK", "OK"),
                        isConfirm = item == "OK"
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isSpecial: Boolean = false,
    isConfirm: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(16.dp),
        color = when {
            isConfirm -> MaterialTheme.colorScheme.primary
            isSpecial -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.surface
        },
        border = if (!isConfirm) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline) else null,
        tonalElevation = if (isSpecial) 8.dp else 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isConfirm) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light, // Sleeker look
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
