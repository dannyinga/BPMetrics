package inga.bpmetrics.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour rules of §5, as assertions.
 *
 * Every failure in this area is silent — a wrong colour still renders, and the only symptom is that
 * two screens quietly stop agreeing. That is exactly the class of bug the taxonomy work keeps
 * finding, so the rules are pinned rather than trusted.
 */
class BpmPaletteTest {

    /** The ramp's ends. If these move, every chart, band and export moves with them. */
    @Test
    fun `the ramp runs cool to warm`() {
        // Blue-ish at the bottom: more blue than red.
        assertTrue(BpmPalette.LOW.blue() > BpmPalette.LOW.red())
        // Warm at the top: more red than blue.
        assertTrue(BpmPalette.HIGH.red() > BpmPalette.HIGH.blue())
        // And the middle sits between them rather than off on its own — amber, not the green it
        // used to be, which read as "good" rather than "middling".
        assertTrue(BpmPalette.AVG.red() > BpmPalette.AVG.blue())
        assertTrue(BpmPalette.AVG.green() > BpmPalette.AVG.blue())
    }

    // --- Zones ---

    /** The band scheme spans the ramp, resting at the cool end. */
    @Test
    fun `zones run from the ramp's floor to its ceiling`() {
        assertEquals(BpmPalette.LOW, BpmPalette.zone(0, 4))
        assertEquals(BpmPalette.HIGH, BpmPalette.zone(3, 4))
    }

    /**
     * The bands go by way of the amber, not round the back of the colour wheel.
     *
     * The first version of [BpmPalette.zone] sampled `BpmRamp.forFraction`, which blends the two
     * *ends* of the ramp — and the hue walk from blue to red runs through violet and magenta, so
     * the bar came out blue, purple, pink, red and never touched the middle colour the palette
     * defines. It looked plausible in isolation and wrong the moment anyone who knew the old bar
     * saw it.
     *
     * Green above blue is what separates the two paths: amber and orange have far more green than
     * blue, while violet and magenta have the opposite.
     */
    @Test
    fun `the middle bands are warm, not violet`() {
        val middle = listOf(BpmPalette.zone(1, 4), BpmPalette.zone(2, 4))
        middle.forEach { colour ->
            assertTrue(
                "a middle band came out cool: ${Integer.toHexString(colour)}",
                colour.green() > colour.blue()
            )
            assertTrue(
                "a middle band came out cool: ${Integer.toHexString(colour)}",
                colour.red() > colour.blue()
            )
        }
        // Four bands land exactly on the four chosen stops, in order.
        assertEquals(BpmPalette.ZONES, (0 until 4).map { BpmPalette.zone(it, 4) })
    }

    /**
     * The reason zones are keyed on position rather than name.
     *
     * Five bands must space themselves across the same ramp. The `when` this replaced matched four
     * string literals and sent everything else to a single default, so a fifth band was invisible
     * and a renamed one silently lost its colour.
     */
    @Test
    fun `any number of bands gets distinct colours`() {
        listOf(2, 3, 4, 5, 7).forEach { count ->
            val colours = (0 until count).map { BpmPalette.zone(it, count) }
            assertEquals(
                "$count bands produced ${colours.distinct().size} colours",
                count,
                colours.distinct().size
            )
        }
    }

    /** Degenerate inputs answer something drawable rather than throwing mid-frame. */
    @Test
    fun `a single band, and out-of-range indices, still answer a colour`() {
        assertEquals(BpmPalette.LOW, BpmPalette.zone(0, 1))
        assertEquals(BpmPalette.LOW, BpmPalette.zone(0, 0))
        assertEquals(BpmPalette.LOW, BpmPalette.zone(-3, 4))
        assertEquals(BpmPalette.HIGH, BpmPalette.zone(99, 4))
    }

    // --- Neutral lanes ---

    /**
     * A neutral lane must not be mistakable for a person.
     *
     * §5's whole point: identity means a person. If a tag lane could come out in a person's colour,
     * the colour column would be claiming something false about the row.
     */
    @Test
    fun `the neutral series shares no colour with the ramp`() {
        val meaningful = setOf(BpmPalette.LOW, BpmPalette.AVG, BpmPalette.HIGH)
        BpmPalette.NEUTRAL.forEach { assertTrue(it !in meaningful) }
    }

    /** Six lanes get six colours; the seventh wraps rather than failing. */
    @Test
    fun `neutral lanes are distinct, and cycle`() {
        val first = (0 until BpmPalette.NEUTRAL.size).map { BpmPalette.neutral(it) }
        assertEquals(BpmPalette.NEUTRAL.size, first.distinct().size)
        assertEquals(BpmPalette.neutral(0), BpmPalette.neutral(BpmPalette.NEUTRAL.size))
        // Negative indices are a real possibility from an `indexOf` miss; they must not throw.
        assertEquals(BpmPalette.neutral(BpmPalette.NEUTRAL.size - 1), BpmPalette.neutral(-1))
    }

    /** The residual lane is dimmer than the lanes it sits among, not brighter. */
    @Test
    fun `the residual lane recedes`() {
        val residual = BpmPalette.RESIDUAL.luminance()
        BpmPalette.NEUTRAL.forEach {
            assertTrue("residual was brighter than a real lane", residual < it.luminance())
        }
        assertNotEquals(BpmPalette.RESIDUAL, BpmPalette.HIGH)
    }

    /** Every colour is fully opaque. A half-transparent constant would blend into whatever is behind. */
    @Test
    fun `the meaningful colours are opaque`() {
        val all = BpmPalette.NEUTRAL + listOf(
            BpmPalette.LOW, BpmPalette.AVG, BpmPalette.HIGH, BpmPalette.RESIDUAL, BpmPalette.TEAL
        )
        all.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
    }

    private fun Int.red() = (this shr 16) and 0xFF
    private fun Int.green() = (this shr 8) and 0xFF
    private fun Int.blue() = this and 0xFF
    private fun Int.luminance() = 0.299 * red() + 0.587 * green() + 0.114 * blue()
}
