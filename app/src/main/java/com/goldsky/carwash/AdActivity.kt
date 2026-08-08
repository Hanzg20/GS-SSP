package com.goldsky.carwash

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.FileProvider
import com.goldsky.carwash.model.AdMedia
import com.goldsky.carwash.model.TargetedAd
import com.goldsky.carwash.payment.AdManager
import com.goldsky.carwash.payment.AdTargetingEvaluator
import com.goldsky.carwash.payment.AnalyticsManager
import com.goldsky.carwash.payment.DeviceRepository
import com.goldsky.carwash.payment.hardware.HardwareFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File

/**
 * Fullscreen activity that loops advertising video or images from local cache.
 */
class AdActivity : BaseAdActivity() {

    private lateinit var videoAd: VideoView
    private lateinit var imgAd: ImageView
    private lateinit var announcementCard: View
    private lateinit var tvAnnouncement: TextView
    private lateinit var textAdCard: View
    private lateinit var tvTextAd: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var btnPause: ImageView

    private var fullPlaylist: List<TargetedAd> = emptyList()
    private var currentIndex = 0

    // Analytics state
    private var currentAdId: String? = null
    private var adStartTime: Long = 0
    private var wasInterrupted = false

    // Warm-up cache for next ad's URI to reduce switching latency
    private var preloadedUri: Uri? = null
    private var preloadedType: String? = null

    // Backs playImage/playText's auto-advance so togglePauseResume() can
    // cancel/reschedule it -- a plain fire-and-forget postDelayed (as before)
    // can't be paused.
    private val advanceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingAdvance: Runnable? = null
    private var isPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad)

        videoAd = findViewById(R.id.video_ad)
        imgAd = findViewById(R.id.img_ad)
        announcementCard = findViewById(R.id.layout_announcement)
        tvAnnouncement = findViewById(R.id.tv_announcement)
        textAdCard = findViewById(R.id.layout_text_ad)
        tvTextAd = findViewById(R.id.tv_text_ad)
        tvPrompt = findViewById(R.id.tv_ad_prompt)
        btnPause = findViewById(R.id.btn_pause_ad)

        setupPromptAnimation()
        loadPlaylist()
        startPlayback()

        // Decoupled Background Tap (TAP TO PAY logic)
        findViewById<View>(R.id.ad_bg_tap_handler).setOnClickListener {
            Log.i("AdActivity", "Background tap detected: triggering TAP TO PAY")
            wasInterrupted = true
            reportAdCompletion()
            finish()
        }

        // Pause/resume is a small corner control
        btnPause.setOnClickListener { togglePauseResume() }

        // Hot Reload: Listen for playlist updates from sync worker
        AdManager.playlistUpdateFlow.onEach { newAds ->
            Log.i("AdActivity", "Live playlist update detected! Refreshing...")
            fullPlaylist = newAds
            // No need to interrupt current ad, it will pick up new rules on next cycle
        }.launchIn(CoroutineScope(Dispatchers.Main))
    }

    private fun loadPlaylist() {
        fullPlaylist = AdManager.getCachedPlaylist(this)
    }

    private fun startPlayback() {
        if (fullPlaylist.isEmpty()) {
            showImageFallback()
            return
        }

        playNext()
    }

    private fun playNext() {
        if (fullPlaylist.isEmpty()) return

        // Finalize analytics for the ad that just ended
        reportAdCompletion()

        // 1. Filter active ads based on targeting rules
        val activeAds = fullPlaylist.filter { AdTargetingEvaluator.isAdActive(it.entry) }
        
        if (activeAds.isEmpty()) {
            showImageFallback()
            // Try again in 30s in case time-based rules change
            advanceHandler.postDelayed({ if (!isFinishing) playNext() }, 30000)
            return
        }

        // 2. Sort by priority and then original play_order
        val sortedAds = activeAds.sortedWith(
            compareByDescending<TargetedAd> { AdTargetingEvaluator.getPriority(it.entry) }
            .thenBy { it.entry.play_order }
        )

        // Adjust index to stay within active list bounds
        val adIndex = if (currentIndex >= sortedAds.size) 0 else currentIndex
        val targeted = sortedAds[adIndex]
        val ad = targeted.media
        
        // Advance global counter for next round
        currentIndex = (adIndex + 1) % sortedAds.size

        // Init analytics for new ad
        currentAdId = ad.id
        adStartTime = System.currentTimeMillis()
        wasInterrupted = false

        if (ad.media_type == "TEXT" || ad.media_type == "TEXT_AD") {
            val text = ad.text_content
            if (text.isNullOrBlank()) {
                playNext() // Skip malformed announcement/promo
                return
            }
            if (ad.media_type == "TEXT_AD") playTextAd(text) else playText(text)
            return
        }

        val mediaUrl = ad.media_url
        if (mediaUrl == null) {
            playNext() // Skip malformed row (VIDEO/IMAGE with no file url)
            return
        }

        val adsDir = AdManager.getAdsDir(this)
        val fileName = ad.id + getExtension(mediaUrl)
        val file = File(adsDir, fileName)

        if (!file.exists()) {
            playNext() // Skip missing file
            return
        }

        if (ad.media_type == "VIDEO") {
            playVideo(file)
        } else {
            playImage(file)
        }
        
        // Performance: pre-calculate URI for the next ad in the cycle
        preloadNext(sortedAds, (adIndex + 1) % sortedAds.size)
    }

    /**
     * Resolves the next ad's URI in the background to minimize "black frame" 
     * switching latency on low-performance industrial hardware.
     */
    private fun preloadNext(playlist: List<TargetedAd>, nextIndex: Int) {
        if (playlist.isEmpty()) return
        val nextAd = playlist[nextIndex]
        val media = nextAd.media
        if (media.media_type == "TEXT" || media.media_type == "TEXT_AD") {
            preloadedUri = null
            preloadedType = media.media_type
            return
        }
        
        val mediaUrl = media.media_url ?: return
        val adsDir = AdManager.getAdsDir(this)
        val fileName = media.id + getExtension(mediaUrl)
        val file = File(adsDir, fileName)
        
        if (file.exists()) {
            preloadedUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            preloadedType = media.media_type
            Log.d("AdActivity", "Pre-loaded next URI: ${file.name}")
        }
    }

    private fun playVideo(file: File) {
        resetPauseState()
        
        imgAd.visibility = View.GONE
        announcementCard.visibility = View.GONE
        textAdCard.visibility = View.GONE
        videoAd.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE

        // Performance: Use pre-loaded URI if available and matching
        val contentUri = if (preloadedType == "VIDEO" && preloadedUri != null) {
            preloadedUri!!
        } else {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        }
        videoAd.setVideoURI(contentUri)
        
        videoAd.setOnCompletionListener { playNext() }
        videoAd.setOnErrorListener { _, _, _ ->
            Log.e("AdActivity", "Video playback failed for: ${file.name}")
            playNext()
            true
        }
        videoAd.start()
    }

    private fun playImage(file: File) {
        resetPauseState()
        videoAd.visibility = View.GONE
        announcementCard.visibility = View.GONE
        textAdCard.visibility = View.GONE
        imgAd.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE

        val contentUri = if (preloadedType != "VIDEO" && preloadedUri != null) {
            preloadedUri!!
        } else {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        }
        imgAd.setImageURI(contentUri)
        scheduleAdvance(IMAGE_DURATION_MS)
    }

    private fun playText(text: String) {
        resetPauseState()
        videoAd.visibility = View.GONE
        imgAd.visibility = View.GONE
        textAdCard.visibility = View.GONE
        announcementCard.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        tvAnnouncement.text = text

        scheduleAdvance(TEXT_DURATION_MS)
    }

    private fun playTextAd(text: String) {
        resetPauseState()
        videoAd.visibility = View.GONE
        imgAd.visibility = View.GONE
        announcementCard.visibility = View.GONE
        textAdCard.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        tvTextAd.text = text

        scheduleAdvance(TEXT_DURATION_MS)
    }

    /** (Re)schedules the auto-advance to the next ad; cancels any previous one so pause/resume can't stack callbacks. */
    private fun scheduleAdvance(delayMs: Long) {
        pendingAdvance?.let { advanceHandler.removeCallbacks(it) }
        val runnable = Runnable { if (!isFinishing) playNext() }
        pendingAdvance = runnable
        advanceHandler.postDelayed(runnable, delayMs)
    }

    private fun reportAdCompletion() {
        val adId = currentAdId ?: return
        val duration = System.currentTimeMillis() - adStartTime
        if (duration < 500) return // Skip glitchy/instant skips

        val sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN"
        AnalyticsManager.recordPlayback(sn, adId, adStartTime, duration, wasInterrupted)
        
        currentAdId = null
    }

    private fun resetPauseState() {
        isPaused = false
        btnPause.setImageResource(R.drawable.ic_pause)
        btnPause.contentDescription = getString(R.string.ad_pause_content_description)
    }

    private fun togglePauseResume() {
        isPaused = !isPaused
        Log.i("AdActivity", "Toggle requested. New state: isPaused=$isPaused")
        if (isPaused) {
            if (videoAd.visibility == View.VISIBLE && videoAd.isPlaying) {
                videoAd.pause()
            }
            pendingAdvance?.let { advanceHandler.removeCallbacks(it) }
            btnPause.setImageResource(R.drawable.ic_play)
            btnPause.contentDescription = getString(R.string.ad_resume_content_description)
        } else {
            when {
                videoAd.visibility == View.VISIBLE -> videoAd.start()
                imgAd.visibility == View.VISIBLE -> scheduleAdvance(IMAGE_DURATION_MS)
                announcementCard.visibility == View.VISIBLE -> scheduleAdvance(TEXT_DURATION_MS)
                textAdCard.visibility == View.VISIBLE -> scheduleAdvance(TEXT_DURATION_MS)
            }
            btnPause.setImageResource(R.drawable.ic_pause)
            btnPause.contentDescription = getString(R.string.ad_pause_content_description)
        }
    }

    private fun getExtension(url: String): String {
        return when {
            url.contains(".mp4") -> ".mp4"
            url.contains(".jpg") -> ".jpg"
            else -> ".png"
        }
    }

    private fun setupPromptAnimation() {
        ObjectAnimator.ofFloat(tvPrompt, View.ALPHA, 1.0f, 0.2f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun showImageFallback() {
        videoAd.visibility = View.GONE
        announcementCard.visibility = View.GONE
        textAdCard.visibility = View.GONE
        imgAd.visibility = View.VISIBLE
        imgAd.setImageResource(R.drawable.ad_placeholder)
        // Nothing playing/timed to pause in the fallback state.
        btnPause.visibility = View.GONE
    }

    companion object {
        private const val IMAGE_DURATION_MS = 10000L
        private const val TEXT_DURATION_MS = 8000L
    }

    override fun onResume() {
        super.onResume()
        // Override parent timer so that AdActivity itself doesn't start another AdActivity
        stopAdTimer()

        // HARDWARE LOCK: Ensure scanner LEDs are OFF during advertising
        HardwareFactory.getScannerProvider(this, "IDTECH").setScannerLed(false)
        HardwareFactory.getScannerProvider(this, "PAX").setScannerLed(false)
        
        // Resume video if visible -- but not if the customer had manually
        // paused it (togglePauseResume), or coming back from background would
        // silently override their pause.
        if (!isPaused && videoAd.visibility == View.VISIBLE && !videoAd.isPlaying) {
            videoAd.start()
        }
    }

    override fun onPause() {
        super.onPause()
        reportAdCompletion()
        if (videoAd.isPlaying) {
            videoAd.pause()
        }
    }
}
