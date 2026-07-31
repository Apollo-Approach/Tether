package com.rover.remote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceRecognizerManager(
    private val context: Context,
    private val btRoutingManager: BluetoothVoiceRoutingManager,
    private val onResult: (String) -> Unit,
    private val onStateChange: (Boolean) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    var isListening = false
        private set

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(this)
        } else {
            Log.e("VoiceRecognizer", "Speech recognition is not available on this device.")
        }
    }

    fun startListening() {
        if (isListening) return
        
        // 1. Enable Bluetooth audio routing before we start listening
        btRoutingManager.enableBluetoothMicRouting()
        
        // 2. Mark as listening immediately to prevent double-taps triggering multiple instances
        isListening = true
        onStateChange(true)
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // Force on-device for speed
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Enforce a strict 3-second silence timeout (fixes car Bluetooth road noise VAD hangs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
        
        // Delay 1 second to allow the Bluetooth SCO link to establish before capturing audio
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (isListening) { // Ensure they haven't stopped it already
                    speechRecognizer?.startListening(intent)
                }
            } catch (e: Exception) {
                Log.e("VoiceRecognizer", "Failed to start listening", e)
                cleanupRouting()
            }
        }, 1000)
    }

    fun stopListening() {
        if (!isListening) return
        speechRecognizer?.stopListening()
        // Wait for onResults or onError to clean up routing
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        cleanupRouting()
    }

    private fun cleanupRouting() {
        isListening = false
        onStateChange(false)
        
        // Delay restoring the audio mode by 1.5 seconds.
        // This prevents a native SIGABRT crash in libgoogle_speech_jni.so caused by
        // yanking the Bluetooth SCO route away while the AudioRecord is still tearing down.
        Handler(Looper.getMainLooper()).postDelayed({
            btRoutingManager.disableBluetoothMicRouting()
        }, 1500)
    }

    // --- RecognitionListener Callbacks ---

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d("VoiceRecognizer", "Ready for speech")
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
            Handler(Looper.getMainLooper()).postDelayed({
                toneGen.release()
            }, 200)
        } catch (e: Exception) {
            Log.e("VoiceRecognizer", "Failed to play chime", e)
        }
    }

    override fun onBeginningOfSpeech() {
        Log.d("VoiceRecognizer", "Beginning of speech")
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d("VoiceRecognizer", "End of speech")
    }

    override fun onError(error: Int) {
        Log.e("VoiceRecognizer", "Speech recognition error: $error")
        cleanupRouting()
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val text = matches[0]
            Log.d("VoiceRecognizer", "Recognized: $text")
            onResult(text)
        }
        cleanupRouting()
    }

    override fun onPartialResults(partialResults: Bundle?) {}

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
