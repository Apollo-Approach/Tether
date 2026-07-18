package com.antigravity.remote

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private data class AudioTask(val index: Int, val samples: FloatArray, val sampleRate: Int)

class TTSManager(private val context: Context, private val onQueueFinished: () -> Unit) {

    private var tts: OfflineTts? = null
    private var isInitialized = false
    private var currentSid = 0

    fun setVoice(sid: Int) {
        currentSid = sid
    }

    fun isReady() = isInitialized
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var producerJob: Job? = null
    private var consumerJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var audioChannel = Channel<AudioTask>(Channel.BUFFERED)
    
    private val sentenceQueue = mutableListOf<String>()
    private var currentSentenceIndex = 0
    @Volatile private var currentlyPlayingIndex = 0
    private var isPaused = false

    init {
        scope.launch {
            initTts()
        }
    }

    private suspend fun initTts() = withContext(Dispatchers.IO) {
        try {
            val modelDir = "kokoro-en-v0_19"
            
            // Extract espeak-ng-data to filesDir because the native eSpeak library
            // cannot read directly from the Android AssetManager memory space.
            val espeakDataPath = "kokoro-en-v0_19/espeak-ng-data"
            val destEspeakDir = File(context.filesDir, "espeak-ng-data")
            if (!destEspeakDir.exists()) {
                destEspeakDir.mkdirs()
                copyAssetFolder(espeakDataPath, destEspeakDir)
            }
            
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$modelDir/model.onnx",
                        voices = "$modelDir/voices.bin",
                        tokens = "$modelDir/tokens.txt",
                        dataDir = destEspeakDir.absolutePath,
                        lengthScale = 1.0f
                    ),
                    numThreads = 4,
                    debug = true,
                    provider = "cpu"
                )
            )
            
            tts = OfflineTts(assetManager = context.assets, config = config)
            isInitialized = true
            Log.d("TTSManager", "Sherpa-onnx TTS initialized from assets (eSpeak data extracted).")
        } catch (e: Exception) {
            Log.e("TTSManager", "Failed to init TTS: ${e.message}")
        }
    }

    private fun copyAssetFolder(src: String, dest: File) {
        val assets = context.assets.list(src) ?: return
        if (assets.isEmpty()) {
            val inStream = context.assets.open(src)
            val outStream = FileOutputStream(dest)
            inStream.copyTo(outStream)
            inStream.close()
            outStream.close()
        } else {
            dest.mkdirs()
            for (asset in assets) {
                copyAssetFolder("$src/$asset", File(dest, asset))
            }
        }
    }

    fun speak(text: String) {
        if (!isInitialized) return
        
        // Strip markdown characters (like asterisks and hashes) that shouldn't be spoken
        val cleanText = text.replace("*", "").replace("#", "")
        
        val sentences = cleanText.split(Regex("(?<=[.!?,\":;])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            
        sentenceQueue.clear()
        sentenceQueue.addAll(sentences)
        currentSentenceIndex = 0
        currentlyPlayingIndex = 0
        isPaused = false
        
        if (sentenceQueue.isNotEmpty()) {
            startPipelines()
        } else {
            onQueueFinished()
        }
    }

    private fun startPipelines() {
        producerJob?.cancel()
        consumerJob?.cancel()
        audioChannel.cancel()
        audioChannel = Channel(capacity = 5) // Buffer up to 5 sentences
        
        producerJob = scope.launch {
            while (currentSentenceIndex < sentenceQueue.size && isActive) {
                val idx = currentSentenceIndex
                val sentence = sentenceQueue[idx]
                val ttsInstance = tts ?: break
                
                Log.d("TTSManager", "Generating audio for: $sentence")
                try {
                    val audio = ttsInstance.generate(sentence, sid = currentSid, speed = 1.0f)
                    if (isActive) {
                        audioChannel.send(AudioTask(idx, audio.samples, audio.sampleRate))
                        currentSentenceIndex++
                    }
                } catch (e: Exception) {
                    Log.e("TTSManager", "Error generating TTS: ${e.message}")
                    currentSentenceIndex++
                }
            }
            audioChannel.close()
        }
        
        consumerJob = scope.launch {
            // Prevent initial stutter by buffering up to 2 sentences (or all sentences if < 2) before playback begins
            while (isActive && currentSentenceIndex < 2 && currentSentenceIndex < sentenceQueue.size) {
                kotlinx.coroutines.delay(50)
            }
            
            for (task in audioChannel) {
                if (!isActive) break
                currentlyPlayingIndex = task.index
                
                val sampleRate = task.sampleRate
                if (audioTrack == null || audioTrack?.sampleRate != sampleRate) {
                    audioTrack?.release()
                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 2)
                        .build()
                }

                val track = audioTrack ?: continue
                
                while (isPaused && isActive) {
                    kotlinx.coroutines.delay(50)
                }
                if (!isActive) break
                
                try {
                    track.play()
                    track.write(task.samples, 0, task.samples.size, AudioTrack.WRITE_BLOCKING)
                } catch (e: Exception) {
                    Log.e("TTSManager", "Error playing track: ${e.message}")
                }
            }
            
            if (isActive) {
                withContext(Dispatchers.Main) {
                    onQueueFinished()
                }
            }
        }
    }

    fun togglePause() {
        if (isPaused) {
            resume()
        } else {
            pause()
        }
    }

    fun pause() {
        isPaused = true
        audioTrack?.pause()
    }

    fun resume() {
        isPaused = false
        audioTrack?.play()
    }

    fun skip() {
        if (isPaused) {
            isPaused = false
        }
        audioTrack?.flush()
        audioTrack?.pause()
        currentSentenceIndex = currentlyPlayingIndex + 1
        startPipelines()
    }
    
    fun release() {
        producerJob?.cancel()
        consumerJob?.cancel()
        audioChannel.cancel()
        audioTrack?.release()
        audioTrack = null
        tts?.release()
        tts = null
    }
}
