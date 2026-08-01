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

private data class AudioTask(val samples: ShortArray, val sampleRate: Int)

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

    companion object {
        private const val MAX_CHUNK_CHARS = 200
        private const val FORCE_BREAK_CHARS = 250
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
        
        // 5. Split by sentence-ending punctuation (followed by whitespace) or newlines
        val sentences = cleanText.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        // 6. Sub-chunk long sentences so the producer stays ahead of the consumer
        val chunks = sentences.flatMap { splitIntoChunks(it) }
            
        if (flush) stop()
        
        if (chunks.isEmpty()) {
            if (flush) onQueueFinished()
            return
        }
        
        isPaused = false
        itemsInFlight.addAndGet(chunks.size)
        
        for (chunk in chunks) {
            sentenceChannel.trySend(chunk)
        }
        
        if (producerJob?.isActive != true || consumerJob?.isActive != true) {
            startPipelines()
        }
    }

    /**
     * Splits text longer than MAX_CHUNK_CHARS at natural break points
     * (commas, semicolons, colons, " and ", " or ", dashes).
     * Force-breaks at FORCE_BREAK_CHARS on the last space if no natural break found.
     */
    private fun splitIntoChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
        
        val chunks = mutableListOf<String>()
        var remaining = text
        
        while (remaining.length > MAX_CHUNK_CHARS) {
            // Look for a natural break point within the target window
            var breakIdx = -1
            val searchEnd = minOf(remaining.length, FORCE_BREAK_CHARS)
            
            // Prefer breaking at comma/semicolon/colon followed by space
            for (i in searchEnd - 1 downTo MAX_CHUNK_CHARS / 2) {
                val c = remaining[i]
                if ((c == ',' || c == ';' || c == ':') && i + 1 < remaining.length && remaining[i + 1] == ' ') {
                    breakIdx = i + 1  // Include the punctuation, break after space
                    break
                }
            }
            
            // Try " and " or " or " as break points
            if (breakIdx == -1) {
                val andIdx = remaining.lastIndexOf(" and ", searchEnd)
                val orIdx = remaining.lastIndexOf(" or ", searchEnd)
                val conjIdx = maxOf(andIdx, orIdx)
                if (conjIdx >= MAX_CHUNK_CHARS / 2) {
                    breakIdx = conjIdx
                }
            }
            
            // Try an em-dash or hyphen surrounded by spaces
            if (breakIdx == -1) {
                val dashIdx = remaining.lastIndexOf(" — ", searchEnd)
                val hyphenIdx = remaining.lastIndexOf(" - ", searchEnd)
                val dIdx = maxOf(dashIdx, hyphenIdx)
                if (dIdx >= MAX_CHUNK_CHARS / 2) {
                    breakIdx = dIdx
                }
            }
            
            // Force-break at last space within window
            if (breakIdx == -1) {
                breakIdx = remaining.lastIndexOf(' ', searchEnd - 1)
                if (breakIdx < MAX_CHUNK_CHARS / 2) {
                    breakIdx = searchEnd  // No space found, hard break
                }
            }
            
            chunks.add(remaining.substring(0, breakIdx).trim())
            remaining = remaining.substring(breakIdx).trim()
        }
        
        if (remaining.isNotEmpty()) {
            chunks.add(remaining)
        }
        
        return chunks.filter { it.isNotEmpty() }
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
        audioChannel = Channel(capacity = 8)
    }

    private fun startPipelines() {
        producerJob = scope.launch {
            for (sentence in sentenceChannel) {
                if (!isActive) break
                Log.d("TTSManager", "Generating audio for (${sentence.length} chars): ${sentence.take(60)}...")
                try {
                    val ttsInstance = tts ?: break
                    val audio = ttsInstance.generate(sentence, sid = currentSid, speed = 1.0f)
                    if (isActive) {
                        // Convert float→short on the producer thread so the consumer
                        // can write to AudioTrack immediately without CPU work
                        val shortSamples = ShortArray(audio.samples.size)
                        for (i in audio.samples.indices) {
                            val f = audio.samples[i].coerceIn(-1.0f, 1.0f)
                            shortSamples[i] = (f * 32767.0f).toInt().toShort()
                        }
                        audioChannel.send(AudioTask(shortSamples, audio.sampleRate))
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
            var trackPlaying = false
            
            for (task in audioChannel) {
                if (!isActive) break
                
                val sampleRate = task.sampleRate
                if (audioTrack == null || audioTrack?.sampleRate != sampleRate) {
                    audioTrack?.release()
                    trackPlaying = false
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
                        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
                        .build()
                }

                val track = audioTrack ?: continue
                
                while (isPaused && isActive) {
                    kotlinx.coroutines.delay(50)
                }
                if (!isActive) break
                
                try {
                    // Only call play() once — subsequent writes stream seamlessly
                    if (!trackPlaying) {
                        track.play()
                        trackPlaying = true
                    }
                    track.write(task.samples, 0, task.samples.size, AudioTrack.WRITE_BLOCKING)
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
