package com.rover.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

class AudioFocusAndMediaManager(
    private val context: Context,
    private val onPlayPauseToggle: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var isPlaying = false
    private var currentText = ""

    companion object {
        const val MEDIA_CHANNEL_ID = "RoverTTSChannel"
        const val MEDIA_NOTIFICATION_ID = 3001
    }

    init {
        createMediaNotificationChannel()
        setupMediaSession()
    }

    private fun createMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MEDIA_CHANNEL_ID,
                "Voice Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for AI voice playback"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(context, "RoverMediaSession")
        mediaSession?.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession?.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val keyEvent = mediaButtonIntent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null && keyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    val keyCode = keyEvent.keyCode
                    if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ||
                        keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE ||
                        keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK) {
                        Log.d("AudioMediaManager", "MediaButtonEvent intercepted: $keyCode")
                        onPlayPauseToggle()
                        isPlaying = !isPlaying
                        updateMediaNotification()
                        return true
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }

            override fun onPlay() {
                Log.d("AudioMediaManager", "MediaSession onPlay received")
                onPlayPauseToggle()
                isPlaying = true
                updatePlaybackState(PlaybackState.STATE_PLAYING)
                updateMediaNotification()
            }

            override fun onPause() {
                Log.d("AudioMediaManager", "MediaSession onPause received")
                onPlayPauseToggle()
                isPlaying = false
                updatePlaybackState(PlaybackState.STATE_PAUSED)
                updateMediaNotification()
            }

            override fun onStop() {
                Log.d("AudioMediaManager", "MediaSession onStop received")
                onPlayPauseToggle()
                isPlaying = false
                updatePlaybackState(PlaybackState.STATE_STOPPED)
                dismissMediaNotification()
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

    // ─── Media Card Notification ───

    fun showMediaNotification(text: String) {
        currentText = text
        isPlaying = true
        updatePlaybackState(PlaybackState.STATE_PLAYING)
        updateMediaNotification()
    }

    fun updateMediaNotificationPaused(paused: Boolean) {
        isPlaying = !paused
        updatePlaybackState(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
        updateMediaNotification()
    }

    fun dismissMediaNotification() {
        isPlaying = false
        currentText = ""
        notificationManager.cancel(MEDIA_NOTIFICATION_ID)
        updatePlaybackState(PlaybackState.STATE_STOPPED)
    }

    private fun updateMediaNotification() {
        if (currentText.isEmpty()) return

        val sessionToken = mediaSession?.sessionToken ?: return

        // Launch app on notification tap
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Play/Pause action
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause, "Pause",
                buildMediaAction(PlaybackState.ACTION_PAUSE)
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play, "Play",
                buildMediaAction(PlaybackState.ACTION_PLAY)
            ).build()
        }

        // Stop action
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_delete, "Stop",
            buildMediaAction(PlaybackState.ACTION_STOP)
        ).build()

        // Truncate text for notification display
        val displayText = if (currentText.length > 120) currentText.take(120) + "…" else currentText

        val notification = NotificationCompat.Builder(context, MEDIA_CHANNEL_ID)
            .setContentTitle("Rover")
            .setContentText(displayText)
            .setSubText(if (isPlaying) "Speaking" else "Paused")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(
                        android.support.v4.media.session.MediaSessionCompat.Token.fromToken(sessionToken)
                    )
                    .setShowActionsInCompactView(0, 1)
            )
            .setOngoing(isPlaying)
            .setSilent(true)
            .build()

        notificationManager.notify(MEDIA_NOTIFICATION_ID, notification)
    }

    private fun buildMediaAction(action: Long): PendingIntent {
        val intent = Intent(context, MediaButtonReceiver::class.java).apply {
            this.action = when (action) {
                PlaybackState.ACTION_PLAY -> "ACTION_PLAY"
                PlaybackState.ACTION_PAUSE -> "ACTION_PAUSE"
                PlaybackState.ACTION_STOP -> "ACTION_STOP"
                else -> "ACTION_PLAY_PAUSE"
            }
        }
        return PendingIntent.getBroadcast(
            context, action.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ─── Audio Focus ───

    fun requestAudioFocus(): Boolean {
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
        dismissMediaNotification()
        abandonAudioFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
    }
}
