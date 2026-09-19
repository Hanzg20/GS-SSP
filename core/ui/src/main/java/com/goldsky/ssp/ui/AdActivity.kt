package com.goldsky.ssp.ui

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
import com.goldsky.ssp.core.ui.R
import com.goldsky.ssp.model.AdMedia
import com.goldsky.ssp.model.TargetedAd
import com.goldsky.ssp.payment.AdManager
import com.goldsky.ssp.payment.AdTargetingEvaluator
import com.goldsky.ssp.payment.AnalyticsManager
import com.goldsky.ssp.payment.DeviceRepository
import com.goldsky.ssp.payment.hardware.HardwareFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File

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

    private var currentAdId: String? = null
    private var adStartTime: Long = 0
    private var wasInterrupted = false

    private var preloadedUri: Uri? = null
    private var preloadedType: String? = null

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

        findViewById<View>(R.id.ad_bg_tap_handler).setOnClickListener {
            wasInterrupted = true
            reportAdCompletion()
            finish()
        }

        btnPause.setOnClickListener { togglePauseResume() }

        AdManager.playlistUpdateFlow.onEach { newAds ->
            fullPlaylist = newAds
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
        if (fullPlaylist.isEmpty()) {
            showImageFallback()
            return
        }
        reportAdCompletion()
        val activeAds = fullPlaylist.filter { AdTargetingEvaluator.isAdActive(it.entry) }
        if (activeAds.isEmpty()) {
            showImageFallback()
            advanceHandler.postDelayed({ if (!isFinishing) playNext() }, 30000)
            return
        }
        val sortedAds = activeAds.sortedWith(
            compareByDescending<TargetedAd> { AdTargetingEvaluator.getPriority(it.entry) }
            .thenBy { it.entry.play_order }
        )
        val adIndex = if (currentIndex >= sortedAds.size) 0 else currentIndex
        val targeted = sortedAds[adIndex]
        val ad = targeted.media
        currentIndex = (adIndex + 1) % sortedAds.size
        currentAdId = ad.id
        adStartTime = System.currentTimeMillis()
        wasInterrupted = false
        if (ad.media_type == "TEXT" || ad.media_type == "TEXT_AD") {
            val text = ad.text_content
            if (text.isNullOrBlank()) {
                playNext()
                return
            }
            if (ad.media_type == "TEXT_AD") playTextAd(text) else playText(text)
            return
        }
        val mediaUrl = ad.media_url
        if (mediaUrl == null) {
            playNext()
            return
        }
        val adsDir = AdManager.getAdsDir(this)
        val file = File(adsDir, ad.id + getExtension(mediaUrl))
        if (!file.exists()) {
            if (currentIndex == 0) {
                showImageFallback()
                advanceHandler.postDelayed({ if (!isFinishing) playNext() }, 30000)
                return
            }
            playNext()
            return
        }
        if (ad.media_type == "VIDEO") playVideo(file) else playImage(file)
        preloadNext(sortedAds, (adIndex + 1) % sortedAds.size)
    }

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
        val file = File(AdManager.getAdsDir(this), media.id + getExtension(mediaUrl))
        if (file.exists()) {
            preloadedUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            preloadedType = media.media_type
        }
    }

    private fun playVideo(file: File) {
        resetPauseState()
        imgAd.visibility = View.GONE
        announcementCard.visibility = View.GONE
        textAdCard.visibility = View.GONE
        videoAd.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        val contentUri = if (preloadedType == "VIDEO" && preloadedUri != null) preloadedUri!! else FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        videoAd.setVideoURI(contentUri)
        videoAd.setOnCompletionListener { playNext() }
        videoAd.setOnErrorListener { _, _, _ -> playNext(); true }
        videoAd.start()
    }

    private fun playImage(file: File) {
        resetPauseState()
        videoAd.visibility = View.GONE
        announcementCard.visibility = View.GONE
        textAdCard.visibility = View.GONE
        imgAd.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        val contentUri = if (preloadedType != "VIDEO" && preloadedUri != null) preloadedUri!! else FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        imgAd.setImageURI(contentUri)
        scheduleAdvance(10000L)
    }

    private fun playText(text: String) {
        resetPauseState()
        videoAd.visibility = View.GONE
        imgAd.visibility = View.GONE
        textAdCard.visibility = View.GONE
        announcementCard.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        tvAnnouncement.text = text
        scheduleAdvance(8000L)
    }

    private fun playTextAd(text: String) {
        resetPauseState()
        videoAd.visibility = View.GONE
        imgAd.visibility = View.GONE
        announcementCard.visibility = View.GONE
        textAdCard.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        tvTextAd.text = text
        scheduleAdvance(8000L)
    }

    private fun scheduleAdvance(delayMs: Long) {
        pendingAdvance?.let { advanceHandler.removeCallbacks(it) }
        val runnable = Runnable { if (!isFinishing) playNext() }
        pendingAdvance = runnable
        advanceHandler.postDelayed(runnable, delayMs)
    }

    private fun reportAdCompletion() {
        val adId = currentAdId ?: return
        val duration = System.currentTimeMillis() - adStartTime
        if (duration < 500) return
        val sn = DeviceRepository.getPersistedDeviceSn() ?: "UNKNOWN"
        AnalyticsManager.recordPlayback(sn, adId, adStartTime, duration, wasInterrupted)
        currentAdId = null
    }

    private fun resetPauseState() {
        isPaused = false
        btnPause.setImageResource(R.drawable.ic_pause)
    }

    private fun togglePauseResume() {
        isPaused = !isPaused
        if (isPaused) {
            if (videoAd.visibility == View.VISIBLE && videoAd.isPlaying) videoAd.pause()
            pendingAdvance?.let { advanceHandler.removeCallbacks(it) }
            btnPause.setImageResource(R.drawable.ic_play)
        } else {
            when {
                videoAd.visibility == View.VISIBLE -> videoAd.start()
                imgAd.visibility == View.VISIBLE -> scheduleAdvance(10000L)
                announcementCard.visibility == View.VISIBLE -> scheduleAdvance(8000L)
                textAdCard.visibility == View.VISIBLE -> scheduleAdvance(8000L)
            }
            btnPause.setImageResource(R.drawable.ic_pause)
        }
    }

    private fun getExtension(url: String): String = if (url.contains(".mp4")) ".mp4" else if (url.contains(".jpg")) ".jpg" else ".png"

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
        btnPause.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        stopAdTimer()
        HardwareFactory.getScannerProvider(this, "IDTECH").setScannerLed(false)
        HardwareFactory.getScannerProvider(this, "PAX").setScannerLed(false)
        if (!isPaused && videoAd.visibility == View.VISIBLE && !videoAd.isPlaying) videoAd.start()
    }

    override fun onPause() {
        super.onPause()
        reportAdCompletion()
        if (videoAd.isPlaying) videoAd.pause()
    }
}
