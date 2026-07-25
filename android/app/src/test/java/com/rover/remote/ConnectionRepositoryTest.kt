package com.rover.remote

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConnectionRepositoryTest {

    @Before
    fun setup() {
        // Reset state before each test
        ConnectionRepository.updateConnectionStatus("Disconnected")
        ConnectionRepository.setProjects(emptyList())
        ConnectionRepository.setCurrentProject("")
        ConnectionRepository.setThinking(false)
    }

    @Test
    fun testUpdateConnectionStatus() = runTest {
        ConnectionRepository.updateConnectionStatus("Connected")
        val state = ConnectionRepository.state.first()
        assertEquals("Connected", state.connectionStatus)
    }

    @Test
    fun testSetThinking() = runTest {
        ConnectionRepository.setThinking(true)
        val state = ConnectionRepository.state.first()
        assertTrue(state.isThinking)
        assertNotNull(state.thinkingStartTime)
        assertEquals("", state.currentThoughts)

        ConnectionRepository.setThinking(false)
        val newState = ConnectionRepository.state.first()
        assertFalse(newState.isThinking)
        assertNull(newState.thinkingStartTime)
        assertEquals("", newState.currentThoughts)
    }

    @Test
    fun testAppendThought() = runTest {
        ConnectionRepository.setThinking(true)
        ConnectionRepository.appendThought("First thought")
        var state = ConnectionRepository.state.first()
        assertEquals("First thought", state.currentThoughts)

        ConnectionRepository.appendThought("Second thought")
        state = ConnectionRepository.state.first()
        assertEquals("First thought\n\nSecond thought", state.currentThoughts)
    }

    @Test
    fun testAddChatMessage() = runTest {
        val msg = ChatMessage("user", "Hello")
        ConnectionRepository.addChatMessage(msg)
        val state = ConnectionRepository.state.first()
        assertTrue(state.chatMessages.contains(msg))
    }

    @Test
    fun testProjectState() = runTest {
        ConnectionRepository.setProjects(listOf("ProjA", "ProjB"))
        ConnectionRepository.setCurrentProject("ProjA")
        val state = ConnectionRepository.state.first()
        assertEquals(listOf("ProjA", "ProjB"), state.allProjects)
        assertEquals("ProjA", state.currentProject)
    }
}
