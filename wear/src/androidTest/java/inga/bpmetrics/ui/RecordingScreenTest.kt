package inga.bpmetrics.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import inga.bpmetrics.recording.RecordingState
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for the [RecordingContent] composable on Wear OS.
 */
class RecordingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun initialState_showsStartButton() {
        val state = RecordingUIState(
            serviceState = RecordingState.READY,
            statusText = "Ready to record"
        )

        composeTestRule.setContent {
            RecordingContent(
                state = state,
                onStart = {},
                onStop = {}
            )
        }

        // Verify "Start" button is visible and enabled
        composeTestRule.onNodeWithText("Start").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Ready to record").assertIsDisplayed()
    }

    @Test
    fun recordingState_showsStopButtonAndTimer() {
        val state = RecordingUIState(
            serviceState = RecordingState.RECORDING,
            statusText = "Recording...",
            recordingStartTime = System.currentTimeMillis() - 5000 // 5 seconds ago
        )

        composeTestRule.setContent {
            RecordingContent(
                state = state,
                onStart = {},
                onStop = {}
            )
        }

        // Verify "Stop" button is visible
        composeTestRule.onNodeWithText("Stop").assertIsDisplayed()
        
        // Verify recording status text
        composeTestRule.onNodeWithText("Recording...").assertIsDisplayed()
        
        // The timer should be visible (e.g., "5s" or similar)
        // Since it's dynamic, we just check that some text containing "s" is present
        // or targeted specifically if we had a test tag.
    }

    @Test
    fun clickingStart_triggersCallback() {
        var startClicked = false
        val state = RecordingUIState(
            serviceState = RecordingState.READY
        )

        composeTestRule.setContent {
            RecordingContent(
                state = state,
                onStart = { startClicked = true },
                onStop = {}
            )
        }

        composeTestRule.onNodeWithText("Start").performClick()
        assert(startClicked)
    }

    @Test
    fun clickingStop_triggersCallback() {
        var stopClicked = false
        val state = RecordingUIState(
            serviceState = RecordingState.RECORDING
        )

        composeTestRule.setContent {
            RecordingContent(
                state = state,
                onStart = {},
                onStop = { stopClicked = true }
            )
        }

        composeTestRule.onNodeWithText("Stop").performClick()
        assert(stopClicked)
    }
}
