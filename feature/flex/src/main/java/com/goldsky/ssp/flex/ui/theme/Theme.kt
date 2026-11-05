package com.goldsky.ssp.flex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = KioskAmber,
    secondary = KioskEmerald,
    background = KioskBg,
    surface = KioskCard,
    onPrimary = KioskBg,
    onSecondary = KioskBg
)

@Composable
fun FlexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
