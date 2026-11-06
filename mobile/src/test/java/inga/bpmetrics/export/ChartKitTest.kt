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

    // --- The scale for what's on screen (UX Audit O2) ---

    private fun points(vararg bpmEverySecond: Double) = bpmEverySecond.mapIndexed { i, bpm ->
        inga.bpmetrics.library.BpmDataPointEntity(recordOwnerId = 1, timestamp = i * 1_000L, bpm = bpm)
    }

    /** A quiet minute at about 100, then a climb to 170. */
    private val evening = points(*(DoubleArray(60) { 98.0 + it % 5 } + DoubleArray(60) { 110.0 + it }))

    @Test
    fun `the on-screen scale fits the window, not the whole recording`() {
        val whole = ChartKit.bpmRange(evening.map { it.bpm })
        val quiet = ChartKit.windowRange(listOf(evening), 30_000.0, 30_000L, whole)

        assertTrue("the quiet stretch should get a tighter scale", quiet.endInclusive - quiet.start < whole.endInclusive - whole.start)
        assertTrue(quiet.start <= 98.0)
        assertTrue(quiet.endInclusive >= 102.0)
    }

    @Test
    fun `the on-screen scale glides rather than jumping`() {
        val whole = ChartKit.bpmRange(evening.map { it.bpm })
        // One frame apart at 30 fps, across the climb that pushes the range up.
        var previous = ChartKit.windowRange(listOf(evening), 60_000.0, 30_000L, whole)
        var t = 60_000.0
        while (t < 90_000.0) {
            t += 33.0
            val next = ChartKit.windowRange(listOf(evening), t, 30_000L, whole)
            assertTrue("top jumped at $t", kotlin.math.abs(next.endInclusive - previous.endInclusive) < 3.0)
            assertTrue("bottom jumped at $t", kotlin.math.abs(next.start - previous.start) < 3.0)
            previous = next
        }
    }

    @Test
    fun `the same moment always gets the same scale`() {
        val whole = ChartKit.bpmRange(evening.map { it.bpm })
        assertEquals(
            ChartKit.windowRange(listOf(evening), 75_500.0, 30_000L, whole),
            ChartKit.windowRange(listOf(evening), 75_500.0, 30_000L, whole)
        )
    }

    @Test
    fun `a window with no readings near it keeps the whole recording's scale`() {
        val whole = ChartKit.bpmRange(evening.map { it.bpm })
        assertEquals(whole, ChartKit.windowRange(listOf(evening), 10_000_000.0, 30_000L, whole))
    }

    @Test
    fun `text is never smaller than 2 percent of the short side`() {
        assertEquals(21.6f, ChartKit.readableText(1920f, 1080f), 0.01f)
        assertEquals(21.6f, ChartKit.readableText(1080f, 1920f), 0.01f)
    }

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
