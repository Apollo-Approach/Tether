package com.rover.remote

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
import kotlinx.coroutines.cancel
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
    private var sentenceChannel = Channel<String>(Channel.UNLIMITED)
    private var audioChannel = Channel<AudioTask>(5)
    
    private val itemsInFlight = java.util.concurrent.atomic.AtomicInteger(0)
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
            val flagFile = File(destEspeakDir, "extracted_v2.txt")
            if (!flagFile.exists()) {
                Log.d("TTSManager", "Extracting espeak-ng-data to filesDir...")
                destEspeakDir.deleteRecursively()
                destEspeakDir.mkdirs()
                copyAssetFolder(espeakDataPath, destEspeakDir)
                flagFile.writeText("done")
                Log.d("TTSManager", "Extraction complete.")
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
                    numThreads = 6,
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

    fun speak(text: String, flush: Boolean = false) {
        if (!isInitialized) return
        
        // 1. Convert markdown links [text](url) to just "text"
        var cleanText = text.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        
        // 2. Truncate Windows paths (e.g. C:\Users\devon\... \file.txt) to just the filename
        cleanText = cleanText.replace(Regex("""[a-zA-Z]:\\[\\\w\-. ]+\\([\w\-.]+)"""), "$1")
        
        // 3. Truncate Unix paths (e.g. /home/user/... /file.txt) 
        cleanText = cleanText.replace(Regex("""/(?:[\w\-. ]+/)+([\w\-.]+)"""), "$1")
        
        // 4. Strip leftover markdown characters
        cleanText = cleanText.replace("*", "").replace("#", "")
        
        val sentences = cleanText.split(Regex("(?<=[.!?,;:\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            
        if (flush) stop()
        
        if (sentences.isEmpty()) {
            if (flush) onQueueFinished()
            return
        }
        
        isPaused = false
        itemsInFlight.addAndGet(sentences.size)
        
        for (s in sentences) {
            sentenceChannel.trySend(s)
        }
        
        if (producerJob?.isActive != true || consumerJob?.isActive != true) {
            startPipelines()
        }
    }
    
    fun stop() {
        isPaused = false
        itemsInFlight.set(0)
        producerJob?.cancel()
        consumerJob?.cancel()
        sentenceChannel.cancel()
        audioChannel.cancel()
        audioTrack?.pause()
        audioTrack?.flush()
        
        sentenceChannel = Channel(Channel.UNLIMITED)
        audioChannel = Channel(capacity = 5)
    }

    private fun startPipelines() {
        producerJob = scope.launch {
            for (sentence in sentenceChannel) {
                if (!isActive) break
                Log.d("TTSManager", "Generating audio for: $sentence")
                try {
                    val ttsInstance = tts ?: break
                    val audio = ttsInstance.generate(sentence, sid = currentSid, speed = 1.0f)
                    if (isActive) {
                        audioChannel.send(AudioTask(0, audio.samples, audio.sampleRate))
                    } else {
                        checkQueueFinished()
                    }
                } catch (e: Exception) {
                    Log.e("TTSManager", "Error generating TTS: ${e.message}")
                    checkQueueFinished()
                }
            }
        }
        
        consumerJob = scope.launch {
            for (task in audioChannel) {
                if (!isActive) break
                
                val sampleRate = task.sampleRate
                if (audioTrack == null || audioTrack?.sampleRate != sampleRate) {
                    audioTrack?.release()
                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)
                        .build()
                }

                val track = audioTrack ?: continue
                
                while (isPaused && isActive) {
                    kotlinx.coroutines.delay(50)
                }
                if (!isActive) break
                
                try {
                    track.play()
                    val shortSamples = ShortArray(task.samples.size)
                    for (i in task.samples.indices) {
                        val f = task.samples[i].coerceIn(-1.0f, 1.0f)
                        shortSamples[i] = (f * 32767.0f).toInt().toShort()
                    }
                    track.write(shortSamples, 0, shortSamples.size, AudioTrack.WRITE_BLOCKING)
                } catch (e: Exception) {
                    Log.e("TTSManager", "Error playing track: ${e.message}")
                }
                
                checkQueueFinished()
            }
        }
    }
    
    private suspend fun checkQueueFinished() {
        val remaining = itemsInFlight.decrementAndGet()
        if (remaining <= 0) {
            itemsInFlight.set(0)
            withContext(Dispatchers.Main) {
                onQueueFinished()
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
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
        checkQueueFinishedSynchronous()
    }
    
    private fun checkQueueFinishedSynchronous() {
        val remaining = itemsInFlight.decrementAndGet()
        if (remaining <= 0) {
            itemsInFlight.set(0)
            // Can't easily jump to Main thread without coroutine scope here, but skip is rare.
            // Best effort fallback
            onQueueFinished()
        }
    }
    
    fun release() {
        stop()
    }
}
