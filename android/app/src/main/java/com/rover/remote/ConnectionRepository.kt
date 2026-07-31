package com.rover.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConversationInfo(
    val id: String,
    val parentId: String = "",
    val lastActive: String,
    val firstMessage: String
)

enum class InteractionMode(val label: String, val description: String) {
    HANDS_FREE("Hands-Free", "Auto TTS + auto mic after response"),
    AUTO_READ("Auto-Read", "Auto TTS, manual mic/type to reply"),
    NOTIFY("Notify", "Notification ping, tap to hear response"),
    SILENT("Silent", "Text only, no audio")
}

data class RunningTask(
    val id: String,
    val name: String
)

data class RoverHost(
    val name: String,
    val localIp: String? = null,
    val localPort: Int? = null,
    val tailscaleIp: String? = null,
    val os: String = ""
)

data class TrustedNetwork(
    val ssid: String,
    val lat: Double,
    val lng: Double
)

data class AppState(
    val connectionStatus: String = "Disconnected",
    val connectedHostName: String? = null,
    val chatMessages: List<ChatMessage> = emptyList(),
    val currentArtifact: ArtifactMessage? = null,
    val artifactHistory: Map<String, ArtifactMessage> = emptyMap(),
    val currentApprovalRequest: ApprovalRequest? = null,
    val allProjects: List<String> = emptyList(),
    val currentProject: String = "",
    val isThinking: Boolean = false,
    val discoveredHosts: List<RoverHost> = emptyList(),
    val activeConversationId: String = "",
    val activeConversationPreview: String = "",
    val availableConversations: List<ConversationInfo> = emptyList(),
    val thinkingStartTime: Long? = null,
    val currentThoughts: String = "",
    val currentModel: String = "Gemini 3.5 Flash (High)",
    val interactionMode: InteractionMode = InteractionMode.SILENT,
    val isTurboMode: Boolean = false,
    val queuedMessages: List<String> = emptyList(),
    val runningTasks: List<RunningTask> = emptyList(),
    val isMicListening: Boolean = false,
    val trustedNetworks: List<TrustedNetwork> = emptyList()
)

object ConnectionRepository {
    var webSocketManager: WebSocketManager? = null
    var ttsManager: TTSManager? = null
    var networkManager: NetworkManager? = null
    var appContext: android.content.Context? = null
    
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        if (networkManager == null) {
            networkManager = NetworkManager(context)
            
            val prefs = context.getSharedPreferences("ConnectionPrefs", android.content.Context.MODE_PRIVATE)
            lastHost = prefs.getString("lastConnectedHost", null)
            
            // Load trusted networks
            val savedNetworks = prefs.getString("trustedNetworks", "[]")
            try {
                val jsonArray = org.json.JSONArray(savedNetworks)
                val networks = mutableListOf<TrustedNetwork>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    networks.add(TrustedNetwork(
                        obj.getString("ssid"),
                        obj.getDouble("lat"),
                        obj.getDouble("lng")
                    ))
                }
                _state.update { it.copy(trustedNetworks = networks) }
            } catch (e: Exception) {}
        }
    }
    
    private fun saveTrustedNetworks(networks: List<TrustedNetwork>) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("ConnectionPrefs", android.content.Context.MODE_PRIVATE)
            val jsonArray = org.json.JSONArray()
            networks.forEach { net ->
                val obj = org.json.JSONObject()
                obj.put("ssid", net.ssid)
                obj.put("lat", net.lat)
                obj.put("lng", net.lng)
                jsonArray.put(obj)
            }
            prefs.edit().putString("trustedNetworks", jsonArray.toString()).apply()
        }
    }
    
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    
    private var lastHost: String? = null

    fun updateConnectionStatus(status: String) {
        _state.update { it.copy(connectionStatus = status) }
    }

    fun updateConnectedHostName(name: String?) {
        _state.update { it.copy(connectedHostName = name) }
        if (name != null) {
            lastHost = name
            appContext?.let { ctx ->
                val prefs = ctx.getSharedPreferences("ConnectionPrefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putString("lastConnectedHost", name).apply()
            }
        }
    }

    fun getLastConnectedHost(): String? {
        return lastHost ?: _state.value.connectedHostName
    }

    fun setMicListening(isListening: Boolean) {
        _state.update { it.copy(isMicListening = isListening) }
    }

    fun addTrustedNetwork(net: TrustedNetwork) {
        _state.update { 
            val newNetworks = it.trustedNetworks + net
            saveTrustedNetworks(newNetworks)
            it.copy(trustedNetworks = newNetworks) 
        }
    }

    fun removeTrustedNetwork(ssid: String) {
        _state.update { state -> 
            val newNetworks = state.trustedNetworks.filter { it.ssid != ssid }
            saveTrustedNetworks(newNetworks)
            state.copy(trustedNetworks = newNetworks)
        }
    }

    fun updateTurboMode(turbo: Boolean) {
        _state.update { it.copy(isTurboMode = turbo) }
        webSocketManager?.send("""{"event":"update_settings","turbo":$turbo}""")
    }

    fun updateCurrentModel(model: String) {
        _state.update { it.copy(currentModel = model) }
        webSocketManager?.send("""{"event":"set_model","model":"$model"}""")
    }

    fun updateDiscoveredHosts(hosts: List<RoverHost>) {
        _state.update { it.copy(discoveredHosts = hosts) }
    }

    fun clearDiscoveredHosts() {
        _state.update { it.copy(discoveredHosts = emptyList()) }
    }

    fun addChatMessage(message: ChatMessage) {
        _state.update { state -> 
            state.copy(chatMessages = state.chatMessages + message)
        }
    }

    fun setArtifact(artifact: ArtifactMessage?) {
        _state.update { state -> 
            val updatedHistory = if (artifact != null) {
                state.artifactHistory + (artifact.title to artifact)
            } else {
                state.artifactHistory
            }
            state.copy(currentArtifact = artifact, artifactHistory = updatedHistory)
        }
    }

    fun setApprovalRequest(request: ApprovalRequest?) {
        _state.update { it.copy(currentApprovalRequest = request) }
    }

    fun setProjects(projects: List<String>) {
        _state.update { it.copy(allProjects = projects) }
    }

    fun setCurrentProject(project: String) {
        if (_state.value.currentProject == project) return
        
        _state.update { it.copy(
            currentProject = project,
            chatMessages = emptyList(),
            currentArtifact = null,
            isThinking = false,
            thinkingStartTime = null,
            currentThoughts = ""
        ) }
    }

    fun setThinking(thinking: Boolean) {
        _state.update { state ->
            if (thinking) {
                state.copy(
                    isThinking = true,
                    thinkingStartTime = System.currentTimeMillis(),
                    currentThoughts = ""
                )
            } else {
                state.copy(
                    isThinking = false,
                    thinkingStartTime = null,
                    currentThoughts = ""
                )
            }
        }
    }

    fun appendThought(thought: String) {
        _state.update { state ->
            val newThoughts = state.currentThoughts + if (state.currentThoughts.isNotEmpty()) "\n\n$thought" else thought
            state.copy(currentThoughts = newThoughts)
        }
    }

    fun setActiveConversation(conversationId: String, preview: String): Boolean {
        if (_state.value.activeConversationId == conversationId) return false
        _state.update { it.copy(
            activeConversationId = conversationId,
            activeConversationPreview = preview,
            chatMessages = emptyList()
        ) }
        return true
    }

    fun setAvailableConversations(conversations: List<ConversationInfo>) {
        _state.update { it.copy(availableConversations = conversations) }
    }

    fun updateInteractionMode(mode: InteractionMode) {
        _state.update { it.copy(interactionMode = mode) }
    }

    fun setQueuedMessages(messages: List<String>) {
        _state.update { it.copy(queuedMessages = messages) }
    }

    fun setRunningTasks(tasks: List<RunningTask>) {
        _state.update { it.copy(runningTasks = tasks) }
    }

}
