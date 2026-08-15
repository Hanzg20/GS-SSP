package com.goldsky.ssp.payment

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.text.NumberFormat
import java.util.Locale

/**
 * Lifecycle-aware Text-to-Speech manager for dynamic kiosk announcements.
 * Handles multilingual support and audio focus (ducking).
 */
object TtsManager : DefaultLifecycleObserver {
    private const val TAG = "TtsManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentLocale = Locale.US
    private var audioManager: AudioManager? = null

    /**
     * Initializes the TTS engine and binds to activity lifecycle.
     */
    fun registerLifecycle(context: Context, lifecycleOwner: LifecycleOwner) {
        if (tts == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    applyLocale(currentLocale)
                    Log.i(TAG, "TTS Engine initialized successfully")
                } else {
                    Log.e(TAG, "TTS Initialization failed: $status")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(this)
    }

    /**
     * Sets the language for subsequent announcements.
     */
    fun setLocale(languageTag: String) {
        val locale = Locale.forLanguageTag(languageTag)
        currentLocale = locale
        if (isInitialized) {
            applyLocale(locale)
        }
    }

    private fun applyLocale(locale: Locale) {
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language $locale is not supported or missing data")
        }
    }

    /**
     * Announces a monetary amount in a natural localized voice.
     * Example: 450 -> "Four dollars and fifty cents"
     */
    fun announceAmount(cents: Int, prefix: String = "") {
        val amount = cents / 100.0
        val formatter = NumberFormat.getCurrencyInstance(currentLocale)
        val formattedAmount = formatter.format(amount)
        
        val textToSpeak = if (prefix.isNotEmpty()) "$prefix $formattedAmount" else formattedAmount
        speak(textToSpeak)
    }

    /**
     * Core speak method with Audio Focus handling.
     */
    fun speak(text: String) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not ready, skipping: $text")
            return
        }

        Log.d(TAG, "Speaking: $text")

        requestDucking()
        
        // Use QUEUE_FLUSH to interrupt any previous guidance for better UX
        val utteranceId = "SSP_MSG_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun requestDucking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .build()
            audioManager?.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    fun stop() {
        tts?.stop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        Log.i(TAG, "Shutting down TTS engine")
        tts?.shutdown()
        tts = null
        isInitialized = false
        owner.lifecycle.removeObserver(this)
    }
}
