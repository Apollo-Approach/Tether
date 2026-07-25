package com.rover.remote

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val permissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun setup() {
        // Reset state
        ConnectionRepository.updateConnectionStatus("Disconnected")
        ConnectionRepository.setArtifact(null)
    }

    @Test
    fun testInitialUiState() {
        // The TopAppBar should show "Rover"
        composeTestRule.onNodeWithText("Rover").assertIsDisplayed()

        // Open the drawer
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.waitForIdle()

        // Inside the drawer, the button should say "Connect"
        composeTestRule.onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test
    fun testConnectedStateChangesButtonText() {
        // Simulate a connection
        composeTestRule.runOnUiThread {
            ConnectionRepository.updateConnectionStatus("Connected")
        }
        
        // Let Compose idle and update
        composeTestRule.waitForIdle()

        // Open the drawer
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.waitForIdle()

        // Button should change to Disconnect
        composeTestRule.onNodeWithText("Disconnect").assertIsDisplayed()
    }
}
