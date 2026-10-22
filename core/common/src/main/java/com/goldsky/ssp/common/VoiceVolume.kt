package com.goldsky.ssp.common

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * The stream the voice prompts play on (STREAM_MUSIC: [TtsManager]'s
 * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE maps to it). Customers at Eagleson said the
 * voice was too quiet (2026-10-22); 255 sat at 10/15 -- the speaker is small and
 * the bay is loud, so the kiosk keeps it at full.
 */
object VoiceVolume {
    private const val TAG = "VoiceVolume"
    private const val STREAM = AudioManager.STREAM_MUSIC

    /** Current and max level; null if the system won't say. */
    fun read(context: Context): Pair<Int, Int>? = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getStreamVolume(STREAM) to am.getStreamMaxVolume(STREAM)
    }.getOrNull()

    /**
     * Raises the voice stream to full when it is lower (someone turned it down, or
     * the terminal shipped that way). No volume panel shown. Fails quietly where the
     * system refuses (fixed-volume devices, Do Not Disturb).
     */
    fun ensureFull(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(STREAM)
            val now = am.getStreamVolume(STREAM)
            if (now < max) {
                am.setStreamVolume(STREAM, max, 0)
                Log.i(TAG, "Voice volume $now/$max -> ${am.getStreamVolume(STREAM)}/$max")
            }
        }.onFailure { Log.w(TAG, "Can't raise the voice volume: ${it.message}") }
    }
}
