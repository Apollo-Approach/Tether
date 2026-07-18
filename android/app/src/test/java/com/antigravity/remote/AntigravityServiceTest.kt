package com.antigravity.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AntigravityServiceTest {

    @Before
    fun setup() {
        // Reset ConnectionRepository state
        ConnectionRepository.updateConnectionStatus("Disconnected")
        ConnectionRepository.setArtifact(null)
        ConnectionRepository.setThinking(false)
        ConnectionRepository.setAvailableConversations(emptyList())
        ConnectionRepository.setProjects(emptyList())
        ConnectionRepository.setCurrentProject("")
        ConnectionRepository.setActiveConversation("", "")
        // Clear chat messages (rely on state flow emission, we can't easily clear the list directly without a helper, 
        // so we'll just check the last message or size increase)
        // Wait, the repository adds to the list, we can't clear it. We'll check the last item.
    }

    @Test
    fun testParseChatAssistantMessage() = runTest {
        ConnectionRepository.setThinking(true)
        val json = """{"type":"chat", "role":"assistant", "message":"Hello World"}"""
        
        var notifiedTitle = ""
        var notifiedMsg = ""
        
        AntigravityService.handleIncomingMessage(json) { title, msg ->
            notifiedTitle = title
            notifiedMsg = msg
        }
        
        val state = ConnectionRepository.state.first()
        val lastMsg = state.chatMessages.last()
        assertEquals("assistant", lastMsg.role)
        assertEquals("Hello World", lastMsg.message)
        assertFalse(state.isThinking)
        assertEquals("New Message", notifiedTitle)
        assertEquals("Hello World", notifiedMsg)
    }

    @Test
    fun testParseArtifactMessage() = runTest {
        val json = """{"type":"artifact", "title":"Plan", "content":"Step 1"}"""
        
        var notifiedTitle = ""
        
        AntigravityService.handleIncomingMessage(json) { title, _ ->
            notifiedTitle = title
        }
        
        val state = ConnectionRepository.state.first()
        val artifact = state.currentArtifact
        assertNotNull(artifact)
        assertEquals("Plan", artifact?.title)
        assertEquals("Step 1", artifact?.content)
        assertEquals("Artifact Updated", notifiedTitle)
    }

    @Test
    fun testParseHandshake() = runTest {
        val json = """
            {"type":"handshake", "data": {
                "projects": ["Alpha", "Beta"],
                "current_project": "Alpha",
                "activeConversation": "conv-123"
            }}
        """.trimIndent()
        
        AntigravityService.handleIncomingMessage(json) { _, _ -> }
        
        val state = ConnectionRepository.state.first()
        assertEquals(listOf("Alpha", "Beta"), state.allProjects)
        assertEquals("Alpha", state.currentProject)
        assertEquals("conv-123", state.activeConversationId)
    }

    @Test
    fun testParseProjectSelected() = runTest {
        val json = """{"type":"project_selected", "project":"Gamma", "conversationId":"c456", "firstMessage":"Hi"}"""
        
        AntigravityService.handleIncomingMessage(json) { _, _ -> }
        
        val state = ConnectionRepository.state.first()
        assertEquals("Gamma", state.currentProject)
        assertEquals("c456", state.activeConversationId)
        assertEquals("Hi", state.activeConversationPreview)
    }
}
