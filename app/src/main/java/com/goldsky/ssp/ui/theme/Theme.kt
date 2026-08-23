package com.goldsky.ssp.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PremiumColorScheme = darkColorScheme(
    primary = GoldSkyPrimary,
    onPrimary = ObsidianBlack,
    secondary = GoldSkySecondary,
    onSecondary = PlatinumWhite,
    background = GoldSkyBackground,
    onBackground = GoldSkyOnSurface,
    surface = GoldSkySurface,
    onSurface = GoldSkyOnSurface,
    error = GoldSkyError,
    outline = GoldSkyGlassStroke
)

/**
 * Modern Premium Dark Theme for Retail Pro.
 * Forces dark mode to maintain luxury brand identity.
 */
@Composable
fun RetailTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = PremiumColorScheme
    val view = LocalView.current
    
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
