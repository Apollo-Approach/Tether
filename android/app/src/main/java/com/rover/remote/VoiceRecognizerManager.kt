package com.rover.remote

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceRecognizerManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isForceFinishing = false
    private var accumulatedText = ""
    private var lastSilenceCheckTime = System.currentTimeMillis()
    
    private val silenceRunnable = object : Runnable {
        override fun run() {
            if (!isListening || isForceFinishing) return
            val now = System.currentTimeMillis()
            if (now - lastSilenceCheckTime > 3000) {
                Log.d("VoiceManager", "3 seconds of absolute silence detected. Ending transmission.")
                triggerStop()
            } else {
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("VoiceManager", "Speech recognition not available on this device")
            onError("Speech recognition not available")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (!isListening) return
                    Log.e("VoiceManager", "SpeechRecognizer error: $error")
                    
                    if (isForceFinishing) {
                        sendAndCleanUp()
                        return
                    }
                    
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_CLIENT) {
                        Log.d("VoiceManager", "Google timed out without hearing anything. Restarting microphone silently...")
                        startGoogleEngine()
                    } else if (error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        onError("Google ASR Error: $error")
                        sendAndCleanUp()
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (!isListening) return
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (matches != null && matches.isNotEmpty()) {
                        val text = matches[0].trim()
                        if (text.isNotEmpty()) {
                            accumulatedText = if (accumulatedText.isEmpty()) text else "$accumulatedText $text"
                        }
                    }
                    
                    if (isForceFinishing) {
                        sendAndCleanUp()
                    } else {
                        Log.d("VoiceManager", "Google cut off early. Restarting microphone silently. Current text: $accumulatedText")
                        startGoogleEngine()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!isListening) return
                    // We heard a word! Reset the silence timer
                    lastSilenceCheckTime = System.currentTimeMillis()
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startGoogleEngine() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    fun startListening() {
        if (isListening) return
        
        accumulatedText = ""
        isListening = true
        isForceFinishing = false
        lastSilenceCheckTime = System.currentTimeMillis()
        
        mainHandler.postDelayed(silenceRunnable, 500)
        toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
        startGoogleEngine()
    }
    
    private fun triggerStop() {
        if (!isListening || isForceFinishing) return
        isForceFinishing = true
        mainHandler.removeCallbacks(silenceRunnable)
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error stopping recognizer", e)
            sendAndCleanUp()
        }
    }

    private fun sendAndCleanUp() {
        isListening = false
        isForceFinishing = false
        mainHandler.removeCallbacks(silenceRunnable)
        
        val finalText = accumulatedText.trim()
        if (finalText.isNotEmpty()) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 100)
            onResult(finalText)
        } else {
            // Signal empty string via onError to release audio focus safely
            onError("Empty result")
        }
    }

    fun stopListening() {
        if (!isListening) return
        triggerStop()
    }

    fun destroy() {
        isListening = false
        isForceFinishing = false
        mainHandler.removeCallbacks(silenceRunnable)
        speechRecognizer?.destroy()
        speechRecognizer = null
        toneGenerator.release()
    }
}
