package com.goldsky.carwash

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * Manager for voice guidance and audio feedback.
 * Uses pre-recorded MP3 files from res/raw for high-quality outdoor clarity.
 */
object VoiceManager {
    private const val TAG = "VoiceManager"
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Plays a specific audio resource. 
     * Automatically stops and resets any existing playback.
     */
    private fun play(context: Context, resId: Int) {
        try {
            stop() // Ensure no overlapping audio
            mediaPlayer = MediaPlayer.create(context.applicationContext, resId)
            mediaPlayer?.setOnCompletionListener { 
                it.release()
                mediaPlayer = null
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}")
        }
    }

    /**
     * Stops current playback and releases resources.
     */
    fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    // --- Specialized Voice Prompts ---

    fun playPleaseTap(context: Context) {
        play(context, R.raw.voice_tap)
    }

    fun playApproved(context: Context) {
        play(context, R.raw.voice_approved)
    }

    fun playEnjoyWash(context: Context) {
        play(context, R.raw.voice_enjoy)
    }

    fun playDeclined(context: Context) {
        play(context, R.raw.voice_error)
    }

    fun playLowBalance(context: Context) {
        // Fallback to error sound if specific low balance sound isn't available
        play(context, R.raw.voice_error)
    }
}
