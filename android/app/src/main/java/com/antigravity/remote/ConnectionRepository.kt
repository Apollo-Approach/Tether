package com.antigravity.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppState(
    val connectionStatus: String = "Disconnected",
    val chatMessages: List<ChatMessage> = emptyList(),
    val currentArtifact: ArtifactMessage? = null,
    val currentApprovalRequest: ApprovalRequest? = null,
    val allProjects: List<String> = emptyList(),
    val currentProject: String = "",
    val isThinking: Boolean = false,
    val discoveredHosts: List<AntigravityHost> = emptyList()
)

object ConnectionRepository {
    var webSocketManager: WebSocketManager? = null
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun updateConnectionStatus(status: String) {
        _state.value = _state.value.copy(connectionStatus = status)
    }

    fun updateDiscoveredHosts(hosts: List<AntigravityHost>) {
        _state.value = _state.value.copy(discoveredHosts = hosts)
    }

    fun addChatMessage(message: ChatMessage) {
        val updatedList = _state.value.chatMessages.toMutableList().apply { add(message) }
        _state.value = _state.value.copy(chatMessages = updatedList)
    }

    fun setArtifact(artifact: ArtifactMessage?) {
        _state.value = _state.value.copy(currentArtifact = artifact)
    }

    fun setApprovalRequest(request: ApprovalRequest?) {
        _state.value = _state.value.copy(currentApprovalRequest = request)
    }

    fun setProjects(projects: List<String>) {
        _state.value = _state.value.copy(allProjects = projects)
    }

    fun setCurrentProject(project: String) {
        _state.value = _state.value.copy(currentProject = project)
    }

    fun setThinking(thinking: Boolean) {
        _state.value = _state.value.copy(isThinking = thinking)
    }
}
