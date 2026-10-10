package com.goldsky.ssp.feature.retail.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// GoldSky Signature Brand Colors
// Brand colours come from the theme palette (core/ui theme/Color.kt).
val GoldSkyBlue = com.goldsky.ssp.ui.theme.BrandBlue
val GoldSkyCyan = com.goldsky.ssp.ui.theme.BrandCyan
val GoldSkyPurple = com.goldsky.ssp.ui.theme.BrandPurple
val GoldSkyDarkSurface = com.goldsky.ssp.ui.theme.Navy900
val GoldSkyCardSurface = com.goldsky.ssp.ui.theme.Navy700

/**
 * Animated breathing glow modifier for AI & SI empowered elements.
 * Produces a soft, pulsing gradient border that simulates active AI presence.
 */
@Composable
fun Modifier.apexPulseGlow(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    glowColorStart: Color = GoldSkyBlue,
    glowColorEnd: Color = GoldSkyCyan,
    strokeWidth: Dp = 2.dp,
    durationMs: Int = 2000
): Modifier {
    if (!enabled) return this

    val infiniteTransition = rememberInfiniteTransition(label = "ApexGlowTransition")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            glowColorStart.copy(alpha = alpha),
            glowColorEnd.copy(alpha = alpha * 0.7f),
            GoldSkyPurple.copy(alpha = alpha * 0.5f)
        )
    )

    return this.border(
        border = BorderStroke(strokeWidth, gradientBrush),
        shape = shape
    )
}

/**
 * Premium Glassmorphic Card with Apex Pulsing Glow for Iris / Ourea POS.
 */
@Composable
fun GlassmorphicApexCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    isPulseActive: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.apexPulseGlow(enabled = isPulseActive, shape = shape),
        shape = shape,
        color = containerColor,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            GoldSkyBlue.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
                .padding(16.dp),
            content = content
        )
    }
}
