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
import com.goldsky.carwash.payment.PaxScannerManager
import java.io.File

/**
 * Fullscreen activity that loops advertising video or images.
 * Exits back to the main activity upon any user touch.
 */
class AdActivity : BaseAdActivity() {

    private lateinit var videoAd: VideoView
    private lateinit var imgAd: ImageView
    private lateinit var tvPrompt: TextView
    private var scannerManager: PaxScannerManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad)

        videoAd = findViewById(R.id.video_ad)
        imgAd = findViewById(R.id.img_ad)
        tvPrompt = findViewById(R.id.tv_ad_prompt)
        scannerManager = PaxScannerManager(this)

        setupAdPlayback()
        setupPromptAnimation()

        // Clicking anywhere on the screen exits the ad activity
        val rootLayout = findViewById<View>(android.R.id.content)
        rootLayout.setOnClickListener {
            finish()
        }
    }

    private fun setupPromptAnimation() {
        // Create a breathing (fading in and out) effect for the prompt
        ObjectAnimator.ofFloat(tvPrompt, View.ALPHA, 1.0f, 0.2f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun setupAdPlayback() {
        // Attempt to find a video inside res/raw/
        val rawVideoResId = resources.getIdentifier("ad_video", "raw", packageName)
        
        if (rawVideoResId != 0) {
            // Video found, prepare VideoView
            imgAd.visibility = View.GONE
            videoAd.visibility = View.VISIBLE

            val videoUri = Uri.parse("android.resource://$packageName/$rawVideoResId")
            videoAd.setVideoURI(videoUri)
            videoAd.setOnPreparedListener { mediaPlayer ->
                mediaPlayer.isLooping = true
                videoAd.start()
            }
            videoAd.setOnErrorListener { _, _, _ ->
                // Fallback to image if video playback fails
                showImageFallback()
                true
            }
        } else {
            // No video in raw folder, fall back to image
            showImageFallback()
        }
    }

    private fun showImageFallback() {
        videoAd.visibility = View.GONE
        imgAd.visibility = View.VISIBLE
        // Set placeholder image
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
