package com.goldsky.ssp.common

import android.content.Context
import android.os.Vibrator

/**
 * Handles haptic and audio feedback.
 */
object FeedbackManager {

    fun vibrate(context: Context, durationMs: Long = 100) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(durationMs)
    }

    /** Short buzz for a successful barcode scan -- deliberately no TTS, this fires once per item added. */
    fun emitScanFeedback(context: Context) {
        vibrate(context, 50)
    }

    fun success(context: Context) {
        vibrate(context, 150)
        TtsManager.speak("Success")
    }

    fun failure(context: Context) {
        vibrate(context, 500)
        TtsManager.speak("Failed")
    }
}
