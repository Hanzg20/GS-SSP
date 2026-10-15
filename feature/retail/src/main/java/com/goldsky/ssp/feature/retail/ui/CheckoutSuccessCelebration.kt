package com.goldsky.ssp.feature.retail.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Checkout success overlay (Ourea): spring check mark, pulsing card, slide-up slip.
 * Shows only what the payment flow confirmed -- [amountCents] this sale took, [detail]
 * (approval code, change due...) and the quick-service call number [orderNumber] -- never
 * a made-up amount or reference.
 */
@Composable
fun CheckoutSuccessCelebration(
    amountCents: Int,
    detail: String,
    orderNumber: Int?,
    onDismiss: () -> Unit,
    title: String = "PAYMENT SUCCESSFUL",
    orderNumberLabel: String = "Order number",
    dismissLabel: String = "NEW SALE",
) {
    val scaleAnim = remember { Animatable(0f) }
    val slideUpAnim = remember { Animatable(100f) }

    LaunchedEffect(Unit) {
        scaleAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
        slideUpAnim.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        )
    }

    val formattedTotal = "$${String.format(java.util.Locale.US, "%.2f", amountCents / 100.0)}"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center
    ) {
        GlassmorphicApexCard(
            modifier = Modifier
                .width(340.dp)
                .padding(16.dp)
                .scale(scaleAnim.value),
            shape = RoundedCornerShape(24.dp),
            isPulseActive = true,
            containerColor = GoldSkyDarkSurface
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                // Success Checkmark Icon with Spring
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(GoldSkyCyan.copy(alpha = 0.3f), GoldSkyBlue.copy(alpha = 0.1f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(44.dp)) {
                        val stroke = 6.dp.toPx()
                        drawCircle(GoldSkyCyan, radius = size.minDimension / 2, style = Stroke(stroke))
                        val checkPath = Path().apply {
                            moveTo(size.width * 0.28f, size.height * 0.52f)
                            lineTo(size.width * 0.44f, size.height * 0.68f)
                            lineTo(size.width * 0.75f, size.height * 0.32f)
                        }
                        drawPath(
                            checkPath,
                            Color.White,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = formattedTotal,
                    color = GoldSkyCyan,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Virtual Receipt Card (Rolling out)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = slideUpAnim.value.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = GoldSkyCardSurface
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Quick-service: the call number, big, to tell the customer.
                        if (orderNumber != null) {
                            Text(
                                text = orderNumberLabel,
                                color = Color(0xFF8A96A3),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "%03d".format(orderNumber),
                                color = Color.White,
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(
                            text = detail,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldSkyCyan,
                        contentColor = GoldSkyDarkSurface
                    )
                ) {
                    Text(
                        text = dismissLabel,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
