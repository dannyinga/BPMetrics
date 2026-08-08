package inga.bpmetrics.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the axes say.
 *
 * The vertical axis used to divide the data range into six and print whatever fell out — 154, 167,
 * 180. Those are accurate and unusable: an axis is read by *landing on* a value, and nobody lands
 * on 167. The horizontal axis did not exist, so nothing on the frame said how far ahead the graph
 * could see, even though the window is a preset and differs between them.
 *
 * All of it is arithmetic, so all of it is checkable here rather than by exporting a video.
 */
class GraphAxesTest {

    // --- Heart rate ---

    @Test
    fun `gridlines land on numbers a person counts in`() {
        // A typical range for a set: nothing here should print a 7 or a 13.
        val lines = GraphAxes.bpmGridLines(60.0..180.0)

        assertTrue(lines.isNotEmpty())
        lines.forEach { value ->
            assertEquals(
                "$value is not a round step",
                0.0,
                value % 5.0,
                0.001
            )
        }
    }

    @Test
    fun `a narrow range gets a fine step and a wide one a coarse step`() {
        // Someone sitting still, then someone at a show.
        assertTrue(GraphAxes.bpmStep(20.0) <= 10.0)
        assertTrue(GraphAxes.bpmStep(160.0) >= 25.0)
    }

    @Test
    fun `the number of gridlines stays readable at any range`() {
        listOf(10.0, 25.0, 60.0, 120.0, 200.0, 400.0).forEach { span ->
            val range = 50.0..(50.0 + span)
            val lines = GraphAxes.bpmGridLines(range)
            assertTrue(
                "span $span produced ${lines.size} lines",
                lines.size in 2..12
            )
        }
    }

    @Test
    fun `the range widens to whole steps so the edges are gridlines`() {
        val snapped = GraphAxes.snapBpmRange(63.0, 178.0)

        val step = GraphAxes.bpmStep(snapped.endInclusive - snapped.start)
        assertEquals(0.0, snapped.start % step, 0.001)
        assertEquals(0.0, snapped.endInclusive % step, 0.001)
    }

    @Test
    fun `snapping never crops a reading out of view`() {
        // Outward only. Cropping to a tidy number would put a peak off the top of the plot, and a
        // graph that hides the highest reading is worse than one with an untidy edge.
        listOf(63.0 to 178.0, 51.0 to 52.0, 99.0 to 101.0, 120.5 to 187.4).forEach { (min, max) ->
            val snapped = GraphAxes.snapBpmRange(min, max)
            assertTrue("$min fell outside", snapped.start <= min + 0.001)
            assertTrue("$max fell outside", snapped.endInclusive >= max - 0.001)
        }
    }

    @Test
    fun `a flat recording still gets a plot to draw in`() {
        // Every reading identical — a watch that never moved. A zero-height range would divide by
        // nothing and draw a line through the middle of nowhere.
        val snapped = GraphAxes.snapBpmRange(72.0, 72.0)

        assertTrue(snapped.endInclusive > snapped.start)
        assertTrue(GraphAxes.bpmGridLines(snapped).isNotEmpty())
    }

    @Test
    fun `gridlines stay inside the range they were asked for`() {
        val range = 55.0..185.0
        GraphAxes.bpmGridLines(range).forEach {
            assertTrue("$it is outside $range", it >= range.start - 0.001)
            assertTrue("$it is outside $range", it <= range.endInclusive + 0.001)
        }
    }

    @Test
    fun `labels are whole numbers`() {
        assertEquals("154", GraphAxes.bpmLabel(153.7))
        assertEquals("60", GraphAxes.bpmLabel(60.0))
    }

    // --- Time ---

    @Test
    fun `time gridlines land on intervals a person would name`() {
        val named = setOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L)

        listOf(10_000L, 30_000L, 60_000L, 120_000L).forEach { span ->
            val step = GraphAxes.timeStepMs(span)
            assertTrue("$span gave a step of $step", step in named || step % 60_000L == 0L)
        }
    }

    @Test
    fun `the axis always marks the present`() {
        // Everything else on it is measured from the playhead, so an axis without a zero has no
        // origin to be read against.
        listOf(5_000L, 30_000L, 90_000L, 600_000L).forEach { span ->
            assertTrue("no now at $span", GraphAxes.timeOffsetsMs(span).contains(0L))
        }
    }

    @Test
    fun `offsets are symmetric and stay inside the window`() {
        val span = 30_000L
        val offsets = GraphAxes.timeOffsetsMs(span)
        val half = span / 2

        offsets.forEach { assertTrue("$it is outside the window", kotlin.math.abs(it) <= half) }
        // The playhead is centred, so what is behind and what is ahead must be labelled alike.
        offsets.filter { it != 0L }.forEach {
            assertTrue("$it has no mirror", offsets.contains(-it))
        }
    }

    @Test
    fun `offsets come back in order`() {
        val offsets = GraphAxes.timeOffsetsMs(60_000L)

        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun `the present is named rather than numbered`() {
        // "0s" reads as a quantity. The label is there to say where the present is, which is a
        // landmark.
        assertEquals("now", GraphAxes.offsetLabel(0L))
    }

    @Test
    fun `offsets read as ahead or behind`() {
        assertEquals("+15s", GraphAxes.offsetLabel(15_000L))
        assertEquals("−15s", GraphAxes.offsetLabel(-15_000L))
        assertEquals("+1m", GraphAxes.offsetLabel(60_000L))
        assertEquals("−2m", GraphAxes.offsetLabel(-120_000L))
        assertEquals("+1:30", GraphAxes.offsetLabel(90_000L))
    }

    @Test
    fun `the window caption says how far either side can be seen`() {
        // The one-line answer to the question the axis exists for.
        assertEquals("±15s", GraphAxes.windowCaption(30_000L))
        assertEquals("±5s", GraphAxes.windowCaption(10_000L))
        assertEquals("±1m", GraphAxes.windowCaption(120_000L))
    }

    @Test
    fun `a degenerate window does not hang or divide by nothing`() {
        // Guarded because these run per frame: a zero span from a one-sample recording would
        // otherwise loop forever building offsets.
        listOf(0L, 1L, -5L).forEach { span ->
            val offsets = GraphAxes.timeOffsetsMs(span)
            assertTrue(offsets.contains(0L))
            assertTrue(offsets.size < 30)
        }
    }
}
