package com.goldsky.ssp.vending.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = GoldskyPrimary,
    secondary = GoldskySecondary,
    background = DarkBg,
    surface = GlassSurface,
    onPrimary = TextPrimary,
    onSecondary = DarkBg
)

@Composable
fun VendingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
