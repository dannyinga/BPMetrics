package inga.bpmetrics.ui.analysis

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import inga.bpmetrics.ui.theme.BpmRamp

/**
 * The blue-to-red heart rate gradient, as Compose sees it.
 *
 * A single recording's curve is coloured by *value* — cool at the bottom of its range, hot at the
 * top — which is what makes the shape readable at a glance without reading the axis. That is a
 * different job from the flat per-person colours a multi-lane chart uses, where the colour has to
 * say *whose* curve it is and cannot also encode height.
 *
 * The walk itself is [BpmRamp], shared with both renderers, so what the chart shows and what an
 * export draws are the same colours by construction rather than by two copies of the same maths
 * happening to agree.
 */
object BpmGradient {

    /**
     * The colour for one reading within a range.
     *
     * @param fraction Where the reading sits in the range, 0..1.
     */
    fun colorAt(fraction: Float): Color = Color(BpmRamp.forFraction(fraction))

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
