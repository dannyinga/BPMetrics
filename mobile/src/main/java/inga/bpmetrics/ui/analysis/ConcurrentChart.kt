package inga.bpmetrics.ui.analysis

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import kotlin.math.roundToInt

/**
 * Everyone's heart rate over one shared wall clock.
 *
 * Curves are drawn against a common BPM axis rather than each being normalised to its own, so the
 * shapes are directly readable as heart rates. Normalisation belongs to the group intensity
 * calculation, where comparing people is the point; here, drawing one person's 60-90 range across
 * the same height as another's 60-180 would misrepresent both.
 *
 * @param window The stretch currently shown. Pinching adjusts it; the caller supplies the slider.
 * @param scrubbedMs Instant to mark, for tying the chart to a selected moment.
 * @param onScrub Reports the instant under the user's finger.
 */
@Composable
fun ConcurrentChart(
    analysis: ConcurrentAnalysis,
    window: ConcurrentViewWindow,
    modifier: Modifier = Modifier,
    scrubbedMs: Long? = null,
    onScrub: (Long?) -> Unit = {}
) {
    if (analysis.isEmpty) return

    val gridColor = Color(0x33CCCCCC)
    val intensityColor = MaterialTheme.colorScheme.primary
    val scrubColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val minBpm = remember(analysis) {
        (analysis.series.minOf { it.minBpm } - 5).coerceAtLeast(30.0)
    }
    val maxBpm = remember(analysis) {
        (analysis.series.maxOf { it.maxBpm } + 5).coerceAtMost(220.0)
    }

    val bpmLabelPaint = remember(labelColor) {
        Paint().apply {
            color = labelColor.toArgb()
            textAlign = Paint.Align.RIGHT
            textSize = 28f
            isAntiAlias = true
        }
    }
    val timeLabelPaint = remember(labelColor) {
        Paint().apply {
            color = labelColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 26f
            isAntiAlias = true
        }
    }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                // One detector for both gestures. Two competing pointerInputs would fight over
                // the same drag, so zoom and scrub are told apart by whether fingers pinched.
                .pointerInput(analysis) {
                    detectTransformGestures { centroid, _, zoom, _ ->
                        if (zoom != 1f) {
                            window.zoomBy(zoom, centroid.x - AXIS_GUTTER_PX, plotWidth(size.width.toFloat()))
                        } else {
                            onScrub(window.timeAt(centroid.x - AXIS_GUTTER_PX, plotWidth(size.width.toFloat())))
                        }
                    }
                }
                .pointerInput(analysis) {
                    detectTapGestures { offset ->
                        onScrub(window.timeAt(offset.x - AXIS_GUTTER_PX, plotWidth(size.width.toFloat())))
                    }
                }
        ) {
            val plotWidth = plotWidth(size.width)
            val plotHeight = size.height - TIME_AXIS_PX

            drawGridAndBpmLabels(gridColor, minBpm, maxBpm, plotHeight, bpmLabelPaint)

            // Group intensity sits behind the curves as a filled band: it is context for the
            // shapes on top, not another line competing with them.
            drawIntensityBand(analysis, window, intensityColor, plotWidth, plotHeight)

            analysis.series.forEach { series ->
                drawSeries(series, window, minBpm, maxBpm, plotWidth, plotHeight)
            }

            drawTimeLabels(window, plotWidth, plotHeight, timeLabelPaint)

            scrubbedMs?.let { drawScrubLine(it, window, scrubColor, plotWidth, plotHeight) }
        }
    }
}

/** Width available to the curves, once the BPM axis has taken its gutter. */
private fun plotWidth(totalWidth: Float): Float = (totalWidth - AXIS_GUTTER_PX).coerceAtLeast(1f)

private fun DrawScope.drawGridAndBpmLabels(
    color: Color,
    minBpm: Double,
    maxBpm: Double,
    plotHeight: Float,
    labelPaint: Paint
) {
    val lines = 4
    val span = (maxBpm - minBpm).coerceAtLeast(1.0)

    repeat(lines + 1) { i ->
        val fraction = i.toFloat() / lines
        val y = plotHeight * fraction
        val bpm = maxBpm - (span * fraction)

        drawLine(color, Offset(AXIS_GUTTER_PX, y), Offset(size.width, y), strokeWidth = 1f)

        drawContext.canvas.nativeCanvas.drawText(
            bpm.roundToInt().toString(),
            AXIS_GUTTER_PX - 8f,
            // Nudged onto the line rather than sitting on its baseline.
            y + 10f,
            labelPaint
        )
    }
}

/**
 * Clock times beneath the plot.
 *
 * Spaced by pixels rather than by time so the count stays readable at any zoom — a fixed number
 * of divisions would crowd on a narrow screen and look sparse on a wide one.
 */
private fun DrawScope.drawTimeLabels(
    window: ConcurrentViewWindow,
    plotWidth: Float,
    plotHeight: Float,
    labelPaint: Paint
) {
    val divisions = (plotWidth / 220f).toInt().coerceIn(2, 6)
    val y = plotHeight + 30f

    repeat(divisions + 1) { i ->
        val fraction = i.toFloat() / divisions
        val at = window.startMs + (fraction * window.spanMs).toLong()
        val x = (AXIS_GUTTER_PX + fraction * plotWidth)
            // Keep the first and last labels inside the canvas instead of half off it.
            .coerceIn(AXIS_GUTTER_PX + 24f, size.width - 24f)

        drawContext.canvas.nativeCanvas.drawText(getTimeString(at), x, y, labelPaint)
    }
}

private fun DrawScope.drawIntensityBand(
    analysis: ConcurrentAnalysis,
    window: ConcurrentViewWindow,
    color: Color,
    plotWidth: Float,
    plotHeight: Float
) {
    if (analysis.intensity.isEmpty()) return

    val visible = analysis.intensity.filter {
        it.wallClockMs >= window.startMs && it.wallClockMs <= window.endMs
    }
    if (visible.isEmpty()) return

    val path = Path().apply {
        moveTo(AXIS_GUTTER_PX + window.xFor(visible.first().wallClockMs, plotWidth), plotHeight)
        visible.forEach { moment ->
            // Moments nobody shared are drawn flat: a lone spike is not the group reacting.
            val value = if (moment.participants > 1) moment.intensity else 0f
            lineTo(
                AXIS_GUTTER_PX + window.xFor(moment.wallClockMs, plotWidth),
                plotHeight - (value * plotHeight)
            )
        }
        lineTo(AXIS_GUTTER_PX + window.xFor(visible.last().wallClockMs, plotWidth), plotHeight)
        close()
    }
    drawPath(path, color.copy(alpha = 0.12f))
}

private fun DrawScope.drawSeries(
    series: ConcurrentSeries,
    window: ConcurrentViewWindow,
    minBpm: Double,
    maxBpm: Double,
    plotWidth: Float,
    plotHeight: Float
) {
    if (series.points.isEmpty()) return

    val span = (maxBpm - minBpm).coerceAtLeast(1.0)
    val colour = Color(series.colorArgb)

    fun yFor(bpm: Double) = plotHeight - (((bpm - minBpm) / span).toFloat() * plotHeight)

    // One point either side of the visible window keeps the line running to the edges instead of
    // stopping short when zoomed in.
    val visible = series.points.filter {
        it.wallClockMs >= window.startMs - window.spanMs && it.wallClockMs <= window.endMs + window.spanMs
    }
    if (visible.isEmpty()) return

    val path = Path()
    var started = false
    var previous: TimedBpm? = null

    visible.forEach { point ->
        val x = AXIS_GUTTER_PX + window.xFor(point.wallClockMs, plotWidth)
        val y = yFor(point.bpm)

        // Break the line across a sensor dropout rather than drawing a straight segment through
        // it, which would look like a real, very smooth heart rate.
        val gap = previous?.let { point.wallClockMs - it.wallClockMs > GAP_THRESHOLD_MS } ?: false

        if (!started || gap) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
        previous = point
    }

    clipRect(left = AXIS_GUTTER_PX, top = 0f, right = size.width, bottom = plotHeight) {
        drawPath(path, colour, style = Stroke(width = 3f))
    }
}

private fun DrawScope.drawScrubLine(
    at: Long,
    window: ConcurrentViewWindow,
    color: Color,
    plotWidth: Float,
    plotHeight: Float
) {
    if (at < window.startMs || at > window.endMs) return
    val x = AXIS_GUTTER_PX + window.xFor(at, plotWidth)
    drawLine(color.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, plotHeight), strokeWidth = 2f)
}

/** Room reserved at the left for BPM labels. */
private const val AXIS_GUTTER_PX = 72f

/** Room reserved at the bottom for clock labels. */
private const val TIME_AXIS_PX = 40f

private const val GAP_THRESHOLD_MS = 10_000L
