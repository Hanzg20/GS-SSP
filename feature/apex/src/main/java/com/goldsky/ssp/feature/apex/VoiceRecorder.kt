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
    // Loudest level seen while recording (0..32767). Whisper turns silence into
    // made-up text ("Thank you for watching."), so a silent take isn't sent.
    @Volatile private var peak = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sampler = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            peak = maxOf(peak, runCatching { r.maxAmplitude }.getOrDefault(0))
            handler.postDelayed(this, 100)
        }
    }
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
            peak = 0
            handler.post(sampler)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Recording didn't start: ${e.message}")
            stopQuietly()
            false
        }
    }

    /** Why the last [stop] returned null. */
    var lastProblem: Problem? = null
        private set

    enum class Problem { TOO_SHORT, SILENT }

    /** The recording, or null if it was too short (a tap, not a hold) or silent. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        handler.removeCallbacks(sampler)
        val longEnough = System.currentTimeMillis() - startedAt >= MIN_MS
        lastProblem = when { !longEnough -> Problem.TOO_SHORT; peak < SILENCE_PEAK -> Problem.SILENT; else -> null }
        return try {
            r.stop()
            if (lastProblem == null) file else null
        } catch (e: Exception) {
            null // stop() throws when nothing was captured
        } finally {
            r.release()
        }
    }

    fun stopQuietly() {
        handler.removeCallbacks(sampler)
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        const val MAX_MS = 30_000
        private const val MIN_MS = 600L
        private const val SILENCE_PEAK = 1500
    }
}
