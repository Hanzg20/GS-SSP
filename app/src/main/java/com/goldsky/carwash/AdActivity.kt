package com.goldsky.carwash

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import com.goldsky.carwash.model.AdMedia
import com.goldsky.carwash.payment.AdManager
import com.goldsky.carwash.payment.PaxScannerManager
import java.io.File

/**
 * Fullscreen activity that loops advertising video or images from local cache.
 */
class AdActivity : BaseAdActivity() {

    private lateinit var videoAd: VideoView
    private lateinit var imgAd: ImageView
    private lateinit var tvPrompt: TextView
    private var scannerManager: PaxScannerManager? = null

    private var playlist: List<AdMedia> = emptyList()
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad)

        videoAd = findViewById(R.id.video_ad)
        imgAd = findViewById(R.id.img_ad)
        tvPrompt = findViewById(R.id.tv_ad_prompt)
        scannerManager = PaxScannerManager(this)

        setupPromptAnimation()
        loadPlaylist()
        startPlayback()

        // Exit on touch
        findViewById<View>(android.R.id.content).setOnClickListener { finish() }
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

        val adsDir = AdManager.getAdsDir(this)
        val fileName = ad.id + getExtension(ad.media_url)
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
        imgAd.visibility = View.GONE
        videoAd.visibility = View.VISIBLE
        
        videoAd.setVideoPath(file.absolutePath)
        videoAd.setOnCompletionListener { playNext() }
        videoAd.setOnErrorListener { _, _, _ ->
            playNext()
            true
        }
        videoAd.start()
    }

    private fun playImage(file: File) {
        videoAd.visibility = View.GONE
        imgAd.visibility = View.VISIBLE
        
        imgAd.setImageURI(Uri.fromFile(file))
        
        // Show image for 10 seconds then next
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing) playNext()
        }, 10000)
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
        imgAd.visibility = View.VISIBLE
        imgAd.setImageResource(R.drawable.ad_placeholder)
    }

    override fun onResume() {
        super.onResume()
        // Override parent timer so that AdActivity itself doesn't start another AdActivity
        stopAdTimer()

        // HARDWARE LOCK: Ensure scanner LEDs are OFF during advertising
        scannerManager?.setScannerLed(false)
        
        // Resume video if visible
        if (videoAd.visibility == View.VISIBLE && !videoAd.isPlaying) {
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
