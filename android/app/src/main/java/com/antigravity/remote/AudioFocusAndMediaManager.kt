package com.antigravity.remote

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log

class AudioFocusAndMediaManager(
    private val context: Context,
    private val onPlayPauseToggle: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        setupMediaSession()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(context, "AntigravityMediaSession")
        mediaSession?.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession?.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                val keyEvent = mediaButtonIntent.getParcelableExtra<android.view.KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null && keyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    val keyCode = keyEvent.keyCode
                    if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ||
                        keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE ||
                        keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK) {
                        Log.d("AudioMediaManager", "MediaButtonEvent intercepted: $keyCode")
                        onPlayPauseToggle()
                        return true
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }

            override fun onPlay() {
                Log.d("AudioMediaManager", "MediaSession onPlay received")
                onPlayPauseToggle()
                updatePlaybackState(PlaybackState.STATE_PLAYING)
            }

            override fun onPause() {
                Log.d("AudioMediaManager", "MediaSession onPause received")
                onPlayPauseToggle()
                updatePlaybackState(PlaybackState.STATE_PAUSED)
            }

            override fun onStop() {
                Log.d("AudioMediaManager", "MediaSession onStop received")
                onPlayPauseToggle()
                updatePlaybackState(PlaybackState.STATE_STOPPED)
            }
            
            // For Bluetooth double taps, sometimes it sends skipToNext
            override fun onSkipToNext() {
                Log.d("AudioMediaManager", "MediaSession onSkipToNext received (treating as toggle)")
                onPlayPauseToggle()
            }
        })
        
        mediaSession?.isActive = true
        updatePlaybackState(PlaybackState.STATE_STOPPED)
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT
            )
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    fun requestAudioFocus(): Boolean {
        // We use GAIN_TRANSIENT so podcasts pause completely while agent talks.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                Log.d("AudioMediaManager", "Focus change: $focusChange")
            }
            .build()

        val result = audioManager.requestAudioFocus(focusRequest!!)
        
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            updatePlaybackState(PlaybackState.STATE_PLAYING)
            return true
        }
        return false
    }

    fun abandonAudioFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        updatePlaybackState(PlaybackState.STATE_STOPPED)
    }

    fun destroy() {
        abandonAudioFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
    }
}
