package com.goldsky.ssp.vending.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.goldsky.ssp.vending.ui.theme.GoldskyPrimary

/**
 * Modern NFC Radar Animation (Logic crowdsourced from Nayax/Cantaloupe).
 * Uses a pulsing infinite animation to guide the user's hand to the reader area.
 */
@Composable
fun NfcRadar() {
    val infiniteTransition = rememberInfiniteTransition(label = "NfcRadar")
    
    // Scale and Alpha animation for the outer rings
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RingScale"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RingAlpha"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
        // Pulsing Ring
        Box(
            modifier = Modifier
                .size(60.dp)
                .scale(scale)
                .background(GoldskyPrimary.copy(alpha = alpha), CircleShape)
        )
        
        // Static Core
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(GoldskyPrimary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Using a standard NFC/Signal icon placeholder
            Icon(
                painter = painterResource(android.R.drawable.ic_menu_share), // Replace with real NFC icon
                contentDescription = "Tap Here",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}
