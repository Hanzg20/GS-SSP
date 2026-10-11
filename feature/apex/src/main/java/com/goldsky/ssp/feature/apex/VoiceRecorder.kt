package com.goldsky.ssp.feature.apex

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Hold-to-talk recording for 小海狸: AAC in an .m4a, mono 16 kHz -- small
 * (~4 KB/s) and read directly by Whisper. One recording at a time; the file is
 * overwritten by the next one.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    val file: File get() = File(context.cacheDir, "apex_speech.m4a")

    fun start(): Boolean {
        stopQuietly()
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(MAX_MS)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Recording didn't start: ${e.message}")
            stopQuietly()
            false
        }
    }

    /** The recording, or null if it was too short to hold any speech (a tap, not a hold). */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val longEnough = System.currentTimeMillis() - startedAt >= MIN_MS
        return try {
            r.stop()
            if (longEnough) file else null
        } catch (e: Exception) {
            null // stop() throws when nothing was captured
        } finally {
            r.release()
        }
    }

    fun stopQuietly() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        const val MAX_MS = 30_000
        private const val MIN_MS = 600L
    }
}
