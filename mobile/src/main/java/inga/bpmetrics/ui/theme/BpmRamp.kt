package inga.bpmetrics.ui.theme

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The cool-to-hot heart rate ramp, in one place.
 *
 * Three copies of this walk existed: the on-screen chart's, the video renderer's, and a half one in
 * the timeline image that only ever asked for the midpoint. They agreed by coincidence rather than
 * by construction, which in this project is the reliable precursor to them disagreeing — the same
 * number derived in two places is how the preview and the export came to draw different colours.
 *
 * Interpolated through HSV rather than RGB. A straight RGB blend from blue to red passes through a
 * muddy grey in the middle; HSV walks the hue round and keeps the midtones saturated.
 *
 * The conversions are written out rather than taken from `android.graphics.Color` so this is a
 * plain function with no framework behind it — which means it can be unit tested, and the renderers
 * and the chart can be shown to agree rather than assumed to.
 */
object BpmRamp {

    /**
     * A colour [fraction] of the way from [fromArgb] to [toArgb], 0..1.
     *
     * Alpha comes from [fromArgb]: the ramp describes a hue at a height, and the two endpoints of a
     * heart rate range are never meant to differ in transparency.
     */
    fun blend(fromArgb: Int, toArgb: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val from = toHsv(fromArgb)
        val to = toHsv(toArgb)

        // Round the short way, or a hue that wraps past 360 runs backwards through every other
        // colour on the wheel — blue to red would go by way of green.
        var endHue = to[0]
        if (endHue < from[0]) endHue += 360f

        return fromHsv(
            hue = (from[0] + (endHue - from[0]) * f) % 360f,
            saturation = from[1] + (to[1] - from[1]) * f,
            value = from[2] + (to[2] - from[2]) * f,
            alpha = (fromArgb ushr 24) and 0xFF
        )
    }

    /** The heart rate ramp's own endpoints, for anything drawing in the app's colours. */
    fun forFraction(fraction: Float): Int = blend(BpmPalette.LOW, BpmPalette.HIGH, fraction)

    /** Hue in degrees, saturation and value 0..1 — the same convention as `Color.colorToHSV`. */
    private fun toHsv(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }

        return floatArrayOf(
            if (hue < 0f) hue + 360f else hue,
            if (max == 0f) 0f else delta / max,
            max
        )
    }

    private fun fromHsv(hue: Float, saturation: Float, value: Float, alpha: Int): Int {
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val h = ((hue % 360f) + 360f) % 360f

        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c

        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        return ((alpha and 0xFF) shl 24) or
            (((r + m) * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            (((g + m) * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            ((b + m) * 255f).roundToInt().coerceIn(0, 255)
    }
}
