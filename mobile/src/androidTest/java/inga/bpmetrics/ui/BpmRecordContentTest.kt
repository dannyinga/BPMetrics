package inga.bpmetrics.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Rule
import org.junit.Test
import java.util.Date

/**
 * UI tests for the [BpmRecordContent] composable.
 */
class BpmRecordContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Helper to create a mock BpmRecord
    private fun createMockRecord(title: String): BpmRecord {
        val metadata = BpmRecordEntity(
            recordId = 1L,
            title = title,
            date = Date().time,
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 10000,
            durationMs = 10000,
            avg = 75.0
        )
        val minPoint = BpmDataPointEntity(1, 1, 1000, 60.0)
        val maxPoint = BpmDataPointEntity(2, 1, 5000, 90.0)
        
        return BpmRecord(
            metadata = metadata,
            dataPoints = listOf(minPoint, maxPoint),
            minDataPoint = minPoint,
            maxDataPoint = maxPoint
        )
    }

    @Test
    fun recordDetails_areDisplayed() {
        val record = createMockRecord("Morning Run")

        composeTestRule.setContent {
            BpmRecordContent(
                record = record,
                onBack = {},
                onRefresh = {},
                onDelete = {},
                onUpdateTitle = {}
            )
        }

        // Verify title and statistics are displayed
        composeTestRule.onNodeWithText("Morning Run").assertIsDisplayed()
        composeTestRule.onNodeWithText("Avg BPM: 75.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Max BPM: (1s 0ms, 90.0 BPM)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Min BPM: (1s 0ms, 60.0 BPM)").assertIsDisplayed()
    }

    @Test
    fun clickingEdit_showsTextField() {
        val record = createMockRecord("Test Title")

        composeTestRule.setContent {
            BpmRecordContent(
                record = record,
                onBack = {},
                onRefresh = {},
                onDelete = {},
                onUpdateTitle = {}
            )
        }

        // Click the edit button
        composeTestRule.onNodeWithContentDescription("Edit Title").performClick()

        // Verify the TextField is displayed with the title
        composeTestRule.onNodeWithText("Record Title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Title").assertIsDisplayed()
    }

    @Test
    fun deleteButton_isClickable() {
        var deleteClicked = false
        val record = createMockRecord("Delete Me")

        composeTestRule.setContent {
            BpmRecordContent(
                record = record,
                onBack = {},
                onRefresh = {},
                onDelete = { deleteClicked = true },
                onUpdateTitle = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Delete Record").performClick()
        assert(deleteClicked)
    }
}
