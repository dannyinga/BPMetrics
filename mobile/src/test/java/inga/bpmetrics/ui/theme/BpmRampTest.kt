package inga.bpmetrics.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared heart rate ramp.
 *
 * Three copies of this walk used to exist — the chart's, the video renderer's, and a midpoint-only
 * one in the timeline image. Nothing checked that they agreed, and in this project that is exactly
 * how the preview and the export came to draw different colours for the same reading. These tests
 * exist so the one remaining copy cannot drift from what it renders today.
 */
class BpmRampTest {

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF

    @Test
    fun `the ends of the ramp are the colours it was given`() {
        val low = 0xFF6FC3FF.toInt()
        val high = 0xFFFF6B6B.toInt()

        // Within a point of rounding: the round trip through HSV is lossy at 8 bits per channel.
        assertChannelsClose(low, BpmRamp.blend(low, high, 0f))
        assertChannelsClose(high, BpmRamp.blend(low, high, 1f))
    }

    @Test
    fun `the midpoint does not pass through grey`() {
        // The whole reason the walk is through HSV. A straight RGB blend from this blue to this red
        // gives a muddy near-grey; HSV keeps the midtone saturated.
        val mid = BpmRamp.blend(0xFF6FC3FF.toInt(), 0xFFFF6B6B.toInt(), 0.5f)

        val max = maxOf(red(mid), green(mid), blue(mid))
        val min = minOf(red(mid), green(mid), blue(mid))
        assertTrue(
            "midpoint $mid is too desaturated: max $max min $min",
            (max - min) > 60
        )
    }

    @Test
    fun `hue rounds the short way rather than through every other colour`() {
        // Blue to red the long way passes through green. If the wrap handling regresses, the
        // quarter point is greener than it is blue.
        val quarter = BpmRamp.blend(0xFF6FC3FF.toInt(), 0xFFFF6B6B.toInt(), 0.25f)

        assertTrue(
            "quarter point $quarter went round through green",
            blue(quarter) > green(quarter)
        )
    }

    @Test
    fun `alpha comes from the low end and is not interpolated`() {
        // The two ends of a heart rate range are never meant to differ in transparency, so a
        // translucent low end stays translucent all the way up.
        val translucent = 0x806FC3FF.toInt()
        val opaque = 0xFFFF6B6B.toInt()

        assertEquals(0x80, alpha(BpmRamp.blend(translucent, opaque, 0f)))
        assertEquals(0x80, alpha(BpmRamp.blend(translucent, opaque, 0.5f)))
        assertEquals(0x80, alpha(BpmRamp.blend(translucent, opaque, 1f)))
    }

    @Test
    fun `fractions outside the range are clamped rather than extrapolated`() {
        val low = BpmPalette.LOW
        val high = BpmPalette.HIGH

        assertEquals(BpmRamp.blend(low, high, 0f), BpmRamp.blend(low, high, -3f))
        assertEquals(BpmRamp.blend(low, high, 1f), BpmRamp.blend(low, high, 7f))
    }

    @Test
    fun `the ramp climbs rather than repeating itself`() {
        // Every step is a distinct colour, and red rises across the range while blue falls. This is
        // what makes the curve's shape readable without reading the axis.
        val steps = (0..10).map { BpmRamp.forFraction(it / 10f) }

        assertEquals(steps.size, steps.distinct().size)
        assertTrue(red(steps.last()) > red(steps.first()))
        assertTrue(blue(steps.first()) > blue(steps.last()))
    }

    @Test
    fun `blending a colour with itself returns it`() {
        val teal = 0xFF00DCC2.toInt()

        assertChannelsClose(teal, BpmRamp.blend(teal, teal, 0.5f))
    }

    @Test
    fun `greys have no hue to walk and stay grey`() {
        val dark = 0xFF202020.toInt()
        val light = 0xFFE0E0E0.toInt()

        val mid = BpmRamp.blend(dark, light, 0.5f)

        assertEquals(red(mid), green(mid))
        assertEquals(green(mid), blue(mid))
        assertTrue(red(mid) in red(dark)..red(light))
    }

    @Test
    fun `the app ramp runs between the palette's own low and high`() {
        // forFraction is what the chart draws with; blend against the palette is what an export
        // draws with when the preset is left at its defaults. They must be the same colour.
        for (i in 0..10) {
            val f = i / 10f
            assertEquals(
                BpmRamp.blend(BpmPalette.LOW, BpmPalette.HIGH, f),
                BpmRamp.forFraction(f)
            )
        }
    }

    @Test
    fun `low and high are actually different colours`() {
        // Guards the palette rather than the ramp: a ramp between two equal endpoints is flat, and
        // every curve in the app would silently lose its shading.
        assertNotEquals(BpmPalette.LOW, BpmPalette.HIGH)
    }

    private fun assertChannelsClose(expected: Int, actual: Int) {
        assertEquals("alpha", alpha(expected), alpha(actual))
        assertEquals("red", red(expected).toFloat(), red(actual).toFloat(), 1f)
        assertEquals("green", green(expected).toFloat(), green(actual).toFloat(), 1f)
        assertEquals("blue", blue(expected).toFloat(), blue(actual).toFloat(), 1f)
    }
}
