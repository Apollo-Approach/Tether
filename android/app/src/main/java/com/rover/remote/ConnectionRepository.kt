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

data class AppState(
    val connectionStatus: String = "Disconnected",
    val chatMessages: List<ChatMessage> = emptyList(),
    val currentArtifact: ArtifactMessage? = null,
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
    val interactionMode: InteractionMode = InteractionMode.SILENT
)

object ConnectionRepository {
    var webSocketManager: WebSocketManager? = null
    var ttsManager: TTSManager? = null
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun updateConnectionStatus(status: String) {
        _state.update { it.copy(connectionStatus = status) }
    }

    fun updateCurrentModel(model: String) {
        _state.update { it.copy(currentModel = model) }
        webSocketManager?.send("""{"event":"set_model","model":"$model"}""")
    }

    fun updateDiscoveredHosts(hosts: List<RoverHost>) {
        _state.update { it.copy(discoveredHosts = hosts) }
    }

    fun addChatMessage(message: ChatMessage) {
        _state.update { state -> 
            state.copy(chatMessages = state.chatMessages + message)
        }
    }

    fun setArtifact(artifact: ArtifactMessage?) {
        _state.update { it.copy(currentArtifact = artifact) }
    }

    fun setApprovalRequest(request: ApprovalRequest?) {
        _state.update { it.copy(currentApprovalRequest = request) }
    }

    fun setProjects(projects: List<String>) {
        _state.update { it.copy(allProjects = projects) }
    }

    fun setCurrentProject(project: String) {
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

    fun setActiveConversation(conversationId: String, preview: String) {
        _state.update { it.copy(
            activeConversationId = conversationId,
            activeConversationPreview = preview
        ) }
    }

    fun setAvailableConversations(conversations: List<ConversationInfo>) {
        _state.update { it.copy(availableConversations = conversations) }
    }

    fun updateInteractionMode(mode: InteractionMode) {
        _state.update { it.copy(interactionMode = mode) }
    }
}
