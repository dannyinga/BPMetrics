package inga.bpmetrics.ui.record

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.TagEntity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for [BpmRecordTile], the record summary shown in the library list.
 *
 * Replaces an older test written against a record screen that has since been rewritten; it
 * asserted on text the UI no longer renders and had not compiled for some time. The tile is
 * tested instead because it is stateless, which the screen — driven by a ViewModel — is not.
 */
class BpmRecordTileTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun record(
        title: String = "Morning Run",
        deviceId: String = "Pixel Watch 2",
        wearerName: String = "",
        tags: List<TagEntity> = emptyList()
    ): BpmRecord {
        val start = 1_700_000_000_000L
        val metadata = BpmRecordEntity(
            recordId = 1L,
            title = title,
            date = start,
            startTime = start,
            endTime = start + 10_000L,
            durationMs = 10_000L,
            avg = 75.0,
            deviceId = deviceId,
            wearerName = wearerName
        )
        val min = BpmDataPointEntity(1, 1, 1000, 60.0)
        val max = BpmDataPointEntity(2, 1, 5000, 90.0)

        return BpmRecord(
            metadata = metadata,
            dataPoints = listOf(min, max),
            minDataPoint = min,
            maxDataPoint = max,
            tags = tags
        )
    }

    @Test
    fun showsTheRecordTitle() {
        composeTestRule.setContent {
            BpmRecordTile(record = record(title = "Concert Night"), onClick = {})
        }

        composeTestRule.onNodeWithText("Concert Night").assertIsDisplayed()
    }

    @Test
    fun showsTheWatchWhenNoWearerIsNamed() {
        composeTestRule.setContent {
            BpmRecordTile(record = record(deviceId = "Galaxy Watch5"), onClick = {})
        }

        composeTestRule.onNodeWithText("⌚ Galaxy Watch5").assertIsDisplayed()
    }

    @Test
    fun showsTheWearerAlongsideTheWatchWhenOneWasStamped() {
        // The wearer is frozen onto the record at ingest, so this is what was true at the time
        // of recording rather than whatever the watch is called now.
        composeTestRule.setContent {
            BpmRecordTile(
                record = record(deviceId = "Pixel Watch 2", wearerName = "Kyle"),
                onClick = {}
            )
        }

        composeTestRule.onNodeWithText("👤 Kyle  •  ⌚ Pixel Watch 2").assertIsDisplayed()
    }

    @Test
    fun fallsBackToAGenericWatchLabelWhenTheDeviceIsBlank() {
        composeTestRule.setContent {
            BpmRecordTile(record = record(deviceId = ""), onClick = {})
        }

        composeTestRule.onNodeWithText("⌚ Watch").assertIsDisplayed()
    }

    @Test
    fun showsTags() {
        composeTestRule.setContent {
            BpmRecordTile(
                record = record(tags = listOf(TagEntity(tagId = 1, name = "Coachella", parentCategoryId = 1))),
                onClick = {}
            )
        }

        composeTestRule.onNodeWithText("Coachella").assertIsDisplayed()
    }

    @Test
    fun clickingTheTileReportsIt() {
        var clicked = false

        composeTestRule.setContent {
            BpmRecordTile(record = record(title = "Tap Me"), onClick = { clicked = true })
        }

        composeTestRule.onNodeWithText("Tap Me").performClick()
        assertTrue(clicked)
    }
}
