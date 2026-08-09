package inga.bpmetrics.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom window, and in particular what it does to recordings shorter than the zoom floor.
 *
 * A pinch on a two-second recording used to crash the screen: the floor is ten seconds, the ceiling
 * is the recording's own span, and `coerceIn(10_000, 2_000)` is an inverted range, which throws
 * rather than clamping. Every case below that starts a recording shorter than ten seconds exists
 * because of that crash.
 */
class ConcurrentViewWindowTest {

    private val start = 1_700_000_000_000L

    @Test
    fun `pinching a two second recording does not throw`() {
        val window = ConcurrentViewWindow(start, start + 2_000L)

        window.zoomBy(factor = 2f, focusX = 50f, width = 100f)

        // Nothing to zoom into, so the window stays whole rather than narrowing past the recording.
        assertEquals(start, window.startMs)
        assertEquals(start + 2_000L, window.endMs)
        assertFalse(window.isZoomed)
    }

    @Test
    fun `repeated pinches on a short recording stay bounded`() {
        val window = ConcurrentViewWindow(start, start + 2_000L)

        repeat(10) { window.zoomBy(factor = 4f, focusX = 10f, width = 100f) }

        assertEquals(2_000L, window.spanMs)
        assertEquals(1f, window.visibleFraction, 0.0001f)
    }

    @Test
    fun `a recording exactly at the floor is still not zoomable`() {
        val window = ConcurrentViewWindow(start, start + 10_000L)

        window.zoomBy(factor = 3f, focusX = 50f, width = 100f)

        assertEquals(10_000L, window.spanMs)
        assertFalse(window.isZoomed)
    }

    @Test
    fun `a long recording still zooms to the ten second floor and no further`() {
        val window = ConcurrentViewWindow(start, start + 600_000L)

        repeat(10) { window.zoomBy(factor = 4f, focusX = 50f, width = 100f) }

        assertEquals(10_000L, window.spanMs)
        assertTrue(window.isZoomed)
    }

    @Test
    fun `zooming keeps the focused instant under the finger`() {
        val window = ConcurrentViewWindow(start, start + 600_000L)
        val focusedBefore = window.timeAt(75f, 100f)

        window.zoomBy(factor = 2f, focusX = 75f, width = 100f)

        // Within a millisecond of rounding — the window is integer milliseconds.
        assertEquals(focusedBefore.toDouble(), window.timeAt(75f, 100f).toDouble(), 1.0)
        assertEquals(300_000L, window.spanMs)
    }

    @Test
    fun `zooming out returns to the whole recording`() {
        val window = ConcurrentViewWindow(start, start + 600_000L)
        window.zoomBy(factor = 8f, focusX = 20f, width = 100f)
        assertTrue(window.isZoomed)

        window.zoomBy(factor = 0.05f, focusX = 20f, width = 100f)

        assertEquals(start, window.startMs)
        assertEquals(start + 600_000L, window.endMs)
        assertFalse(window.isZoomed)
    }

    @Test
    fun `a zero length recording survives a pinch`() {
        // Possible with a single data point, where the window start and end are the same instant.
        val window = ConcurrentViewWindow(start, start)

        window.zoomBy(factor = 2f, focusX = 50f, width = 100f)

        assertEquals(start, window.startMs)
        assertEquals(1f, window.visibleFraction, 0.0001f)
    }

    @Test
    fun `scrolling a short recording is a no-op rather than a throw`() {
        val window = ConcurrentViewWindow(start, start + 2_000L)

        window.scrollTo(0.75f)

        assertEquals(start, window.startMs)
        assertEquals(0f, window.scrollFraction, 0.0001f)
    }

    @Test
    fun `a zoomed window scrolls within the recording`() {
        val window = ConcurrentViewWindow(start, start + 600_000L)
        window.zoomBy(factor = 2f, focusX = 0f, width = 100f)

        window.scrollTo(1f)

        assertEquals(start + 600_000L, window.endMs)
        assertEquals(1f, window.scrollFraction, 0.0001f)
    }

    @Test
    fun `reset undoes a zoom`() {
        val window = ConcurrentViewWindow(start, start + 600_000L)
        window.zoomBy(factor = 6f, focusX = 30f, width = 100f)

        window.reset()

        assertFalse(window.isZoomed)
        assertEquals(600_000L, window.spanMs)
    }

    @Test
    fun `a zero width chart is ignored rather than dividing by it`() {
        val window = ConcurrentViewWindow(start, start + 600_000L)

        window.zoomBy(factor = 2f, focusX = 0f, width = 0f)

        assertFalse(window.isZoomed)
        assertEquals(start, window.timeAt(50f, 0f))
    }
}
