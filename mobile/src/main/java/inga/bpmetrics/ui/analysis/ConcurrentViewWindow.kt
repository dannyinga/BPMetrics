package inga.bpmetrics.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The stretch of wall clock a concurrent chart is currently showing.
 *
 * Kept in absolute time rather than as an offset because everything the chart draws — every
 * wearer's curve, the group band, a scrubbed instant — is already absolute. Converting once here
 * avoids each of them re-deriving the same origin.
 */
class ConcurrentViewWindow(
    private val fullStartMs: Long,
    private val fullEndMs: Long
) {
    var startMs by mutableStateOf(fullStartMs)
        private set
    var endMs by mutableStateOf(fullEndMs)
        private set

    private val fullSpan get() = (fullEndMs - fullStartMs).coerceAtLeast(1L)
    val spanMs get() = (endMs - startMs).coerceAtLeast(1L)

    /** Whether anything is hidden, so the UI can offer a way back to the whole thing. */
    val isZoomed: Boolean get() = startMs > fullStartMs || endMs < fullEndMs

    /** How much of the whole recording is visible, 0..1. */
    val visibleFraction: Float get() = (spanMs.toFloat() / fullSpan.toFloat()).coerceIn(0f, 1f)

    /** Where the visible window begins within the whole, 0..1. */
    val scrollFraction: Float
        get() {
            val scrollable = fullSpan - spanMs
            if (scrollable <= 0L) return 0f
            return ((startMs - fullStartMs).toFloat() / scrollable.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * The narrowest window this recording can be zoomed to.
     *
     * Ten seconds normally: past that the curves are wider apart than the data is dense, and the
     * chart shows interpolation rather than measurements.
     *
     * But never wider than the recording itself. A two-second recording has a full span of 2,000ms,
     * and asking for a floor of 10,000 against a ceiling of 2,000 is an inverted range — which
     * `coerceIn` does not clamp, it throws. Pinching such a chart crashed the screen. A recording
     * shorter than the floor simply cannot be zoomed into, which is the correct behaviour and what
     * the equal bounds now produce.
     */
    private val minSpan get() = MIN_SPAN_MS.coerceAtMost(fullSpan)

    /**
     * Zooms about [focusX], so whatever is under the fingers stays under the fingers.
     *
     * Bounded below by [minSpan].
     */
    fun zoomBy(factor: Float, focusX: Float, width: Float) {
        if (width <= 0f || factor <= 0f) return

        val focusRatio = (focusX / width).coerceIn(0f, 1f)
        val focusTime = startMs + (focusRatio * spanMs).toLong()

        val newSpan = (spanMs / factor).toLong().coerceIn(minSpan, fullSpan)
        var newStart = focusTime - (focusRatio * newSpan).toLong()
        var newEnd = newStart + newSpan

        if (newStart < fullStartMs) {
            newStart = fullStartMs
            newEnd = newStart + newSpan
        }
        if (newEnd > fullEndMs) {
            newEnd = fullEndMs
            newStart = newEnd - newSpan
        }

        startMs = newStart.coerceAtLeast(fullStartMs)
        endMs = newEnd.coerceAtMost(fullEndMs)
    }

    /** Moves the window so it begins [fraction] of the way through the whole recording. */
    fun scrollTo(fraction: Float) {
        val scrollable = fullSpan - spanMs
        if (scrollable <= 0L) return

        val span = spanMs
        startMs = fullStartMs + (fraction.coerceIn(0f, 1f) * scrollable).toLong()
        endMs = startMs + span
    }

    fun reset() {
        startMs = fullStartMs
        endMs = fullEndMs
    }

    /** The instant at a horizontal position within the chart. */
    fun timeAt(x: Float, width: Float): Long {
        if (width <= 0f) return startMs
        return startMs + ((x / width).coerceIn(0f, 1f) * spanMs).toLong()
    }

    /** Where an instant falls across the chart, in pixels. Off-screen values fall outside 0..width. */
    fun xFor(wallClockMs: Long, width: Float): Float =
        ((wallClockMs - startMs).toFloat() / spanMs.toFloat()) * width

    private companion object {
        const val MIN_SPAN_MS = 10_000L
    }
}

@Composable
fun rememberConcurrentViewWindow(analysis: ConcurrentAnalysis): ConcurrentViewWindow =
    // Keyed on the window so opening a different analysis starts fully zoomed out rather than
    // inheriting the last one's scroll position.
    remember(analysis.windowStartMs, analysis.windowEndMs) {
        ConcurrentViewWindow(analysis.windowStartMs, analysis.windowEndMs)
    }
