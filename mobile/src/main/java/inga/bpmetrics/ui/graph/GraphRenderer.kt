package inga.bpmetrics.ui.graph

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow
import inga.bpmetrics.ui.util.StringFormatHelpers
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A stateless component that renders the BPM graph's visual elements.
 *
 * Updated to use HSV interpolation for gradients, matching the Image and Video exporters.
 */
@Composable
fun GraphRenderer(
    record: BpmRecord,
    state: GraphState,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true
) {
    // Colors aligned with Exporter palette
    val gridColor = Color(0x33CCCCCC)
    val labelColor = Color.White
    val secondaryLabelColor = Color(0xFFCCCCCC)
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val handleColor = MaterialTheme.colorScheme.primary
    val lowBpmColor = BpmLow
    val highBpmColor = BpmHigh

    val valueLabelPaint = remember(labelColor) {
        Paint().apply {
            color = labelColor.toArgb()
            textAlign = Paint.Align.RIGHT
            textSize = 36f
        }
    }

    val clockTimeLabelPaint = remember(secondaryLabelColor) {
        Paint().apply {
            color = secondaryLabelColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 30f
        }
    }

    val axisTitlePaint = remember(secondaryLabelColor) {
        Paint().apply {
            color = secondaryLabelColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 42f
            isFakeBoldText = true
        }
    }

    val globalMinBpm = floor(record.minDataPoint!!.bpm / 10) * 10
    val globalMaxBpm = ceil(record.maxDataPoint!!.bpm / 10) * 10
    val globalBpmRange = (globalMaxBpm - globalMinBpm).coerceAtLeast(1.0)

    var dragMode by remember { mutableStateOf<DragMode>(DragMode.None) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(
                if (isInteractive) {
                    Modifier
                        .pointerInput(state.totalDuration) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val paddingLeft = 120f
                                val paddingRight = 40f
                                val graphWidth = size.width - paddingLeft - paddingRight
                                state.transform(centroid.x - paddingLeft, graphWidth, pan.x, zoom)
                            }
                        }
                        .pointerInput(state.totalDuration) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val paddingLeft = 120f
                                    val paddingRight = 40f
                                    val graphWidth = size.width - paddingLeft - paddingRight
                                    if (offset.x > paddingLeft && offset.x < size.width - paddingRight) {
                                        val time = state.zoomRange.first + ((offset.x - paddingLeft) / graphWidth * (state.zoomRange.last - state.zoomRange.first)).toLong()
                                        
                                        // Hit test for handles
                                        val handleWidthPx = 40f
                                        val startX = paddingLeft + (state.selectionStartMs?.let { (it - state.zoomRange.first).toFloat() / (state.zoomRange.last - state.zoomRange.first) * graphWidth } ?: -1000f)
                                        val endX = paddingLeft + (state.selectionEndMs?.let { (it - state.zoomRange.first).toFloat() / (state.zoomRange.last - state.zoomRange.first) * graphWidth } ?: -1000f)
                                        
                                        dragMode = when {
                                            abs(offset.x - startX) < handleWidthPx -> DragMode.StartHandle
                                            abs(offset.x - endX) < handleWidthPx -> DragMode.EndHandle
                                            else -> {
                                                state.selectionStartMs = time
                                                state.selectionEndMs = time
                                                DragMode.NewSelection
                                            }
                                        }
                                    }
                                },
                                onDrag = { change, _ ->
                                    val paddingLeft = 120f
                                    val paddingRight = 40f
                                    val graphWidth = size.width - paddingLeft - paddingRight
                                    val offset = change.position
                                    if (offset.x > paddingLeft && offset.x < size.width - paddingRight) {
                                        val ratio = (offset.x - paddingLeft) / graphWidth
                                        val time = state.zoomRange.first + (ratio * (state.zoomRange.last - state.zoomRange.first)).toLong()
                                        
                                        when (dragMode) {
                                            DragMode.StartHandle -> state.selectionStartMs = time
                                            DragMode.EndHandle -> state.selectionEndMs = time
                                            DragMode.NewSelection -> state.selectionEndMs = time
                                            DragMode.None -> {}
                                        }
                                    }
                                },
                                onDragEnd = { dragMode = DragMode.None },
                                onDragCancel = { dragMode = DragMode.None }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val paddingLeft = 120f
                                val paddingRight = 40f
                                val graphWidth = size.width - paddingLeft - paddingRight
                                if (offset.x > paddingLeft && offset.x < size.width - paddingRight) {
                                    state.inspectedRatio = (offset.x - paddingLeft) / graphWidth
                                    state.clearSelection()
                                }
                            }
                        }
                } else Modifier
            )
    ) {
        val width = size.width
        val height = size.height
        val paddingLeft = 120f
        val paddingBottom = 140f
        val paddingTop = 40f
        val paddingRight = 40f
        val graphWidth = width - paddingLeft - paddingRight
        val graphHeight = height - paddingTop - paddingBottom

        // Draw Axes Titles
        drawContext.canvas.nativeCanvas.drawText("Time", paddingLeft + graphWidth / 2, height - 10f, axisTitlePaint)
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.rotate(-90f, 30f, paddingTop + graphHeight / 2)
        drawContext.canvas.nativeCanvas.drawText("BPM", 30f, paddingTop + graphHeight / 2, axisTitlePaint)
        drawContext.canvas.nativeCanvas.restore()

        val zoomRange = state.zoomRange
        val visibleDataPoints = record.dataPoints.filter { it.timestamp in zoomRange }
        if (visibleDataPoints.isEmpty()) return@Canvas

        val timeRangeMs = (zoomRange.last - zoomRange.first).coerceAtLeast(1)

        fun mapTime(timeMs: Long): Float {
            val relativeTimestamp = timeMs - zoomRange.first
            val xRatio = relativeTimestamp.toFloat() / timeRangeMs
            return paddingLeft + (xRatio * graphWidth)
        }

        fun mapPoint(point: BpmDataPointEntity): Offset {
            val yRatio = (point.bpm - globalMinBpm) / globalBpmRange
            return Offset(mapTime(point.timestamp), paddingTop + (1.0f - yRatio.toFloat()) * graphHeight)
        }

        // Draw Selection Overlay & Handles
        if (state.selectionStartMs != null && state.selectionEndMs != null) {
            val startX = mapTime(min(state.selectionStartMs!!, state.selectionEndMs!!)).coerceIn(paddingLeft, width - paddingRight)
            val endX = mapTime(max(state.selectionStartMs!!, state.selectionEndMs!!)).coerceIn(paddingLeft, width - paddingRight)
            
            drawRect(
                color = selectionColor,
                topLeft = Offset(startX, paddingTop),
                size = androidx.compose.ui.geometry.Size(endX - startX, graphHeight)
            )
            
            // Handles
            drawLine(handleColor, Offset(startX, paddingTop), Offset(startX, paddingTop + graphHeight), 4f)
            drawLine(handleColor, Offset(endX, paddingTop), Offset(endX, paddingTop + graphHeight), 4f)
            drawCircle(handleColor, radius = 8f, center = Offset(startX, paddingTop + graphHeight / 2))
            drawCircle(handleColor, radius = 8f, center = Offset(endX, paddingTop + graphHeight / 2))
        }

        // Draw Grid and Labels
        val gridCount = 4
        for (i in 0..gridCount) {
            val x = paddingLeft + (graphWidth / gridCount) * i
            val currentTimeMs = zoomRange.first + ((timeRangeMs / gridCount) * i)
            val absoluteTimeMs = record.metadata.startTime + currentTimeMs

            drawLine(gridColor, Offset(x, paddingTop), Offset(x, paddingTop + graphHeight), 1f)
            
            // Relative HMS Time
            drawContext.canvas.nativeCanvas.drawText(
                TimeUtils.formatMs(currentTimeMs),
                x, paddingTop + graphHeight + 45f,
                valueLabelPaint.apply { textAlign = Paint.Align.CENTER }
            )

            // Absolute Clock Time
            drawContext.canvas.nativeCanvas.drawText(
                StringFormatHelpers.getTimeString(absoluteTimeMs).replace(":00 ", " "),
                x, paddingTop + graphHeight + 85f,
                clockTimeLabelPaint.apply { textAlign = Paint.Align.CENTER }
            )
        }

        val horizontalGridLines = 5
        val bpmStep = (globalBpmRange / horizontalGridLines).roundToInt().coerceAtLeast(1)
        for (i in 0..horizontalGridLines) {
            val currentBpm = globalMinBpm + (i * bpmStep)
            val yRatio = (currentBpm - globalMinBpm) / globalBpmRange
            val y = paddingTop + (1.0 - yRatio) * graphHeight
            drawLine(gridColor, Offset(paddingLeft, y.toFloat()), Offset(width - paddingRight, y.toFloat()), 1f)
            drawContext.canvas.nativeCanvas.drawText(
                currentBpm.roundToInt().toString(),
                paddingLeft - 20f, y.toFloat() + 12f,
                valueLabelPaint.apply { textAlign = Paint.Align.RIGHT }
            )
        }

        // Curve path
        val path = Path()
        val firstPoint = mapPoint(visibleDataPoints.first())
        path.moveTo(firstPoint.x, firstPoint.y)
        for (i in 1 until visibleDataPoints.size) {
            val nextPoint = mapPoint(visibleDataPoints[i])
            path.lineTo(nextPoint.x, nextPoint.y)
        }

        // Gradient & Shading using HSV interpolation for vibrancy (consistent with exporters)
        val stops = 5
        val gradientColors = List(stops) { i ->
            val bpm = globalMinBpm + (globalBpmRange * i / (stops - 1))
            getBpmColor(bpm, globalMinBpm, globalMaxBpm, lowBpmColor, highBpmColor)
        }
        
        val gradientBrush = Brush.verticalGradient(
            colors = gradientColors.reversed(), // reversed because paddingTop is max BPM
            startY = paddingTop,
            endY = paddingTop + graphHeight
        )
        
        val fillPath = Path().apply {
            addPath(path)
            lineTo(mapPoint(visibleDataPoints.last()).x, paddingTop + graphHeight)
            lineTo(mapPoint(visibleDataPoints.first()).x, paddingTop + graphHeight)
            close()
        }
        
        val fillColors = gradientColors.reversed().mapIndexed { i, color ->
            color.copy(alpha = 0.2f * (1f - i.toFloat() / (stops - 1)).coerceAtLeast(0.02f))
        }
        
        drawPath(fillPath, brush = Brush.verticalGradient(
            colors = fillColors,
            startY = paddingTop, endY = paddingTop + graphHeight
        ), style = Fill)
        drawPath(path, brush = gradientBrush, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Pointer
        state.inspectedRatio?.let { ratio ->
            val x = paddingLeft + (ratio * graphWidth)
            drawLine(labelColor.copy(alpha = 0.4f), Offset(x, paddingTop), Offset(x, paddingTop + graphHeight), 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))

            state.inspectedTimeMs?.let { time ->
                visibleDataPoints.minByOrNull { abs(it.timestamp - time) }?.let { point ->
                    val offset = mapPoint(point)
                    val pointColor = getBpmColor(point.bpm, globalMinBpm, globalMaxBpm, lowBpmColor, highBpmColor)
                    drawCircle(pointColor, radius = 12f, center = offset)
                    drawCircle(Color.White, radius = 6f, center = offset)
                }
            }
        }
    }
}

/**
 * Interpolates between two colors in HSV space.
 */
private fun getBpmColor(bpm: Double, minBpm: Double, maxBpm: Double, lowColor: Color, highColor: Color): Color {
    val fraction = ((bpm - minBpm) / (maxBpm - minBpm)).coerceIn(0.0, 1.0).toFloat()
    val hsvStart = FloatArray(3)
    val hsvEnd = FloatArray(3)
    android.graphics.Color.colorToHSV(lowColor.toArgb(), hsvStart)
    android.graphics.Color.colorToHSV(highColor.toArgb(), hsvEnd)
    
    val sH = hsvStart[0]
    var eH = hsvEnd[0]
    if (eH < sH) eH += 360f
    
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(
        (sH + (eH - sH) * fraction) % 360f,
        hsvStart[1] + (hsvEnd[1] - hsvStart[1]) * fraction,
        hsvStart[2] + (hsvEnd[2] - hsvStart[2]) * fraction
    )))
}

private enum class DragMode { None, NewSelection, StartHandle, EndHandle }
