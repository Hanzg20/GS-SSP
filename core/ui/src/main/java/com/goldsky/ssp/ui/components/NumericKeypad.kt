package com.goldsky.ssp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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

/**
 * One key. Colours come from the theme only (digits: raised neutral, back:
 * muted, confirm: primary), so the keypad matches whatever theme hosts it --
 * GoldSkyTheme on Iris, OureaTheme on Ourea -- instead of carrying its own
 * amber palette.
 */
@Composable
private fun KeypadButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isSpecial: Boolean = false,
    isConfirm: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (isConfirm) scheme.primary else scheme.secondaryContainer
    val content = when {
        isConfirm -> scheme.onPrimary
        isSpecial -> scheme.onSurfaceVariant
        else -> scheme.onSecondaryContainer
    }

    Surface(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = if (isConfirm) null else BorderStroke(1.dp, scheme.outline),
        shadowElevation = if (isConfirm) 4.dp else 1.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = content)
            } else {
                Text(text = text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = content)
            }
        }
    }
}
