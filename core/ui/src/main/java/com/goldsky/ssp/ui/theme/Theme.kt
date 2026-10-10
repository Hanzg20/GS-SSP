package com.goldsky.ssp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours Material's scheme has no role for. Status colours are per theme so
 * a "paid" green or "refund" red stays legible on navy and on white alike.
 */
@Immutable
data class GsColors(
    val isDark: Boolean,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Accent for figures that should stand out (prices, totals, counts). */
    val emphasis: Color,
)

private val DarkScheme = darkColorScheme(
    primary = BrandCyan,
    onPrimary = Color(0xFF00222A),
    primaryContainer = Color(0xFF003A45),
    onPrimaryContainer = Color(0xFFB3F4FF),
    secondary = Color(0xFF4D8DFF),
    onSecondary = Color(0xFF001A43),
    secondaryContainer = Navy700,
    onSecondaryContainer = Slate100,
    tertiary = WarningOnDark,
    onTertiary = Color(0xFF2A1C00),
    background = Navy950,
    onBackground = Slate100,
    surface = Navy900,
    onSurface = Slate100,
    surfaceVariant = Navy800,
    onSurfaceVariant = Slate400,
    surfaceTint = Color.Transparent,   // no tonal tint: layers are the navy ladder above
    outline = Navy600,
    outlineVariant = Navy600,
    error = DangerOnDark,
    onError = Color(0xFF3B0000),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Slate100,
    inverseOnSurface = Navy950,
    inversePrimary = BrandBlue,
    scrim = Color.Black,
)

private val LightScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = PaperWhite,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF00838F),
    onSecondary = PaperWhite,
    secondaryContainer = Mist,
    onSecondaryContainer = Ink,
    tertiary = WarningOnLight,
    onTertiary = PaperWhite,
    background = Paper,
    onBackground = Ink,
    surface = PaperWhite,
    onSurface = Ink,
    surfaceVariant = PaperWhite,
    onSurfaceVariant = InkMuted,
    surfaceTint = Color.Transparent,
    outline = Line,
    outlineVariant = Line,
    error = DangerOnLight,
    onError = PaperWhite,
    errorContainer = Color(0xFFFDE2E2),
    onErrorContainer = Color(0xFF5F1414),
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = BrandCyan,
    scrim = Color.Black,
)

private val DarkExtras = GsColors(isDark = true, success = SuccessOnDark, warning = WarningOnDark, danger = DangerOnDark, emphasis = BrandCyan)
private val LightExtras = GsColors(isDark = false, success = SuccessOnLight, warning = WarningOnLight, danger = DangerOnLight, emphasis = BrandBlue)

private val LocalGsColors = staticCompositionLocalOf { DarkExtras }

/** Access to [GsColors] inside [GoldSkyTheme]. */
object GsTheme {
    val colors: GsColors
        @Composable get() = LocalGsColors.current
}

/**
 * The attended-POS theme: dark ("CYBER", navy + cyan) or light ("CLASSIC",
 * paper + blue), chosen per device in Settings. Both carry the same roles, so
 * a screen written against MaterialTheme.colorScheme / GsTheme.colors looks
 * right in either without branching on the theme itself.
 *
 * Layers: background (page) < surface (bars) < surfaceVariant (cards) <
 * secondaryContainer (raised controls / dialogs); outline for borders.
 */
@Composable
fun GoldSkyTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGsColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
