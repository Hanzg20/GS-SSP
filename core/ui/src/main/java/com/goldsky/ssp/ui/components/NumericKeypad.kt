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
import com.goldsky.ssp.payment.DeviceRepository

// Amber Palette (琥珀色彩图谱)
val AmberGold = Color(0xFFFFB300)
val AmberDarkGold = Color(0xFFFF8F00)
val AmberLightGlow = Color(0xFFFFE082)
val AmberDarkResinBg = Color(0xFF1F1508)
val AmberSpecialResinBg = Color(0xFF2A1C0B)
val AmberTextGold = Color(0xFFFFCA28)

@Composable
fun NumericKeypad(
    onNumberClick: (String) -> Unit,
    onBackSpace: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val isCyber = DeviceRepository.getThemeStyle() == DeviceRepository.UiThemeStyle.CYBER

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
                        isCyber = isCyber,
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
 * Amber Glassmorphic Rounded Square Button (倒角方形琥珀质感按钮)
 */
@Composable
private fun KeypadButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isCyber: Boolean = false,
    isSpecial: Boolean = false,
    isConfirm: Boolean = false
) {
    val shape = RoundedCornerShape(16.dp)

    val surfaceColor = when {
        isConfirm -> if (isCyber) AmberGold else Color(0xFFFFA000)
        isSpecial -> if (isCyber) AmberSpecialResinBg else Color(0xFFFFF3E0)
        else -> if (isCyber) AmberDarkResinBg else Color(0xFFFFF8E1)
    }

    val borderColor = when {
        isConfirm -> Color(0xFFFFECB3)
        isCyber -> AmberGold.copy(alpha = 0.45f)
        else -> Color(0xFFFFCA28).copy(alpha = 0.6f)
    }

    val contentColor = when {
        isConfirm -> Color(0xFF1C1000) // Rich dark brown/black on gold
        isSpecial -> if (isCyber) AmberGold else Color(0xFFE65100)
        else -> if (isCyber) Color.White else Color(0xFF3E2723)
    }

    Surface(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        shape = shape,
        color = surfaceColor,
        border = BorderStroke(1.5.dp, borderColor),
        tonalElevation = if (isConfirm) 8.dp else 3.dp,
        shadowElevation = if (isConfirm) 6.dp else 1.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = contentColor
                )
            } else {
                Text(
                    text = text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = contentColor
                )
            }
        }
    }
}

