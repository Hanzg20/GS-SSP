package com.goldsky.ssp.ourea.eye.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.goldsky.ssp.ourea.eye.CfdDisplayState
import com.goldsky.ssp.ourea.eye.CfdItemData
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.ui.FillVideoView
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun OureaEyeScreen(state: CfdDisplayState, rotationDegrees: Int = 0) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(rotationZ = rotationDegrees.toFloat()),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(targetState = state, transitionSpec = {
            fadeIn() togetherWith fadeOut()
        }, label = "CfdTransition") { targetState ->
            when (targetState) {
                is CfdDisplayState.Idle -> IdleScreen()
                is CfdDisplayState.Cart -> CartScreen(targetState)
                is CfdDisplayState.Payment -> PaymentScreen(targetState)
                is CfdDisplayState.ThankYou -> ThankYouScreen()
            }
        }
    }
}

@Composable
fun IdleScreen() {
    val context = LocalContext.current
    val playlist = remember { AdManager.getCachedPlaylist(context) }
    val adsDir = remember { AdManager.getAdsDir(context) }

    val videoFile = remember {
        playlist.firstNotNullOfOrNull { ad ->
            val file = File(adsDir, ad.media.id + ".mp4")
            if (file.exists()) file else null
        }
    }

    if (videoFile != null) {
        AndroidView(
            factory = { ctx ->
                FillVideoView(ctx).apply {
                    setVideoURI(android.net.Uri.fromFile(videoFile))
                    setOnPreparedListener { mp ->
                        setVideoSize(mp.videoWidth, mp.videoHeight)
                        start()
                    }
                    setOnCompletionListener { start() }
                    setOnErrorListener { _, _, _ -> true }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        // Rotating promotional banners carousel fallback
        var adIndex by remember { mutableStateOf(0) }
        val ads = listOf(
            "Welcome to GoldSky Ourea · Tap card when ready",
            "Special Offer: Premium Coffee & Pastry Combo - 15% OFF",
            "Join our VIP Member Program to earn rewards on every order!"
        )

        LaunchedEffect(Unit) {
            while (true) {
                delay(5000)
                adIndex = (adIndex + 1) % ads.size
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(110.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ShoppingCart, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "GoldSky Ourea POS",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
            ) {
                AnimatedContent(targetState = ads[adIndex], transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "AdBanner") { currentAd ->
                    Text(
                        text = currentAd,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CartScreen(state: CfdDisplayState.Cart) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) {
            listState.animateScrollToItem(state.items.size - 1)
        }
    }

    Row(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // Left: Itemized List
        Surface(
            modifier = Modifier.weight(1.5f).fillMaxHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Current Order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.items) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text("Qty: ${item.qty}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("$${"%.2f".format(item.priceCents / 100.0)}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        // Right: Totals summary
        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Order Summary", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    SummaryRow("Subtotal", state.subtotalCents)
                    SummaryRow("Tax", state.taxCents)
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("$${"%.2f".format(state.totalCents / 100.0)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun PaymentScreen(state: CfdDisplayState.Payment) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CreditCard, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Total Due: $${"%.2f".format(state.amountCents / 100.0)}",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = "Please Tap, Insert or Swipe Card",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
        }
    }
}

@Composable
fun SummaryRow(label: String, cents: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text("$${"%.2f".format(cents / 100.0)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun ThankYouScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(96.dp), tint = Color(0xFF4CAF50))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Payment Successful!", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Thank you for your visit. Have a wonderful day!", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
