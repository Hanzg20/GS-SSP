package com.goldsky.ssp.ourea

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.goldsky.ssp.ui.theme.GoldSkyTheme
import com.goldsky.ssp.ui.theme.GsTheme

/**
 * Ourea runs on the shared GoldSkyTheme (dark "CYBER" / light "CLASSIC",
 * chosen in Settings), like Iris. Its screens still say OureaGold,
 * OureaSurface... -- those names now resolve to theme roles instead of
 * fixed hex values, so both themes come out right without touching every
 * call site. (Until 2026-10-10 Ourea had its own fixed dark palette taken
 * from the logo's gold / sky-blue / amber.)
 */
val OureaBackground: Color @Composable get() = MaterialTheme.colorScheme.background
val OureaSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
val OureaSurfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val OureaSidebar: Color @Composable get() = MaterialTheme.colorScheme.surface

/** Primary accent (was the logo gold). */
val OureaGold: Color @Composable get() = MaterialTheme.colorScheme.primary
val OureaGoldDeep: Color @Composable get() = MaterialTheme.colorScheme.primary
val OureaSkyBlue: Color @Composable get() = MaterialTheme.colorScheme.secondary
/** Warm accent for prices / attention (was the logo amber). */
val OureaAmber: Color @Composable get() = GsTheme.colors.warning
val OureaAmberDeep: Color @Composable get() = GsTheme.colors.warning

val OureaOrange: Color @Composable get() = OureaAmber
val OureaGreen: Color @Composable get() = GsTheme.colors.success
val OureaRed: Color @Composable get() = GsTheme.colors.danger
val OureaBlue: Color @Composable get() = OureaSkyBlue
val OureaTextPrimary: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val OureaTextSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** Primary CTAs (Checkout / Charge Card): primary into secondary. */
val OureaBrandGradient: Brush @Composable get() = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))

@Composable
fun OureaTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    GoldSkyTheme(dark = dark, content = content)
}
