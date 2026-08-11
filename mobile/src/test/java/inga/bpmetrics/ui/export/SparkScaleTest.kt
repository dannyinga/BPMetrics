package inga.bpmetrics.ui.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sparkline's axis.
 *
 * Worth a test because every failure here is silent: the plot still draws, the curve still has a
 * shape, and the only symptom is that the shape means something other than what it appears to.
 */
class SparkScaleTest {

    /** The invariant everything else rests on: nothing is plotted outside its own axis. */
    @Test
    fun `the axis always contains the readings`() {
        val cases = listOf(
            60.0 to 61.0,
            88.0 to 174.0,
            147.3 to 152.9,
            0.0 to 0.0,
            200.0 to 201.0
        )
        cases.forEach { (low, high) ->
            val scale = SparkScale.of(low, high)
            assertTrue(
                "$low..$high fell outside ${scale.first}..${scale.last}",
                scale.first <= low && scale.last >= high
            )
        }
    }

    /**
     * The reason this exists.
     *
     * A clip where nobody moved three beats must not be given the full height of the plot — that is
     * the comparison-bar failure over again, where scaling between the extremes made two close
     * numbers look like nothing against everything.
     */
    @Test
    fun `a flat clip is not magnified into a skyline`() {
        val scale = SparkScale.of(149.0, 151.0)
        assertTrue(
            "a 2 bpm range drew on a ${scale.last - scale.first} bpm axis",
            scale.last - scale.first >= SparkScale.MIN_SPAN_BPM
        )
        // And the two beats sit near the middle of it rather than at the extremes, which is what
        // makes them *look* like the small change they are.
        val middle = (scale.first + scale.last) / 2.0
        assertTrue(middle > 145.0 && middle < 155.0)
    }

    /** A real climb keeps its own range — the minimum is a floor, not a target. */
    @Test
    fun `a wide range is left alone apart from rounding`() {
        assertEquals(85..175, SparkScale.of(88.0, 174.0))
    }

    /** Grid lines have to land on numbers people round to, or they are decoration. */
    @Test
    fun `bounds are multiples of five`() {
        listOf(60.0 to 61.0, 88.0 to 174.0, 147.3 to 152.9, 99.0 to 101.0).forEach { (low, high) ->
            val scale = SparkScale.of(low, high)
            assertEquals(0, scale.first % 5)
            assertEquals(0, scale.last % 5)
        }
    }

    /**
     * The middle rung is drawn halfway up, so it has to be a whole number of the same kind.
     *
     * It is `(first + last) / 2` in the drawing code; an odd number of five-steps would put it on a
     * `.5` and print a rounded label on a line that is not where that value sits.
     */
    @Test
    fun `the midpoint of the axis is a whole number`() {
        listOf(60.0 to 61.0, 88.0 to 174.0, 147.3 to 152.9).forEach { (low, high) ->
            val scale = SparkScale.of(low, high)
            assertEquals(
                "${scale.first}..${scale.last} has no whole midpoint",
                0,
                (scale.first + scale.last) % 2
            )
        }
    }

    /**
     * A resting heart rate near the bottom of the range must not produce a negative floor.
     *
     * Widening is symmetric, so a narrow range close to zero would otherwise push the floor below
     * it — and an axis that starts at -5 bpm spends a fifth of its height on impossible values.
     */
    @Test
    fun `the axis never starts below zero`() {
        val scale = SparkScale.of(2.0, 3.0)
        assertEquals(0, scale.first)
        assertTrue(scale.last - scale.first >= SparkScale.MIN_SPAN_BPM)
    }

    /** A single reading is a legitimate clip — one sample, no range at all. */
    @Test
    fun `one reading still gets a readable axis`() {
        val scale = SparkScale.of(120.0, 120.0)
        assertTrue(scale.first <= 120 && scale.last >= 120)
        assertTrue(scale.last - scale.first >= SparkScale.MIN_SPAN_BPM)
    }
}
