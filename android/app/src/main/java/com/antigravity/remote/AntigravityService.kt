package com.antigravity.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.content.pm.ServiceInfo
import org.json.JSONObject
import java.net.InetAddress

class AntigravityService : Service() {
    companion object {
        const val ACTION_CONNECT = "com.antigravity.remote.CONNECT"
        const val EXTRA_URL = "url"

        fun handleIncomingMessage(
            text: String,
            showMessageNotification: (String, String) -> Unit,
            onAssistantChat: (String) -> Unit = {}
        ) {
            try {
                val json = JSONObject(text)
                val type = json.optString("type")
                if (type == "chat") {
                    val role = json.optString("role", "user")
                    val msg = json.optString("message", "")
                    ConnectionRepository.addChatMessage(ChatMessage(role, msg))
                    if (role == "assistant") {
                        ConnectionRepository.setThinking(false)
                        showMessageNotification("New Message", msg)
                        onAssistantChat(msg)
                    }
                } else if (type == "artifact") {
                    val title = json.optString("title", "Artifact")
                    val content = json.optString("content", "")
                    val artifactMsg = ArtifactMessage(title, content)
                    ConnectionRepository.setArtifact(artifactMsg)
                    ConnectionRepository.addChatMessage(ChatMessage("assistant", "📝 **Artifact Updated:** $title\n\n$content"))
                    showMessageNotification("Artifact Updated", title)
                } else if (type == "handshake") {
                    val dataObj = json.optJSONObject("data")
                    if (dataObj != null) {
                        val projectsArray = dataObj.optJSONArray("projects")
                        if (projectsArray != null) {
                            val projects = mutableListOf<String>()
                            for (i in 0 until projectsArray.length()) {
                                projects.add(projectsArray.getString(i))
                            }
                            ConnectionRepository.setProjects(projects)
                        }
                        val currentProject = dataObj.optString("current_project", "")
                        if (currentProject.isNotEmpty()) {
                            ConnectionRepository.setCurrentProject(currentProject)
                        }
                        val activeProject = dataObj.optString("activeProject", "")
                        if (activeProject.isNotEmpty()) {
                            ConnectionRepository.setCurrentProject(activeProject)
                        }
                        val activeConv = dataObj.optString("activeConversation", "")
                        if (activeConv.isNotEmpty()) {
                            ConnectionRepository.setActiveConversation(activeConv, "")
                        }
                    }
                } else if (type == "approval_request") {
                    val title = json.optString("title", "Permission Request")
                    val optionsArray = json.optJSONArray("options")
                    val optionsList = mutableListOf<String>()
                    if (optionsArray != null) {
                        for (i in 0 until optionsArray.length()) {
                            optionsList.add(optionsArray.getString(i))
                        }
                    }
                    ConnectionRepository.setApprovalRequest(ApprovalRequest(title, optionsList))
                    ConnectionRepository.addChatMessage(ChatMessage("system", "[DEBUG] Received approval request: $title with ${optionsList.size} options"))
                    showMessageNotification("Approval Needed", title)
                } else if (type == "project_selected") {
                    val project = json.optString("project", "")
                    val conversationId = json.optString("conversationId", "")
                    val firstMessage = json.optString("firstMessage", "")
                    if (project.isNotEmpty()) {
                        ConnectionRepository.setCurrentProject(project)
                    }
                    ConnectionRepository.setActiveConversation(conversationId, firstMessage)
                    if (conversationId.isNotEmpty()) {
                        ConnectionRepository.addChatMessage(ChatMessage("system", "\uD83D\uDD17 Connected to conversation: ${firstMessage.take(60)}..."))
                    }
                } else if (type == "conversations") {
                    val dataArray = json.optJSONArray("data")
                    if (dataArray != null) {
                        val convos = mutableListOf<ConversationInfo>()
                        for (i in 0 until dataArray.length()) {
                            val c = dataArray.getJSONObject(i)
                            convos.add(ConversationInfo(
                                id = c.optString("id", ""),
                                lastActive = c.optString("lastActive", ""),
                                firstMessage = c.optString("firstMessage", "")
                            ))
                        }
                        ConnectionRepository.setAvailableConversations(convos)
                    }
                } else if (type == "thought") {
                    val textContent = json.optString("text", "")
                    if (textContent.isNotEmpty()) {
                        ConnectionRepository.appendThought(textContent)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val CHANNEL_ID = "AntigravityServiceChannel"
    private val MESSAGE_CHANNEL_ID = "AntigravityMessageChannel"
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val webSocketManager = WebSocketManager()
    
    private var ttsManager: TTSManager? = null
    private var audioMediaManager: AudioFocusAndMediaManager? = null
    private var voiceManager: VoiceRecognizerManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        ConnectionRepository.webSocketManager = webSocketManager
        
        ttsManager = TTSManager(this) {
            // TTS finished reading, start listening
            audioMediaManager?.abandonAudioFocus()
            voiceManager?.startListening()
        }
        ConnectionRepository.ttsManager = ttsManager
        
        audioMediaManager = AudioFocusAndMediaManager(this) {
            ttsManager?.togglePause()
        }
        
        voiceManager = VoiceRecognizerManager(this, onResult = { text ->
            val payload = JSONObject().apply {
                put("event", "chat")
                put("message", text)
            }
            webSocketManager.send(payload.toString())
            audioMediaManager?.abandonAudioFocus()
        }, onError = { error ->
            android.util.Log.e("AntigravityService", "Voice error: $error")
            audioMediaManager?.abandonAudioFocus()
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Antigravity Remote")
            .setContentText("Listening for connection...")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        if (intent?.action == ACTION_CONNECT) {
            val url = intent.getStringExtra(EXTRA_URL)
            if (url != null) {
                stopDiscovery()
                connectWebSocket(url)
            }
            return START_STICKY
        }
        
        startDiscovery()
        return START_STICKY
    }


    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Antigravity Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "Antigravity Messages",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(messageChannel)
        }
    }

    private fun startDiscovery() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host: InetAddress = serviceInfo.host
                        val port: Int = serviceInfo.port
                        val url = "ws://${host.hostAddress}:$port"
                        ConnectionRepository.updateConnectionStatus("Connecting...")
                        connectWebSocket(url)
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        
        try {
            nsdManager.discoverServices("_antigravity._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        discoveryListener = null
    }

    private fun connectWebSocket(url: String) {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocketManager.isCurrentWebSocket(webSocket)) {
                    ConnectionRepository.updateConnectionStatus("Authenticating...")
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocketManager.isCurrentWebSocket(webSocket)) {
                    ConnectionRepository.updateConnectionStatus("Disconnected")
                    updateForegroundNotification("Disconnected")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocketManager.isCurrentWebSocket(webSocket)) {
                    ConnectionRepository.updateConnectionStatus("Error: ${t.message}")
                    updateForegroundNotification("Connection Error")
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = org.json.JSONObject(text)
                    if (json.optString("type") == "auth_success") {
                        ConnectionRepository.updateConnectionStatus("Connected")
                        updateForegroundNotification("Connected to Antigravity")
                        return
                    }
                } catch(e: Exception) {}
                
                scope.launch {
                        handleIncomingMessage(text, 
                            showMessageNotification = { title, msg ->
                                showMessageNotification(title, msg)
                            },
                            onAssistantChat = { msg ->
                                if (audioMediaManager?.requestAudioFocus() == true) {
                                    ttsManager?.speak(msg)
                                }
                            }
                        )
                }
            }
        }
        webSocketManager.connect(url, listener)
    }

    private fun updateForegroundNotification(text: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Antigravity Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentIntent(pendingIntent)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, notification)
    }

    private fun showMessageNotification(title: String, text: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) {}
        }
        webSocketManager.disconnect()
        ttsManager?.release()
        audioMediaManager?.destroy()
        voiceManager?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
