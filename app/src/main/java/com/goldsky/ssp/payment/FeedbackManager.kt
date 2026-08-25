package com.goldsky.ssp.payment

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Handles audio (beeps) and haptic feedback for POS operations.
 */
object FeedbackManager {
    private const val TAG = "FeedbackManager"
    private var soundPool: SoundPool? = null
    private var scanSuccessId: Int = -1
    private var paymentSuccessId: Int = -1

    fun init(context: Context) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(audioAttributes)
            .build()

        // In a real app, these would be raw assets. 
        // For prototype, we'll try to load them if they exist or log the intent.
        // scanSuccessId = soundPool?.load(context, R.raw.scan_beep, 1) ?: -1
    }

    /**
     * Plays a classic POS scanner "beep" and vibrates.
     */
    fun emitScanFeedback(context: Context) {
        Log.d(TAG, "FEEDBACK: Scan Success (Beep + Vibrate)")
        vibrate(context, 50)
        // soundPool?.play(scanSuccessId, 1f, 1f, 1, 0, 1f)
    }

    /**
     * Plays a celebratory tone for approved payments.
     */
    fun emitPaymentSuccessFeedback(context: Context) {
        Log.i(TAG, "FEEDBACK: Payment Approved!")
        vibrate(context, 200)
        // soundPool?.play(paymentSuccessId, 1f, 1f, 1, 0, 1f)
    }

    private fun vibrate(context: Context, durationMs: Long) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }
}
