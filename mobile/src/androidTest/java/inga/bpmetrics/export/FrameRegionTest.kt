package inga.bpmetrics.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pointing at part of the preview finds that part (S14B.2), from the same layout it's drawn with.
 *
 * On a device because the layout measures real text: where the pills go depends on how wide the
 * names are in the device's font.
 */
@RunWith(AndroidJUnit4::class)
class FrameRegionTest {

    private val startMs = 1_750_000_000_000L

    private fun person(id: Long, name: String) = BpmRecordWithPoints(
        metadata = BpmRecordEntity(
            recordId = id, title = "Untitled $id", date = startMs, startTime = startMs,
            endTime = startMs + 10 * 60_000L, durationMs = 10 * 60_000L, wearerName = name
        ),
        dataPoints = (0 until 120).map { BpmDataPointEntity(recordOwnerId = id, timestamp = it * 5_000L, bpm = 90.0 + id * 10) },
        minDataPoint = null,
        maxDataPoint = null
    )

    private val cast = listOf(person(1, "Danny"), person(2, "Bethany"), person(3, "Stina"))
    private val aligned = VideoFrameRenderer.alignRecords(cast)
    private val content = ExportContent(records = cast, startMs = 0L, endMs = 1L, title = "Boogie T @ The Granada")
    private val look = ExportPreset(width = 1920, height = 1080)
    private val panel = look.frameFor(cast.size).toRectF()

    private fun at(fx: Float, fy: Float): VideoFrameRenderer.Region? = VideoFrameRenderer.regionAt(
        1920, 1080, aligned, content, look, panel,
        1920 * (panel.left + (panel.right - panel.left) * fx),
        1080 * (panel.top + (panel.bottom - panel.top) * fy)
    )

    @Test
    fun theTitleIsTheMiddleOfTheHeader() {
        assertEquals(VideoFrameRenderer.Region.TITLE, at(0.5f, 0.04f))
    }

    @Test
    fun theClockIsTheRightOfTheHeader() {
        assertEquals(VideoFrameRenderer.Region.CLOCK, at(0.95f, 0.04f))
    }

    @Test
    fun thePillsAreTopRightOfThePlot() {
        // The readouts start just inside the plot's top right.
        assertEquals(VideoFrameRenderer.Region.READOUTS, at(0.93f, 0.4f))
    }

    @Test
    fun theCurvesAreThePlot() {
        assertEquals(VideoFrameRenderer.Region.PLOT, at(0.3f, 0.6f))
    }

    @Test
    fun outsideThePanelIsNothing() {
        assertNull(VideoFrameRenderer.regionAt(1920, 1080, aligned, content, look, panel, 10f, 10f))
    }

    @Test
    fun pillsMovedOffTheGraphAreFoundWhereTheyWerePut() {
        // Pinned to the top left of the frame, well outside the panel.
        val spot = ReadoutSpot(x = 0.03f, y = 0.05f, pin = ReadoutPin.TOP_LEFT)
        assertEquals(
            VideoFrameRenderer.Region.READOUTS,
            VideoFrameRenderer.regionAt(1920, 1080, aligned, content, look, panel, 1920 * 0.05f, 1080 * 0.08f, readouts = spot)
        )
        // And where they were on the graph is the graph again.
        assertEquals(
            VideoFrameRenderer.Region.PLOT,
            VideoFrameRenderer.regionAt(
                1920, 1080, aligned, content, look, panel,
                1920 * (panel.left + (panel.right - panel.left) * 0.93f),
                1080 * (panel.top + (panel.bottom - panel.top) * 0.4f),
                readouts = spot
            )
        )
    }
}
