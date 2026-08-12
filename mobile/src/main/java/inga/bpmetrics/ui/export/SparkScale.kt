package inga.bpmetrics.ui.export

/**
 * The axis a clip's sparkline is drawn against.
 *
 * A sparkline is normalised floor-to-ceiling, which is right — it is there to show the *shape* of a
 * couple of minutes, and pinning the axis to zero would flatten every clip into a wiggle along the
 * top. But floor-to-ceiling has a failure that this project has already been bitten by once, in the
 * comparison bars: a plot scaled between its own extremes always fills its box, so 151 against 152
 * draws as nothing against everything. On a curve the same lie reads as a dramatic climb during a
 * clip where nobody's heart moved three beats.
 *
 * Two rules fix it, and the drawn grid lines are what make them legible:
 *
 * - **A minimum span.** Below [MIN_SPAN_BPM] the axis is widened about the middle, so flat footage
 *   draws flat instead of being magnified into a skyline.
 * - **Rounded outward to fives.** Grid lines land on numbers a reader recognises rather than on
 *   147.3, and rounding only ever outward means the drawn range always contains every reading.
 */
object SparkScale {

    /**
     * The shortest axis allowed, in bpm.
     *
     * Fifteen is roughly the smallest range where a change is worth looking at. Anything narrower
     * is noise, and noise should not be given the full height of the plot.
     */
    const val MIN_SPAN_BPM = 15

    /** Grid values are multiples of this, so the numbers on the plot are ones people round to. */
    private const val STEP = 5

    /**
     * The floor and ceiling to draw for readings spanning [low] to [high].
     *
     * Always contains `low..high`: the widening is symmetric about the middle and the rounding is
     * outward on both ends, so no reading can fall outside the axis it is plotted against.
     */
    fun of(low: Double, high: Double): IntRange {
        val middle = (low + high) / 2.0
        val wanted = (high - low).coerceAtLeast(MIN_SPAN_BPM.toDouble())

        // A heart rate cannot be negative, and an axis that starts below zero spends a fifth of its
        // height saying so. The clamp can eat the widening, though — a range of 2..3 widens to
        // -5..10 and then clamps to 0..10 — so the span is re-established afterwards rather than
        // assumed to have survived.
        val floor = (kotlin.math.floor((middle - wanted / 2.0) / STEP).toInt() * STEP)
            .coerceAtLeast(0)
        var ceiling = (kotlin.math.ceil((middle + wanted / 2.0) / STEP).toInt() * STEP)
            .coerceAtLeast(floor + MIN_SPAN_BPM)

        // An even number of steps, so the middle grid line lands on a whole bpm. The plot draws
        // three rungs and labels the halfway one `(floor + ceiling) / 2`; across an odd span that
        // is a `.5` printed as a rounded integer on a line that is not where that value sits — a
        // grid line that lies quietly, which is worse than no grid line.
        if (((ceiling - floor) / STEP) % 2 != 0) ceiling += STEP

        return floor..ceiling
    }
}
