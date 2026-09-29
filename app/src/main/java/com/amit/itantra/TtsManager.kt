package com.amit.itantra

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    var ttsErrorMessage: String? = null
        private set

    init {
        Log.d("TtsManager", "Initializing TextToSpeech engine...")
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d("TtsManager", "TTS Engine initialized successfully")
            setLanguage("en")
        } else {
            Log.d("TtsManager", "TTS Engine initialization failed with status code: $status")
            ttsErrorMessage = "TTS initialization failed (code $status)"
        }
    }

    fun setLanguage(langCode: String): Int {
        val locale = if (langCode == "hi") Locale("hi", "IN") else Locale("en", "US")
        Log.d("TtsManager", "Setting TTS language to langCode: '$langCode' (Locale: $locale)")
        val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        Log.d("TtsManager", "TTS setLanguage result code: $result for locale: $locale")

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            val msg = if (result == TextToSpeech.LANG_MISSING_DATA) {
                "TTS voice data missing for $langCode. Opening voice data installer..."
            } else {
                "TTS language $langCode is not supported on this device"
            }
            Log.d("TtsManager", msg)
            ttsErrorMessage = msg
            openInstallTtsDataScreen()

            // Fallback to English if Hindi voice data is missing
            if (langCode == "hi") {
                Log.d("TtsManager", "Attempting fallback to Locale.US for TTS...")
                tts?.setLanguage(Locale.US)
            }
        } else {
            ttsErrorMessage = null
            isInitialized = true
        }

        return result
    }

    private fun openInstallTtsDataScreen() {
        try {
            Log.d("TtsManager", "Launching ACTION_INSTALL_TTS_DATA intent...")
            val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.d("TtsManager", "Failed to launch TTS data installation screen: ${e.message}")
        }
    }

    fun speak(text: String) {
        Log.d("TtsManager", "speak() requested. Text: '$text', isInitialized: $isInitialized")
        if (isInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Log.d("TtsManager", "Cannot speak - TTS is not initialized or language is unsupported")
        }
    }
    
    fun stopSpeech() {
        Log.d("TtsManager", "stopSpeech() called")
        tts?.stop()
    }

    fun shutdown() {
        Log.d("TtsManager", "shutdown() called")
        tts?.stop()
        tts?.shutdown()
    }
}