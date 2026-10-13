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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.goldsky.ssp.ourea.eye.CfdDisplayState
import com.goldsky.ssp.ourea.eye.CfdItemData
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.ui.FillVideoView
import com.goldsky.ssp.ui.SignboardView
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun OureaEyeScreen(state: CfdDisplayState, rotationDegrees: Int = 0, paidCents: Int? = null) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(rotationZ = rotationDegrees.toFloat()),
        color = MaterialTheme.colorScheme.background
    ) {
        // Fade between screens, not on every cart update (each scan is a new Cart).
        AnimatedContent(targetState = state, transitionSpec = {
            fadeIn() togetherWith fadeOut()
        }, contentKey = { it::class }, label = "CfdTransition") { targetState ->
            when (targetState) {
                is CfdDisplayState.Idle -> IdleScreen()
                is CfdDisplayState.Cart -> CartScreen(targetState)
                is CfdDisplayState.Payment -> PaymentScreen(targetState)
                is CfdDisplayState.ThankYou -> ThankYouScreen(paidCents, targetState.orderNumber)
            }
        }
    }
}

/** One playable idle-screen item from the store's ad playlist (CMP > Ads). */
private sealed class EyeAd {
    data class Video(val file: File) : EyeAd()
    data class Image(val file: File) : EyeAd()
    /** TEXT = store announcement, TEXT_AD = text ad: drawn as Aegis draws them (SignboardView). */
    data class Text(val text: String, val billboard: Boolean) : EyeAd()
}

private const val STILL_MS = 8_000L // images and text announcements

/**
 * Idle screen: the store's ad playlist (videos, images, text announcements) in
 * CMP's order, looping; a new playlist from AdManager's sync takes effect as it
 * arrives. With no ads, a plain welcome -- never made-up offers (this screen
 * used to cycle a "Premium Coffee & Pastry Combo -15%" that no store ran).
 */
@Composable
fun IdleScreen() {
    val context = LocalContext.current
    var playlist by remember { mutableStateOf(AdManager.getCachedPlaylist(context)) }
    LaunchedEffect(Unit) { AdManager.playlistUpdateFlow.collect { playlist = it } }
    val adsDir = remember { AdManager.getAdsDir(context) }

    val items = remember(playlist) {
        playlist.sortedBy { it.entry.play_order }.mapNotNull { ad ->
            val media = ad.media
            when (media.media_type) {
                "TEXT", "TEXT_AD" -> media.text_content?.takeIf { it.isNotBlank() }?.let { EyeAd.Text(it, billboard = media.media_type == "TEXT_AD") }
                else -> adsDir.listFiles { f -> f.name.startsWith(media.id + ".") && !f.name.endsWith(".part") }?.firstOrNull()?.let { f ->
                    if (media.media_type == "VIDEO") EyeAd.Video(f) else EyeAd.Image(f)
                }
            }
        }
    }

    if (items.isEmpty()) {
        WelcomeScreen()
        return
    }

    // step, not index, keys each showing: a one-item playlist must restart too.
    var step by remember(items) { mutableStateOf(0) }
    fun next() { step++ }
    when (val ad = items[step % items.size]) {
        is EyeAd.Video -> key(step) {
            AndroidView(
                factory = { ctx ->
                    FillVideoView(ctx).apply {
                        setVideoURI(android.net.Uri.fromFile(ad.file))
                        setOnPreparedListener { mp ->
                            setVideoSize(mp.videoWidth, mp.videoHeight)
                            start()
                        }
                        setOnCompletionListener { next() }
                        setOnErrorListener { _, _, _ -> next(); true }
                    }
                },
                modifier = Modifier.fillMaxSize().background(Color.Black)
            )
        }
        is EyeAd.Image -> key(step) {
            val bitmap = remember(ad.file) { decodeScaled(ad.file, 1280) }
            LaunchedEffect(Unit) { delay(STILL_MS); next() }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(), contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color.Black)
                )
            } else {
                LaunchedEffect(Unit) { next() }
            }
        }
        is EyeAd.Text -> key(step) {
            LaunchedEffect(Unit) { delay(STILL_MS); next() }
            // Same as the Aegis ad screen: a pinned notice board (announcement) or a
            // backlit marquee billboard (text ad), square, 24dp in from the edges.
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx -> SignboardView(ctx) },
                    update = { v ->
                        v.style = if (ad.billboard) SignboardView.Style.BILLBOARD else SignboardView.Style.NOTICE
                        v.text = ad.text
                    },
                    modifier = Modifier.padding(24.dp).aspectRatio(1f)
                )
            }
        }
    }
}

/** No ads assigned: the GoldSky mark and a welcome, nothing invented. */
@Composable
private fun WelcomeScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.goldsky.ssp.core.ui.R.drawable.goldsky_logo),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("Welcome", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Decodes an ad image no larger than [maxPx] on its long side (480x480 screen: no need for a 4K bitmap in memory). */
private fun decodeScaled(file: File, maxPx: Int): android.graphics.Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: Exception) {
    null
}
