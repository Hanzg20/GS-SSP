package com.goldsky.ssp.ui

import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import com.goldsky.ssp.core.ui.R
import com.goldsky.ssp.common.QrUtils

class VipActivity : BaseAdActivity() {

    companion object {
        // Shorter and separate from BaseAdActivity's own 3-minute ad-idle
        // timer -- this page has nothing for an idle customer to be shown
        // (no ad content makes sense mid-VIP-purchase), so it just backs out
        // to the price selection screen instead of waiting for the ad timer.
        private const val VIP_IDLE_TIMEOUT_MS = 60000L
    }

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { finish() }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, VIP_IDLE_TIMEOUT_MS)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(idleRunnable)
    }

    override fun onResume() {
        super.onResume()
        resetIdleTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        idleHandler.removeCallbacks(idleRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vip)

        val imgQr = findViewById<ImageView>(R.id.img_vip_qr)
        val vipUrl = "https://goldsky.com/vip"
        val qrBitmap = QrUtils.generateQrCode(vipUrl, 250, 250)
        imgQr.setImageBitmap(qrBitmap)

        // Round the card preview's corners to match the rest of the app's
        // card aesthetic (same 16dp radius as bg_glass_card). img_vip_card
        // uses fitCenter (the full source photo, nothing cropped off), so it
        // doesn't necessarily fill layout_card_bg's whole box -- both views
        // share the same bounds and need the same outline, or the letterbox
        // fill behind a narrower image would show square corners poking out.
        val cardRadiusPx = 16f * resources.displayMetrics.density
        val roundedCornerOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cardRadiusPx)
            }
        }
        findViewById<View>(R.id.layout_card_bg).apply {
            clipToOutline = true
            outlineProvider = roundedCornerOutline
        }
        findViewById<ImageView>(R.id.img_vip_card).apply {
            clipToOutline = true
            outlineProvider = roundedCornerOutline
        }

        // The layout's BACK button was never wired up -- combined with
        // BaseAdActivity's immersive full-screen kiosk flags (nav bar
        // hidden), this screen was a dead end with no way back to the wash
        // home screen. Found live on-device 2026-08-29.
        findViewById<Button>(R.id.btn_back_main).setOnClickListener {
            finish()
        }
    }
}
