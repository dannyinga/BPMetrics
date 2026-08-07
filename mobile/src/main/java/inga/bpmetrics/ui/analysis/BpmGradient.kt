package inga.bpmetrics.ui.analysis

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow

/**
 * The blue-to-red heart rate gradient, in one place.
 *
 * A single recording's curve is coloured by *value* — cool at the bottom of its range, hot at the
 * top — which is what makes the shape readable at a glance without reading the axis. That is a
 * different job from the flat per-person colours a multi-lane chart uses, where the colour has to
 * say *whose* curve it is and cannot also encode height.
 *
 * Interpolated through HSV rather than RGB. A straight RGB blend from blue to red passes through
 * a muddy grey in the middle; HSV walks the hue round and keeps the midtones saturated, which is
 * why the exporters have always done it this way.
 */
object BpmGradient {

    /**
     * The colour for one reading within a range.
     *
     * @param fraction Where the reading sits in the range, 0..1.
     */
    fun colorAt(fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        val start = FloatArray(3)
        val end = FloatArray(3)
        android.graphics.Color.colorToHSV(BpmLow.toArgb(), start)
        android.graphics.Color.colorToHSV(BpmHigh.toArgb(), end)

        var startHue = start[0]
        var endHue = end[0]
        // Round the short way, or a hue that wraps past 360 runs backwards through every other
        // colour on the wheel.
        if (endHue < startHue) endHue += 360f

        return Color(
            android.graphics.Color.HSVToColor(
                floatArrayOf(
                    (startHue + (endHue - startHue) * f) % 360f,
                    start[1] + (end[1] - start[1]) * f,
                    start[2] + (end[2] - start[2]) * f
                )
            )
        )
    }

    /**
     * A vertical brush spanning a plot, hot at the top.
     *
     * Reversed against the stop order because a canvas grows downward while heart rate grows up.
     */
    fun verticalBrush(topY: Float, bottomY: Float, stops: Int = 5): Brush =
        Brush.verticalGradient(
            colors = List(stops) { i -> colorAt(i.toFloat() / (stops - 1)) }.reversed(),
            startY = topY,
            endY = bottomY
        )
}
