package inga.bpmetrics.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A custom-drawn graph component that visualizes heart rate data points over time.
 * 
 * It supports zooming via time-range input and inspection of specific points via tap gestures.
 *
 * @param record The [BpmRecord] containing data points and metadata to visualize.
 * @param modifier The modifier to be applied to the outer layout.
 * @param lineColor The color of the main heart rate path.
 * @param axisColor The color of the X and Y axes.
 * @param gridColor The color of the background grid lines.
 */
@Composable
fun BpmGraph(
    record: BpmRecord,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.Red,
    axisColor: Color = Color.Gray,
    gridColor: Color = Color.LightGray
) {
    if (record.dataPoints.isEmpty() || record.maxDataPoint == null || record.minDataPoint == null) return

    // State for the currently inspected time and corresponding BPM
    var inspectedTimeMs by remember { mutableStateOf<Long?>(null) }
    val inspectedBpm = remember(inspectedTimeMs) {
        inspectedTimeMs?.let { ms ->
            record.dataPoints.minByOrNull { kotlin.math.abs(it.timestamp - ms) }?.bpm
        }
    }

    // State for the zoom range (start and end time in ms)
    var zoomRange by remember { mutableStateOf(0L..record.metadata.durationMs) }

    // State for the text input fields for zoom
    var zoomStartInput by remember(zoomRange) { mutableStateOf((zoomRange.first / 1000.0).toString()) }
    var zoomEndInput by remember(zoomRange) { mutableStateOf((zoomRange.last / 1000.0).toString()) }

    // Remember the Paint objects for drawing text on the native canvas. 
    val valueLabelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.BLACK
            textAlign = Paint.Align.RIGHT
            textSize = 40f
        }
    }

    val axisTitlePaint = remember {
        Paint().apply {
            color = android.graphics.Color.DKGRAY
            textAlign = Paint.Align.CENTER
            textSize = 48f
            isFakeBoldText = true
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(horizontal = 16.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val graphWidth = size.width - 160f 
                        val paddingLeft = 120f

                        if (offset.x > paddingLeft && offset.x < size.width - 40f) {
                            val timeRatio = (offset.x - paddingLeft) / graphWidth
                            val timeInZoom = (timeRatio * (zoomRange.last - zoomRange.first)).toLong()
                            inspectedTimeMs = zoomRange.first + timeInZoom
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val paddingLeft = 120f
            val paddingBottom = 80f
            val paddingTop = 40f
            val paddingRight = 40f
            val graphWidth = width - paddingLeft - paddingRight
            val graphHeight = height - paddingTop - paddingBottom

            // Draw X-Axis Title
            drawContext.canvas.nativeCanvas.drawText(
                "Time (s)",
                paddingLeft + graphWidth / 2,
                height + 10f + valueLabelPaint.textSize,
                axisTitlePaint
            )

            // Draw Y-Axis Title (Rotated)
            drawContext.canvas.nativeCanvas.save()
            drawContext.canvas.nativeCanvas.rotate(
                -90f,
                30f,
                paddingTop + graphHeight / 2
            )
            drawContext.canvas.nativeCanvas.drawText(
                "BPM",
                30f,
                paddingTop + graphHeight / 2 - valueLabelPaint.textSize,
                axisTitlePaint
            )
            drawContext.canvas.nativeCanvas.restore()

            val visibleDataPoints = record.dataPoints.filter { it.timestamp in zoomRange }
            if (visibleDataPoints.isEmpty()) return@Canvas

            val minVisibleBpm = visibleDataPoints.minOf { it.bpm }
            val maxVisibleBpm = visibleDataPoints.maxOf { it.bpm }

            val minBpm = floor(minVisibleBpm / 10) * 10
            val maxBpm = ceil(maxVisibleBpm / 10) * 10
            val bpmRange = (maxBpm - minBpm).coerceAtLeast(1.0)
            val timeRangeMs = (zoomRange.last - zoomRange.first).coerceAtLeast(1)

            /** Maps a data point to coordinates on the canvas based on padding and zoom. */
            fun mapPoint(point: BpmDataPointEntity): Offset {
                val relativeTimestamp = point.timestamp - zoomRange.first
                val xRatio = relativeTimestamp.toFloat() / timeRangeMs
                val yRatio = (point.bpm - minBpm) / bpmRange
                val x = paddingLeft + (xRatio * graphWidth)
                val y = height - paddingBottom - (yRatio * graphHeight)
                return Offset(x, y.toFloat())
            }

            // Draw Grid Lines and Labels
            val verticalGridLines = 5
            val timeStepMs = timeRangeMs.toDouble() / verticalGridLines

            for (i in 0..verticalGridLines) {
                val x = paddingLeft + (graphWidth / verticalGridLines) * i
                val currentTimeMs = zoomRange.first + (timeStepMs * i)
                val currentTimeSec = (currentTimeMs / 1000.0)

                drawLine(gridColor, Offset(x, paddingTop), Offset(x, height-paddingBottom), 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    String.format("%.1f", currentTimeSec),
                    x, height - paddingBottom + 60f,
                    valueLabelPaint.apply { textAlign = Paint.Align.CENTER }
                )
            }
            valueLabelPaint.apply { textAlign = Paint.Align.RIGHT }

            val horizontalGridLines = 5
            val bpmStep = (bpmRange / horizontalGridLines).roundToInt().coerceAtLeast(1)

            for (i in 0..horizontalGridLines) {
                val currentBpm = minBpm + (i * bpmStep)
                val yRatio = (currentBpm - minBpm) / bpmRange
                val y = height - paddingBottom - (yRatio * graphHeight).toFloat()
                
                drawLine(gridColor, Offset(paddingLeft, y), Offset(width - paddingRight, y), 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    currentBpm.roundToInt().toString(),
                    paddingLeft - 20f,
                    y + 15f,
                    valueLabelPaint
                )
            }

            // Draw Line Graph Path
            val path = Path()
            val firstPoint = mapPoint(visibleDataPoints.first())
            path.moveTo(firstPoint.x, firstPoint.y)
            for (i in 1 until visibleDataPoints.size) {
                val nextPoint = mapPoint(visibleDataPoints[i])
                path.lineTo(nextPoint.x, nextPoint.y)
            }
            drawPath(path, lineColor, style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Draw Inspection Indicator
            inspectedTimeMs?.let { time ->
                if (time in zoomRange) {
                    val relativeTimestamp = time - zoomRange.first
                    val xRatio = relativeTimestamp.toFloat() / timeRangeMs
                    val x = paddingLeft + (xRatio * graphWidth)
                    drawLine(
                        color = Color.DarkGray,
                        start = Offset(x, paddingTop),
                        end = Offset(x, height - paddingBottom),
                        strokeWidth = 3f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // Inspection Display
        if (inspectedTimeMs != null && inspectedBpm != null) {
            Text("Time: %.2f s  |  BPM: %.1f".format(inspectedTimeMs!! / 1000.0, inspectedBpm))
        } else {
            Text("Tap on the graph to inspect a point")
        }

        Spacer(Modifier.height(24.dp))

        // Zoom Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = zoomStartInput,
                onValueChange = { zoomStartInput = it },
                label = { Text("Start (s)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = zoomEndInput,
                onValueChange = { zoomEndInput = it },
                label = { Text("End (s)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val start = (zoomStartInput.toDoubleOrNull() ?: 0.0) * 1000
                val end = (zoomEndInput.toDoubleOrNull() ?: (record.metadata.durationMs / 1000.0)) * 1000
                zoomRange = start.roundToLong().coerceAtLeast(0L)..end.roundToLong().coerceAtMost(record.metadata.durationMs)
            }) {
                Text("Zoom")
            }
        }
    }
}
