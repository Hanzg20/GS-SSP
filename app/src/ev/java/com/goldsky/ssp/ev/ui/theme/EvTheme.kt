package com.goldsky.ssp.ev.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val EvNeonGreen = Color(0xFF00FF7F)
val EvDarkNavy = Color(0xFF0A0F1E)
val EvElectricBlue = Color(0xFF2E86DE)
val EvSurface = Color(0xFF161B2E)
val EvGold = Color(0xFFF1C40F)

private val EvColorScheme = darkColorScheme(
    primary = EvNeonGreen,
    secondary = EvElectricBlue,
    background = EvDarkNavy,
    surface = EvSurface,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color(0xFFB0B8C1)
)

@Composable
fun EvChargingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EvColorScheme,
        content = content
    )
}
