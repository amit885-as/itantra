package com.amit.itantra

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SpeechManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    private val _speechText = MutableStateFlow("")
    val speechText: StateFlow<String> = _speechText

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private var useDefaultLanguageFallback = false
    private var isUserIntendingToListen = false
    private var accumulatedText = ""
    private var currentLanguageCode = ""

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error (code $errorCode)"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error (code $errorCode)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions (code $errorCode)"
            SpeechRecognizer.ERROR_NETWORK -> "Network error (code $errorCode)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout (code $errorCode)"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized (code $errorCode)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service busy (code $errorCode)"
            SpeechRecognizer.ERROR_SERVER -> "Server error (code $errorCode)"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected (code $errorCode)"
            13 -> "Language unavailable (code 13)"
            else -> "Speech recognition error (code $errorCode)"
        }
    }

    private fun initRecognizer(languageCode: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.d("SpeechManager", "Speech recognition is NOT available on this device!")
            _speechText.value = "Error: Speech recognition unavailable on device"
            return
        }

        if (speechRecognizer != null) {
            Log.d("SpeechManager", "[INIT] Destroying old SpeechRecognizer for locale change to $languageCode")
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.setRecognitionListener(null)
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.d("SpeechManager", "[INIT] Error destroying speechRecognizer: ${e.message}")
            }
            speechRecognizer = null
        }

        useDefaultLanguageFallback = false

        Log.d("SpeechManager", "[INIT] Creating new SpeechRecognizer for locale: $languageCode")
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        currentLanguageCode = languageCode

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("SpeechManager", "[CALLBACK] onReadyForSpeech for locale: $currentLanguageCode")
            }

            override fun onBeginningOfSpeech() {
                Log.d("SpeechManager", "[CALLBACK] onBeginningOfSpeech for locale: $currentLanguageCode")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {
                Log.d("SpeechManager", "[CALLBACK] onBufferReceived for locale: $currentLanguageCode")
            }

            override fun onEndOfSpeech() {
                Log.d("SpeechManager", "[CALLBACK] onEndOfSpeech for locale: $currentLanguageCode")
            }

            override fun onError(error: Int) {
                val errorMsg = getErrorMessage(error)
                Log.d("SpeechManager", "[CALLBACK] onError code: $error ($errorMsg) for locale: $currentLanguageCode")
                
                if (error == 13 && !useDefaultLanguageFallback) {
                    useDefaultLanguageFallback = true
                    _speechText.value = "Error 13: Language unavailable. Tap Speak again to retry."
                    _isListening.value = false
                    isUserIntendingToListen = false
                } else if (isUserIntendingToListen && (
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || 
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                )) {
                    Log.d("SpeechManager", "[RETRY] Restarting recognizer after transient error ($error) for locale: $currentLanguageCode")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isUserIntendingToListen) {
                            startRecognizer()
                        }
                    }, 100)
                } else {
                    if (!isUserIntendingToListen) {
                        _isListening.value = false
                    } else {
                        _speechText.value = errorMsg
                        _isListening.value = false
                        isUserIntendingToListen = false
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("SpeechManager", "[CALLBACK] onResults received for locale: $currentLanguageCode, matches: $matches")
                if (!matches.isNullOrEmpty()) {
                    val newText = matches[0]
                    accumulatedText = if (accumulatedText.isEmpty()) newText else "$accumulatedText $newText"
                    _speechText.value = accumulatedText
                    Log.d("SpeechManager", "Successfully recognized text: '$accumulatedText'")
                }
                
                if (isUserIntendingToListen) {
                    startRecognizer()
                } else {
                    _isListening.value = false
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("SpeechManager", "[CALLBACK] onPartialResults for locale: $currentLanguageCode, matches: $matches")
                if (!matches.isNullOrEmpty()) {
                    val partial = matches[0]
                    val display = if (accumulatedText.isEmpty()) partial else "$accumulatedText $partial"
                    _speechText.value = display
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.d("SpeechManager", "[START_RECOGNIZER] Speech recognition NOT available on this device")
            _speechText.value = "Error: Speech recognition unavailable on device"
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (!useDefaultLanguageFallback) {
                Log.d("SpeechManager", "[START_RECOGNIZER] Setting EXTRA_LANGUAGE locale to: $currentLanguageCode")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageCode)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguageCode)
            } else {
                Log.d("SpeechManager", "[START_RECOGNIZER] Using DEFAULT system language (fallback triggered)")
            }
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            speechRecognizer?.startListening(intent)
            Log.d("SpeechManager", "[START_RECOGNIZER] startListening() called successfully for locale: $currentLanguageCode")
        } catch (e: Exception) {
            Log.d("SpeechManager", "[START_RECOGNIZER FAILED] Exception: ${e.message}")
            _speechText.value = "Error starting speech recognition: ${e.message}"
        }
    }

    fun startListening(languageCode: String) {
        Log.d("SpeechManager", "[START_LISTENING] Active language code requested: $languageCode")
        useDefaultLanguageFallback = false
        currentLanguageCode = languageCode
        if (speechRecognizer == null) {
            initRecognizer(languageCode)
        }
        
        isUserIntendingToListen = true
        accumulatedText = ""
        _isListening.value = true
        _speechText.value = "Listening..."
        startRecognizer()
    }

    fun stopListening() {
        Log.d("SpeechManager", "[STOP_LISTENING] Stopping listening for locale: $currentLanguageCode")
        isUserIntendingToListen = false
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.d("SpeechManager", "[STOP_LISTENING FAILED] Exception: ${e.message}")
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isUserIntendingToListen && _isListening.value) {
                Log.d("SpeechManager", "[STOP_LISTENING] Safety timeout reached, resetting isListening to false")
                _isListening.value = false
            }
        }, 2000)
    }

    fun destroy() {
        Log.d("SpeechManager", "[DESTROY] Destroying SpeechManager and SpeechRecognizer instance")
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.d("SpeechManager", "[DESTROY FAILED] Exception: ${e.message}")
        }
        speechRecognizer = null
    }
}