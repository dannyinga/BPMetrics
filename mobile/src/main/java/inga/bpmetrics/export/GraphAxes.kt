package inga.bpmetrics.export

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What the numbers down the side and along the bottom of a graph should say.
 *
 * Pulled out of the renderer because it is arithmetic, and because the arithmetic was wrong in a way
 * only a person could notice: the vertical axis divided the data range into six equal parts and
 * printed whatever fell out, so a graph was labelled 154, 167, 180. Those are true and useless. An
 * axis is read by *landing on* a value — "that peak is about 170" — and nobody lands on 167.
 *
 * All pure, so the choices can be checked without rendering anything.
 */
internal object GraphAxes {

    // --- Heart rate, down the side ---

    /**
     * The gap between gridlines for a range of [span] bpm, aiming for [target] lines.
     *
     * Only steps a person counts in. Five, ten and twenty-five are how anyone reads a heart rate
     * off a chart, and the nearest "mathematically tidy" answer — powers of ten and their halves —
     * would offer 2 and 50 while refusing 25.
     */
    fun bpmStep(span: Double, target: Int = 5): Double {
        val rough = (span / target.coerceAtLeast(1)).coerceAtLeast(1.0)
        return STEPS.firstOrNull { it >= rough } ?: STEPS.last()
    }

    private val STEPS = listOf(5.0, 10.0, 20.0, 25.0, 50.0, 100.0)

    /**
     * The range widened outward to land on whole steps.
     *
     * So the top and bottom gridlines are the frame of the plot rather than two arbitrary lines
     * floating inside it. Widening rather than narrowing, always: cropping to a tidy number would
     * put a peak outside the visible range, and a graph that hides the highest reading is worse
     * than one with an untidy edge.
     */
    fun snapBpmRange(min: Double, max: Double, target: Int = 5): ClosedFloatingPointRange<Double> {
        val step = bpmStep((max - min).coerceAtLeast(1.0), target)
        val low = floor(min / step) * step
        val high = ceil(max / step) * step
        // A range that snapped to nothing — every reading identical — still needs a plot to draw in.
        return if (high - low < step) low..(low + step) else low..high
    }

    /** Every gridline value within [range], on the step. */
    fun bpmGridLines(range: ClosedFloatingPointRange<Double>, target: Int = 5): List<Double> {
        val step = bpmStep(range.endInclusive - range.start, target)
        val first = ceil(range.start / step) * step
        if (step <= 0.0) return emptyList()

        val values = mutableListOf<Double>()
        var value = first
        // Bounded rather than while-true: a step that came out zero or negative from a degenerate
        // range would otherwise spin here, and this runs on every frame of a render.
        while (value <= range.endInclusive + 0.001 && values.size < MAX_LINES) {
            values += value
            value += step
        }
        return values
    }

    private const val MAX_LINES = 24

    // --- Time, along the bottom ---

    /**
     * The gap between time gridlines for a window of [spanMs], aiming for [target] divisions.
     *
     * Intervals someone would actually name. A tick every seven seconds is arithmetically fine and
     * reads as noise; five, ten, fifteen, thirty are the ones a person holds in their head.
     */
    fun timeStepMs(spanMs: Long, target: Int = 4): Long {
        val rough = (spanMs / target.coerceAtLeast(1)).coerceAtLeast(1L)
        return TIME_STEPS.firstOrNull { it >= rough } ?: TIME_STEPS.last()
    }

    private val TIME_STEPS = listOf(
        1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L,
        60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L
    )

    /**
     * Offsets from the playhead, in milliseconds, for a window of [spanMs] centred on it.
     *
     * Relative rather than absolute, and this is the whole reason the time axis is worth having.
     * The window is a setting — ten seconds on one preset, a minute on another — so a row of clock
     * times says nothing about *how far ahead you can see*, which is the only question the axis is
     * being asked. "+15s" answers it; "21:47:30" does not.
     *
     * Always includes zero, because the playhead is the thing everything else is measured from.
     */
    fun timeOffsetsMs(spanMs: Long, target: Int = 4): List<Long> {
        val half = (spanMs / 2).coerceAtLeast(1L)
        val step = timeStepMs(spanMs, target)

        val offsets = mutableListOf(0L)
        var offset = step
        while (offset <= half && offsets.size < MAX_LINES) {
            offsets += offset
            offsets += -offset
            offset += step
        }
        return offsets.sorted()
    }

    /**
     * An offset from the playhead, as someone would say it.
     *
     * Zero is "now" rather than "0s". The point of the label is to say where the present is, and a
     * zero reads as a quantity rather than as a landmark.
     */
    fun offsetLabel(offsetMs: Long): String {
        if (offsetMs == 0L) return "now"

        val sign = if (offsetMs < 0) "−" else "+"
        val seconds = (abs(offsetMs) / 1000.0).roundToLong()

        return when {
            seconds < 60 -> "$sign${seconds}s"
            seconds % 60 == 0L -> "$sign${seconds / 60}m"
            else -> "$sign${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }

    /**
     * How much of the recording is visible either side of the playhead, as a caption.
     *
     * The one-line version of the whole time axis, for where there is no room to label every line —
     * and the sentence someone actually wants: not what time it is, but how far ahead they can see
     * a climb coming.
     */
    fun windowCaption(spanMs: Long): String {
        val half = (spanMs / 2).coerceAtLeast(1L)
        return "±${offsetLabel(half).removePrefix("+")}"
    }

    /** A whole number of bpm for a gridline label, never a decimal. */
    fun bpmLabel(value: Double): String = value.roundToInt().toString()
}
