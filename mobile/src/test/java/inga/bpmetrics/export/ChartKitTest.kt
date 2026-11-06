package inga.bpmetrics.export

import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.PersonColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a video frame and a picture have to agree on: the scale, the colours, the names, and the
 * names of the files they're saved as.
 */
class ChartKitTest {

    private fun recording(wearer: String = "", device: String = "", title: String = "Untitled 1") =
        BpmRecordWithPoints(
            metadata = BpmRecordEntity(
                recordId = 1, title = title, date = 0L, startTime = 0L, endTime = 1_000L,
                durationMs = 1_000L, wearerName = wearer, deviceId = device
            ),
            dataPoints = emptyList(),
            minDataPoint = null,
            maxDataPoint = null
        )

    @Test
    fun `the scale holds every reading, with room above and below`() {
        val range = ChartKit.bpmRange(listOf(72.0, 151.0))
        assertTrue(range.start < 72.0)
        assertTrue(range.endInclusive > 151.0)
    }

    @Test
    fun `the scale's edges are gridlines`() {
        val range = ChartKit.bpmRange(listOf(64.0, 133.0))
        val lines = ChartKit.gridLines(range)
        assertEquals(range.start, lines.first(), 0.001)
        assertEquals(range.endInclusive, lines.last(), 0.001)
    }

    @Test
    fun `a flat recording still gets a plot at least twenty tall`() {
        val range = ChartKit.bpmRange(listOf(80.0, 80.0, 81.0))
        assertTrue(range.endInclusive - range.start >= 20.0)
        assertTrue(80.0 in range && 81.0 in range)
    }

    @Test
    fun `padding stops at what a heart does, then widens only to the next gridline`() {
        // Padded 40–210 would be 26–224; clamped it's 30–220, and on a 50 step that's 0–250.
        val range = ChartKit.bpmRange(listOf(40.0, 210.0))
        assertEquals(0.0..250.0, range)
        // Narrower readings near the limits don't get padded past them.
        val tight = ChartKit.bpmRange(listOf(32.0, 70.0))
        assertTrue(tight.start >= 30.0 - ChartKit.gridLines(tight).zipWithNext { a, b -> b - a }.first())
    }

    @Test
    fun `no readings gets a resting-to-running scale`() {
        assertEquals(60.0..180.0, ChartKit.bpmRange(emptyList()))
    }

    @Test
    fun `a wearer's colour wins, the palette fills in by position`() {
        assertEquals(0x123456, ChartKit.colourFor(7, 0, mapOf(7L to 0x123456)))
        assertEquals(PersonColors.defaultFor(2), ChartKit.colourFor(8, 2, mapOf(7L to 0x123456)))
    }

    @Test
    fun `a curve is named for the wearer, else the watch, else a real title`() {
        assertEquals("Danny", ChartKit.wearerLabel(recording(wearer = "Danny", device = "Blue")))
        assertEquals("Blue", ChartKit.wearerLabel(recording(device = "Blue", title = "Boogie T")))
        assertEquals("Boogie T", ChartKit.wearerLabel(recording(title = "Boogie T")))
        assertEquals("Unknown", ChartKit.wearerLabel(recording(title = "Untitled 4")))
    }

    @Test
    fun `file names keep letters from any language`() {
        assertEquals("Café_Tacvba", FileNames.of("Café Tacvba"))
    }

    @Test
    fun `file names lose anything a file system or a share sheet would trip on`() {
        assertEquals("Mr_Amsterdam_2_3", FileNames.of("Mr. Amsterdam: 2/3?"))
        assertEquals("a-b_c", FileNames.of("  a-b_c  "))
    }

    @Test
    fun `a title with nothing usable still makes a name`() {
        assertEquals("BPMetrics", FileNames.of("???"))
        assertEquals("BPMetrics", FileNames.of(""))
    }

    @Test
    fun `long titles are cut`() {
        assertTrue(FileNames.of("x".repeat(500)).length <= 80)
    }
}
