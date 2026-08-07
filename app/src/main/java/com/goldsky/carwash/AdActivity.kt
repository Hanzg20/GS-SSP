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
import com.goldsky.carwash.model.AdMedia
import com.goldsky.carwash.payment.AdManager
import com.goldsky.carwash.payment.hardware.HardwareFactory
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

    private var playlist: List<AdMedia> = emptyList()
    private var currentIndex = 0

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
            finish()
        }

        // Pause/resume is a small corner control, handled on its own view so
        // it consumes the tap before it reaches the background catcher.
        btnPause.setOnClickListener { togglePauseResume() }
    }

    private fun loadPlaylist() {
        playlist = AdManager.getCachedPlaylist(this)
    }

    private fun startPlayback() {
        if (playlist.isEmpty()) {
            showImageFallback()
            return
        }

        playNext()
    }

    private fun playNext() {
        if (playlist.isEmpty()) return

        val ad = playlist[currentIndex]
        currentIndex = (currentIndex + 1) % playlist.size

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
    }

    private fun playVideo(file: File) {
        resetPauseState()
        // Ensure file is readable by system MediaPlayer process
        try { file.setReadable(true, false) } catch (e: Exception) {}

        imgAd.visibility = View.GONE
        announcementCard.visibility = View.GONE
        textAdCard.visibility = View.GONE
        videoAd.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE

        videoAd.setVideoURI(Uri.fromFile(file))
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

        imgAd.setImageURI(Uri.fromFile(file))
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
        if (videoAd.isPlaying) {
            videoAd.pause()
        }
    }
}
