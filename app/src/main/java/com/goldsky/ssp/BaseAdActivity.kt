package com.goldsky.ssp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Base activity that monitors global user touch inactivity.
 * If there is no interaction for 60 seconds, it launches the AdActivity.
 */
abstract class BaseAdActivity : AppCompatActivity() {

    private val adHandler = Handler(Looper.getMainLooper())
    private val adRunnable = Runnable {
        android.util.Log.d("AdTimer", "Inactivity timeout reached. Launching ad screen...")
        launchAdScreen()
    }

    // Inactivity timeout in milliseconds (30 seconds for testing)
    private val inactivityTimeoutMs: Long = 30000 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyKioskWindowFlags()
    }

    override fun onResume() {
        super.onResume()
        applyKioskWindowFlags()
        resetAdTimer()
    }

    override fun onPause() {
        super.onPause()
        stopAdTimer()
    }

    /**
     * Intercepts all screen touch events to reset the inactivity timer.
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetAdTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyKioskWindowFlags()
        }
    }

    /**
     * Resets the 60 seconds inactivity timer.
     */
    protected fun resetAdTimer() {
        adHandler.removeCallbacks(adRunnable)
        adHandler.postDelayed(adRunnable, inactivityTimeoutMs)
    }

    /**
     * Stops the inactivity timer.
     */
    protected fun stopAdTimer() {
        adHandler.removeCallbacks(adRunnable)
    }

    private fun launchAdScreen() {
        // Do not relaunch AdActivity if it is already running
        if (this is AdActivity) return
        
        // Custom check to see if we are currently in an active car wash session
        if (isCarWashSessionActive()) return

        val intent = Intent(this, AdActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
    }

    /**
     * Hook for subclasses to declare whether a car wash session is currently running.
     * We don't want to show ads *while* the user's car is being washed.
     */
    protected open fun isCarWashSessionActive(): Boolean {
        return false
    }

    /**
     * Locks window parameters into full screen immersive Kiosk mode.
     */
    protected fun applyKioskWindowFlags() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}
