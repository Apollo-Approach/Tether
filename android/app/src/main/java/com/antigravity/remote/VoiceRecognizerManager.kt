package com.antigravity.remote

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
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    fun startListening() {
        mainHandler.post {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                // Play a tiny beep to signal to the user (in their headphones) that it's time to speak
                toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)

                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    
                    // Increase silence timeouts to prevent cutting the user off during mid-sentence pauses
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("VoiceManager", "Ready for speech")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d("VoiceManager", "Beginning of speech")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d("VoiceManager", "End of speech")
                        // Play a tiny confirmation beep
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 100)
                    }

                    override fun onError(error: Int) {
                        Log.e("VoiceManager", "Speech recognition error: $error")
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        val errorMsg = when(error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                            else -> "Error $error"
                        }
                        onError(errorMsg)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d("VoiceManager", "Results: $text")
                        
                        if (text.isNotBlank()) {
                            onResult(text)
                        } else {
                            onError("Empty result")
                        }
                        
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                speechRecognizer?.startListening(intent)
            } else {
                Log.e("VoiceManager", "Speech recognition not available")
                onError("Not available")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            speechRecognizer?.stopListening()
        }
    }

    fun destroy() {
        mainHandler.post {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            toneGenerator.release()
        }
    }
}
