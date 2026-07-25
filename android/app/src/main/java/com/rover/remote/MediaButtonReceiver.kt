package com.rover.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives media button intents from the MediaStyle notification actions
 * (Play, Pause, Stop) and forwards them to the TTSManager via ConnectionRepository.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("MediaButtonReceiver", "Received action: $action")

        when (action) {
            "ACTION_PLAY" -> {
                ConnectionRepository.ttsManager?.resume()
            }
            "ACTION_PAUSE" -> {
                ConnectionRepository.ttsManager?.pause()
            }
            "ACTION_STOP" -> {
                ConnectionRepository.ttsManager?.skip()
            }
            "ACTION_PLAY_PAUSE" -> {
                ConnectionRepository.ttsManager?.togglePause()
            }
        }
    }
}
