package com.goldsky.ssp.ourea

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Visual identity pass 2026-08-27: colors pulled directly off the real
 * GoldSky logo (core/ui's drawable/goldsky_logo.png -- gold arc, sky-blue
 * "wing" with a circuit-line texture, warm amber "eye" with a starfield
 * texture), not the flat dark-navy-plus-orange scheme the first pass
 * borrowed from the unrelated Posly reference screenshots. Per explicit
 * direction: the UI itself should NOT look overtly "AI-styled" (no neon/
 * hologram cliches) -- premium and understated, with the logo's gold/sky-
 * blue/amber only as accents on a near-black ground, the same restraint the
 * logo itself uses (color used for a few clean shapes, not a busy gradient
 * wash). Real AI-*powered* features (voice search, insights, etc.) are a
 * deliberately separate follow-up, not part of this pass.
 */
val OureaBackground = Color(0xFF0A0D16)
val OureaSurface = Color(0xFF141A28)
val OureaSurfaceVariant = Color(0xFF1D2536)
val OureaSidebar = Color(0xFF0C0F1A)

// Pulled from the logo itself
val OureaGold = Color(0xFFF7C948)
val OureaGoldDeep = Color(0xFFD9A02A)
val OureaSkyBlue = Color(0xFF3AB6E8)
val OureaAmber = Color(0xFFE0621C)
val OureaAmberDeep = Color(0xFF8A3A0F)

val OureaOrange = OureaAmber // kept as an alias so existing call sites (CTAs, price text) don't need renaming
val OureaGreen = Color(0xFF2ECC71)
val OureaRed = Color(0xFFE74C3C)
val OureaBlue = OureaSkyBlue
val OureaTextPrimary = Color(0xFFF5F6F8)
val OureaTextSecondary = Color(0xFF808C9E)

/** The logo's gold-to-amber sweep, for primary CTAs (Checkout / Charge Card). */
val OureaBrandGradient = Brush.horizontalGradient(listOf(OureaGold, OureaAmber))

private val OureaColorScheme = darkColorScheme(
    primary = OureaGold,
    onPrimary = Color(0xFF231A00),
    secondary = OureaSkyBlue,
    background = OureaBackground,
    onBackground = OureaTextPrimary,
    surface = OureaSurface,
    onSurface = OureaTextPrimary,
    surfaceVariant = OureaSurfaceVariant,
    onSurfaceVariant = OureaTextSecondary,
    error = OureaRed
)

@Composable
fun OureaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OureaColorScheme, content = content)
}
