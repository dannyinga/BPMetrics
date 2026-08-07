package inga.bpmetrics.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a timeline picture claims about the data.
 *
 * The drawing itself needs a device, but the numbers printed beside the curve do not — and those
 * are the part that can be quietly wrong. A summary that disagrees with the graph above it is worse
 * than no summary, because it looks authoritative.
 */
class TimelineImageSpecTest {

    private fun series(vararg bpm: Double) = TimelineImageExporter.Series(
        label = "Kyle",
        colorArgb = 0xFF42A5F5.toInt(),
        points = bpm.mapIndexed { index, value ->
            TimelineImageExporter.Series.Point(index * 1_000L, value)
        }
    )

    @Test
    fun `a series reports the range it actually covers`() {
        val kyle = series(62.0, 140.0, 168.0, 94.0)

        assertEquals(62.0, kyle.min!!, 0.001)
        assertEquals(168.0, kyle.max!!, 0.001)
        assertEquals((62.0 + 140.0 + 168.0 + 94.0) / 4, kyle.avg!!, 0.001)
    }

    @Test
    fun `an empty series has no statistics rather than zeroes`() {
        // Zero is a heart rate. "No reading" is not, and printing 0 bpm beside someone's name
        // states something false about a person.
        val nobody = TimelineImageExporter.Series("Ben", 0, emptyList())

        assertEquals(null, nobody.min)
        assertEquals(null, nobody.max)
        assertEquals(null, nobody.avg)
    }

    @Test
    fun `the window is at least an instant wide`() {
        // Guards the divisor every x-coordinate is computed against. A zero-width window would put
        // every point at the same place, or throw.
        val spec = TimelineImageExporter.Spec(
            width = 1920,
            height = 1080,
            title = "Subtronics",
            windowStartWallClockMs = 1_700_000_000_000L,
            windowEndWallClockMs = 1_700_000_000_000L,
            series = listOf(series(90.0))
        )

        assertTrue(spec.durationMs >= 1L)
    }

    @Test
    fun `a window spanning a real set reports its true length`() {
        val start = 1_700_000_000_000L
        val spec = TimelineImageExporter.Spec(
            width = 1920,
            height = 1080,
            title = "Subtronics",
            windowStartWallClockMs = start,
            windowEndWallClockMs = start + 3_600_000L,
            series = listOf(series(90.0, 120.0))
        )

        assertEquals(3_600_000L, spec.durationMs)
    }

    @Test
    fun `sections describe positions within the window, not wall clock`() {
        // The renderer places a section by dividing its start by the window length. Storing wall
        // clock here would put every band off the right-hand edge by about fifty-four years.
        val section = TimelineImageExporter.Section("Zeds Dead", startMs = 0L, endMs = 600_000L)

        assertTrue(section.startMs < 24 * 60 * 60_000L)
        assertEquals(600_000L, section.endMs - section.startMs)
    }

    @Test
    fun `render refuses a spec with nothing to draw rather than producing an empty picture`() {
        val spec = TimelineImageExporter.Spec(
            width = 1920,
            height = 1080,
            title = "Nothing",
            windowStartWallClockMs = 0L,
            windowEndWallClockMs = 1_000L,
            series = listOf(TimelineImageExporter.Series("Ben", 0, emptyList()))
        )

        // A blank canvas saved to the gallery is worse than being told there was nothing to save.
        assertEquals(null, TimelineImageExporter.render(spec))
    }

    @Test
    fun `render refuses a canvas with no area`() {
        val spec = TimelineImageExporter.Spec(
            width = 0,
            height = 1080,
            title = "Subtronics",
            windowStartWallClockMs = 0L,
            windowEndWallClockMs = 1_000L,
            series = listOf(series(90.0))
        )

        assertEquals(null, TimelineImageExporter.render(spec))
    }
}
