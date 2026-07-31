package com.rover.remote

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
import kotlinx.coroutines.cancel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.content.pm.ServiceInfo
import org.json.JSONObject
import java.net.InetAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import android.net.wifi.WifiManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
class RoverService : Service() {
    companion object {
        const val ACTION_CONNECT = "com.rover.remote.CONNECT"
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
                    if (role == "assistant" || role == "system") {
                        ConnectionRepository.setThinking(false)
                    }
                    if (role == "assistant") {
                        showMessageNotification("New Message", msg)
                        onAssistantChat(msg)
                    }
                } else if (type == "artifact") {
                    val title = json.optString("title", "Artifact")
                    val content = json.optString("content", "")
                    val artifactMsg = ArtifactMessage(title, content)
                    ConnectionRepository.setArtifact(artifactMsg)
                    ConnectionRepository.addChatMessage(ChatMessage("assistant", "📝 **Artifact Updated:** $title"))
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
                        val activeProject = dataObj.optString("activeProject", "")
                        val activeConv = dataObj.optString("activeConversation", "")
                        
                        val projectToUse = if (activeProject.isNotEmpty()) activeProject else currentProject
                        
                        if (projectToUse.isNotEmpty()) {
                            ConnectionRepository.setCurrentProject(projectToUse)
                            // Auto-select the project on the backend if no conversation is currently active
                            if (activeConv.isEmpty()) {
                                val payload = org.json.JSONObject().apply {
                                    put("event", "select_project")
                                    put("project", projectToUse)
                                }
                                ConnectionRepository.webSocketManager?.send(payload.toString())
                            }
                        }
                        
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
                    showMessageNotification("Approval Needed", title)
                } else if (type == "project_selected") {
                    val project = json.optString("project", "")
                    val conversationId = json.optString("conversationId", "")
                    val firstMessage = json.optString("firstMessage", "")
                    if (project.isNotEmpty()) {
                        ConnectionRepository.setCurrentProject(project)
                    }
                    val changed = ConnectionRepository.setActiveConversation(conversationId, firstMessage)
                    if (changed && conversationId.isNotEmpty()) {
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
                                parentId = c.optString("parentId", ""),
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
                } else if (type == "tasks_update") {
                    val tasksArray = json.optJSONArray("tasks")
                    if (tasksArray != null) {
                        val tasksList = mutableListOf<RunningTask>()
                        for (i in 0 until tasksArray.length()) {
                            val taskObj = tasksArray.getJSONObject(i)
                            tasksList.add(RunningTask(
                                id = taskObj.getString("id"),
                                name = taskObj.getString("name")
                            ))
                        }
                        ConnectionRepository.setRunningTasks(tasksList)
                    }
                } else if (type == "queue_update") {
                    val messagesArray = json.optJSONArray("messages")
                    if (messagesArray != null) {
                        val msgsList = mutableListOf<String>()
                        for (i in 0 until messagesArray.length()) {
                            msgsList.add(messagesArray.getString(i))
                        }
                        ConnectionRepository.setQueuedMessages(msgsList)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val CHANNEL_ID = Config.FOREGROUND_SERVICE_CHANNEL_ID
    private val MESSAGE_CHANNEL_ID = Config.MESSAGE_NOTIFICATION_CHANNEL_ID
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var udpDiscoveryJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val webSocketManager = WebSocketManager()
    
    private var ttsManager: TTSManager? = null
    private var audioMediaManager: AudioFocusAndMediaManager? = null
    
    private var btRoutingManager: BluetoothVoiceRoutingManager? = null
    private var voiceRecognizerManager: VoiceRecognizerManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        ConnectionRepository.webSocketManager = webSocketManager
        
        btRoutingManager = BluetoothVoiceRoutingManager(this)
        voiceRecognizerManager = VoiceRecognizerManager(
            context = this,
            btRoutingManager = btRoutingManager!!,
            onResult = { text ->
                if (text.isNotBlank() && ConnectionRepository.state.value.connectionStatus == "Connected") {
                    val json = org.json.JSONObject().apply {
                        put("event", "chat")
                        put("message", text)
                        put("project", ConnectionRepository.state.value.currentProject)
                    }
                    webSocketManager.send(json.toString())
                    ConnectionRepository.setThinking(true)
                }
            },
            onStateChange = { isListening ->
                ConnectionRepository.setMicListening(isListening)
            }
        )
        
        
        ttsManager = TTSManager(this) {
            // TTS finished reading
            audioMediaManager?.dismissMediaNotification()
            
            // Interaction mode handling for hands-free
            if (ConnectionRepository.state.value.interactionMode == InteractionMode.HANDS_FREE) {
                voiceRecognizerManager?.startListening()
            }
        }
        ConnectionRepository.ttsManager = ttsManager
        
        audioMediaManager = AudioFocusAndMediaManager(
            context = this, 
            onPlayPauseToggle = {
                ttsManager?.togglePause()
            },
            onStopAction = {
                ttsManager?.stop()
                audioMediaManager?.abandonAudioFocus()
            }
        )
        
        val isTrustedNetworkFlow = MutableStateFlow(false)

        var lastVerifiedSsid: String? = null
        
        scope.launch {
            while (isActive) {
                var isTrusted = false
                val netState = ConnectionRepository.networkManager?.networkState?.value
                if (netState != null) {
                    val isWifi = netState.type == NetworkType.WIFI
                    val ssid = netState.ssid
                    if (isWifi && ssid != null && ssid != "<unknown ssid>") {
                        val trusted = ConnectionRepository.state.value.trustedNetworks.find { it.ssid == ssid }
                        if (trusted != null) {
                            if (lastVerifiedSsid == ssid) {
                                isTrusted = true
                            } else {
                                val loc = LocationVerifier.getCurrentLocation(this@RoverService)
                                if (loc != null) {
                                    val target = android.location.Location("").apply {
                                        latitude = trusted.lat
                                        longitude = trusted.lng
                                    }
                                    // Increased from 100m to 1000m to account for indoor cell-tower fallback drift,
                                    // while still strictly preventing evil twin attacks from other locations.
                                    if (loc.distanceTo(target) <= 1000f) {
                                        isTrusted = true
                                        lastVerifiedSsid = ssid
                                    }
                                }
                            }
                        } else {
                            lastVerifiedSsid = null
                        }
                    } else {
                        lastVerifiedSsid = null
                    }
                } else {
                    lastVerifiedSsid = null
                }

                val wasTrusted = isTrustedNetworkFlow.value
                isTrustedNetworkFlow.value = isTrusted
                
                // If we crossed a trust boundary, sever the current WebSocket connection 
                // to force a reconnect on the correct network path.
                // We leave Tailscale running in the background because tsnet hangs if restarted.
                if (wasTrusted != isTrusted) {
                    webSocketManager.disconnect()
                }

                if (isTrusted && ConnectionRepository.state.value.connectionStatus != "Connected") {
                    if (udpDiscoveryJob == null) {
                        startDiscovery()
                    }
                } else {
                    stopDiscovery()
                }
                
                delay(2000)
            }
        }
        
        scope.launch {
            var lastTailscaleConnectAttempt = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                val status = ConnectionRepository.state.value.connectionStatus
                val isTrusted = isTrustedNetworkFlow.value
                
                if (!isTrusted && (status == "Disconnected" || status.startsWith("Error")) && now - lastTailscaleConnectAttempt > Config.AUTO_CONNECT_COOLDOWN_MS) {
                    val lastHost = ConnectionRepository.getLastConnectedHost()
                    if (lastHost != null) {
                        // Ensure Tailscale is running in the background for proxy routing
                        TailscaleManager.start(this@RoverService)
                        
                        // Query the Go daemon directly instead of relying on the UI drawer's polling loop
                        try {
                            if (TailscaleManager.status.value == "Connected") {
                                val jsonString = tsnet_wrapper.Tsnet_wrapper.getPeers()
                                val jsonArray = org.json.JSONArray(jsonString)
                                var targetIp = ""
                                var isOnline = false
                                
                                for (i in 0 until jsonArray.length()) {
                                    val obj = jsonArray.getJSONObject(i)
                                    val peerHostname = obj.getString("hostname").lowercase().replace(Regex("[^a-z0-9]"), "")
                                    val sanitizedLastHost = lastHost.lowercase().replace(Regex("[^a-z0-9]"), "")
                                    
                                    if (peerHostname == sanitizedLastHost || peerHostname.contains(sanitizedLastHost) || sanitizedLastHost.contains(peerHostname)) {
                                        targetIp = obj.optString("ip", "")
                                        isOnline = obj.getBoolean("online")
                                        break
                                    }
                                }
                                
                                if (isOnline && targetIp.isNotEmpty()) {
                                    lastTailscaleConnectAttempt = now
                                    ConnectionRepository.updateConnectedHostName(lastHost)
                                    ConnectionRepository.updateConnectionStatus("Connecting...")
                                    val formattedHost = if (targetIp.contains(":") && !targetIp.startsWith("[")) "[$targetIp]" else targetIp
                                    val url = "${Config.WS_SCHEME}$formattedHost:${Config.TSNET_PROXY_PORT}"
                                    connectWebSocket(url)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                delay(2000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RoverService::class.java).apply { action = "ACTION_STOP" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rover Remote")
            .setContentText("Listening for connection...")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("RoverService", "Failed to start foreground: ${e.message}")
        }

        if (intent?.action == "ACTION_STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CONNECT) {
            val url = intent.getStringExtra(EXTRA_URL)
            if (url != null) {
                stopDiscovery()
                connectWebSocket(url)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == "com.rover.remote.TOGGLE_MIC") {
            if (voiceRecognizerManager?.isListening == true) {
                voiceRecognizerManager?.stopListening()
            } else {
                voiceRecognizerManager?.startListening()
            }
            return START_NOT_STICKY
        }
        
        return START_NOT_STICKY
    }


    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Rover Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "Rover Messages",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(messageChannel)
        }
    }

    private val resolveQueue = java.util.concurrent.ConcurrentLinkedQueue<android.net.nsd.NsdServiceInfo>()
    private val isResolving = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun processResolveQueue() {
        if (!isResolving.compareAndSet(false, true)) return
        val service = resolveQueue.poll()
        if (service == null) {
            isResolving.set(false)
            return
        }
        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                    isResolving.set(false)
                    processResolveQueue()
                }
                override fun onServiceResolved(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    val hostName = serviceInfo.serviceName
                    val host: InetAddress = serviceInfo.host
                    val port: Int = serviceInfo.port
                    val ip = host.hostAddress
                    val url = "${Config.WS_SCHEME}$ip:$port"
                    
                    if (ip != null) {
                        var os = ""
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            val osBytes = serviceInfo.attributes["os"]
                            if (osBytes != null) {
                                os = String(osBytes, Charsets.UTF_8)
                            }
                        }
                        
                        val hostObj = RoverHost(name = hostName, localIp = ip, localPort = port, os = os)
                        val currentHosts = ConnectionRepository.state.value.discoveredHosts
                        if (currentHosts.none { it.name == hostObj.name }) {
                            ConnectionRepository.updateDiscoveredHosts(currentHosts + hostObj)
                        }
                    }

                    val currentStatus = ConnectionRepository.state.value.connectionStatus
                    if (currentStatus != "Connected" && currentStatus != "Connecting...") {
                        val lastHost = ConnectionRepository.getLastConnectedHost()
                        if (lastHost == hostName) {
                            ConnectionRepository.updateConnectedHostName(hostName)
                            ConnectionRepository.updateConnectionStatus("Connecting...")
                            connectWebSocket(url)
                        }
                    }
                    isResolving.set(false)
                    processResolveQueue()
                }
            })
        } catch (e: Exception) {
            isResolving.set(false)
            processResolveQueue()
        }
    }

    private fun startDiscovery() {
        stopDiscovery()
        
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager != null) {
            try {
                multicastLock = wifiManager.createMulticastLock("RoverMulticastLock")
                multicastLock?.setReferenceCounted(true)
                multicastLock?.acquire()
            } catch (e: Exception) {}
        }
        
        // 1. Start standard NsdManager discovery (fallback/legacy)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: android.net.nsd.NsdServiceInfo) {
                if (service.serviceType == Config.MDNS_SERVICE_TYPE || service.serviceType == Config.MDNS_SERVICE_TYPE_LOCAL) {
                    resolveQueue.add(service)
                    processResolveQueue()
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                val hostName = service.serviceName
                val currentHosts = ConnectionRepository.state.value.discoveredHosts
                val newHosts = currentHosts.filterNot { it.name == hostName }
                ConnectionRepository.updateDiscoveredHosts(newHosts)
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        
        try {
            nsdManager.discoverServices(Config.MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Start robust UDP Broadcast Request-Response discovery
        udpDiscoveryJob = scope.launch {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(Config.DISCOVERY_UDP_PORT))
                }
                
                // Broadcaster: Ask "is anyone there?" every 3 seconds while disconnected
                launch {
                    val message = Config.UDP_DISCOVERY_PAYLOAD.toByteArray()
                    
                    while (isActive) {
                        val status = ConnectionRepository.state.value.connectionStatus
                        if (status != "Connected" && status != "Connecting...") {
                            try {
                                val targetIps = getSubnetIps(this@RoverService)
                                for (addr in targetIps) {
                                    if (!isActive) break
                                    try {
                                        val packet = DatagramPacket(message, message.size, addr, Config.DISCOVERY_UDP_PORT)
                                        udpSocket?.send(packet)
                                    } catch (e: Exception) {}
                                }
                            } catch (e: Exception) {
                                // Ignore send errors
                            }
                        }
                        delay(Config.UDP_BROADCAST_INTERVAL_MS)
                    }
                }

                // Listener: Wait for unicast replies
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                var lastConnectAttempt = 0L
                while (isActive) {
                    udpSocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    try {
                        val json = JSONObject(data)
                        if (json.optString("app") == Config.APP_ID) {
                            val ip = packet.address.hostAddress
                            val port = json.getInt("port")
                            val hostname = json.optString("hostname", "RoverHost")
                            
                            // Emulate NsdManager successful resolution behavior
                            val currentHosts = ConnectionRepository.state.value.discoveredHosts
                            if (currentHosts.none { it.localIp == ip && it.localPort == port }) {
                                val newHost = RoverHost(
                                    name = hostname,
                                    localIp = ip!!,
                                    localPort = port,
                                    os = "Unknown"
                                )
                                ConnectionRepository.updateDiscoveredHosts(currentHosts + newHost)
                            }
                            
                            // Auto-connect if disconnected
                            val now = System.currentTimeMillis()
                            val lastHost = ConnectionRepository.getLastConnectedHost()
                            val status = ConnectionRepository.state.value.connectionStatus
                            if ((status == "Disconnected" || status.startsWith("Error")) && now - lastConnectAttempt > Config.AUTO_CONNECT_COOLDOWN_MS) {
                                if (lastHost == hostname) {
                                    lastConnectAttempt = now
                                    ConnectionRepository.updateConnectedHostName(hostname)
                                    ConnectionRepository.updateConnectionStatus("Connecting...")
                                    connectWebSocket("${Config.WS_SCHEME}$ip:$port")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Not our JSON, ignore
                    }
                }
            } catch (e: Exception) {
                // Socket closed or error
            }
        }
    }

    private fun stopDiscovery() {
        // Stop NsdManager
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        discoveryListener = null

        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            multicastLock = null
        } catch (e: Exception) {}

        // Stop UDP Discovery
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = null
        try {
            udpSocket?.close()
        } catch (e: Exception) {}
        udpSocket = null
        
        ConnectionRepository.clearDiscoveredHosts()
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
                        updateForegroundNotification("Connected to Rover")
                        
                        // Auto-reselect last project on reconnect
                        val savedProject = ConnectionRepository.state.value.currentProject
                        if (savedProject.isNotEmpty()) {
                            val payload = org.json.JSONObject().apply {
                                put("event", "select_project")
                                put("project", savedProject)
                            }
                            webSocketManager.send(payload.toString())
                        }
                        return
                    }
                } catch(e: Exception) {}
                
                scope.launch {
                        handleIncomingMessage(text, 
                            showMessageNotification = { title, msg ->
                                showMessageNotification(title, msg)
                            },
                            onAssistantChat = { msg ->
                                val mode = ConnectionRepository.state.value.interactionMode
                                when (mode) {
                                    InteractionMode.HANDS_FREE, InteractionMode.AUTO_READ -> {
                                        if (audioMediaManager?.requestAudioFocus() == true) {
                                            audioMediaManager?.showMediaNotification(msg)
                                            ttsManager?.speak(msg)
                                        }
                                    }
                                    InteractionMode.NOTIFY -> {
                                        // Show heads-up notification — user can tap to listen
                                        showMessageNotification("\uD83D\uDD0A Tap to listen", msg)
                                    }
                                    InteractionMode.SILENT -> {
                                        // No TTS, silent notification already handled above
                                    }
                                }
                            }
                        )
                }
            }
        }
        try {
            webSocketManager.connect(url, listener)
        } catch (t: Throwable) {
            ConnectionRepository.updateConnectionStatus("Disconnected")
            updateForegroundNotification("Connection Error")
        }
    }

    private fun updateForegroundNotification(text: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rover Remote")
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
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {}
        webSocketManager.disconnect()
        ttsManager?.release()
        audioMediaManager?.destroy()
        voiceRecognizerManager?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun getSubnetIps(context: Context): List<InetAddress> {
        val ips = mutableListOf<InetAddress>()
        try {
            // Always include standard global broadcast just in case it works
            try { ips.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) {}

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifiManager?.dhcpInfo ?: return ips
            
            if (dhcp.ipAddress == 0 || dhcp.netmask == 0) return ips

            val ipBE = Integer.reverseBytes(dhcp.ipAddress)
            val maskBE = Integer.reverseBytes(dhcp.netmask)
            
            val networkBE = ipBE and maskBE
            val broadcastBE = networkBE or maskBE.inv()
            
            // Add specific broadcast address
            try {
                val bcastBytes = ByteArray(4) { i -> ((broadcastBE shr (24 - i * 8)) and 0xFF).toByte() }
                ips.add(InetAddress.getByAddress(bcastBytes))
            } catch (e: Exception) {}

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ips
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
