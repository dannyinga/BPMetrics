package inga.bpmetrics.ui.detail

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import androidx.core.content.FileProvider
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.graph.TimeUtils
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.InputStreamReader
import java.sql.Date
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Utility class for exporting and importing BPM record data.
 */
object ExportHelpers {

    /**
     * Formats a BPM record into a CSV string.
     * Includes raw timestamps for accurate reconstruction.
     */
    fun getCsvString(record: BpmRecord): String {
        val writer = StringBuilder()
        // 1. Write Header Info
        writer.appendLine("BPMetrics Data Export")
        writer.appendLine("Title,${record.metadata.title}")
        writer.appendLine("Date,${inga.bpmetrics.ui.util.StringFormatHelpers.getDateString(record.metadata.date)}")
        writer.appendLine("Start Time (ms),${record.metadata.startTime}")
        writer.appendLine("End Time (ms),${record.metadata.endTime}")
        writer.appendLine("Duration (ms),${record.metadata.durationMs}")
        writer.appendLine("Average BPM,${record.metadata.avg ?: 0.0}")
        writer.appendLine("Min BPM,${record.minDataPoint?.bpm ?: 0.0}")
        writer.appendLine("Max BPM,${record.maxDataPoint?.bpm ?: 0.0}")
        writer.appendLine("Tags,${record.tags.joinToString(" | ") { it.name }}")
        writer.appendLine() // Spacer

        // 2. Write Data Columns
        writer.appendLine("Timestamp (ms),BPM")
        record.dataPoints.sortedBy { it.timestamp }.forEach { point ->
            writer.appendLine("${point.timestamp},${point.bpm}")
        }
        return writer.toString()
    }

    /**
     * Parses a CSV file back into a BpmWatchRecord for reconstruction.
     */
    fun importFromCsv(context: Context, uri: Uri): BpmWatchRecord? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var startTime = 0L
                var endTime = 0L
                val dataPoints = mutableListOf<BpmDataPoint>()
                var isDataSection = false

                reader.forEachLine { line ->
                    val parts = line.split(",")
                    if (!isDataSection) {
                        when {
                            line.startsWith("Start Time (ms),") -> startTime = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                            line.startsWith("End Time (ms),") -> endTime = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                            line.startsWith("Timestamp (ms),BPM") -> isDataSection = true
                        }
                    } else if (parts.size >= 2) {
                        val ts = parts[0].toLongOrNull()
                        val bpm = parts[1].toDoubleOrNull()
                        if (ts != null && bpm != null) {
                            dataPoints.add(BpmDataPoint(ts, bpm))
                        }
                    }
                }

                if (startTime != 0L && endTime != 0L && dataPoints.isNotEmpty()) {
                    BpmWatchRecord(
                        date = Date(startTime),
                        dataPoints = dataPoints,
                        startTime = startTime,
                        endTime = endTime
                    )
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Generates a CSV file for a BPM record and triggers the Android Share Sheet.
     */
    fun shareCsv(context: Context, record: BpmRecord) {
        val fileName = "${record.metadata.title.replace(" ", "_")}_data.csv"
        val tempFile = File(context.cacheDir, fileName)

        try {
            FileWriter(tempFile).use { it.write(getCsvString(record)) }
            shareFile(context, tempFile, "text/csv")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Configuration for image export.
     */
    data class ImageExportConfig(
        val width: Int = 1920,
        val height: Int = 1080,
        val timeRange: LongRange,
        val backgroundOpacity: Int = 100,
        val showAxes: Boolean = true,
        val axesColor: Int = 0xFFCCCCCC.toInt(),
        val showLabels: Boolean = true,
        val labelsColor: Int = 0xFFFFFFFF.toInt(),
        val showGrid: Boolean = true,
        val gridColor: Int = 0x33CCCCCC.toInt(),
        val lowBpmColor: Int = 0xFF42A5F5.toInt(), // Matches BpmLow (Material Blue 400)
        val highBpmColor: Int = 0xFFF44336.toInt(), // Matches BpmHigh (Material Red 500)
        val showTitle: Boolean = true
    )

    /**
     * Renders the heart rate graph to a Bitmap for image export.
     */
    fun renderGraphToBitmap(
        record: BpmRecord,
        config: ImageExportConfig
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // 1. Clear with background opacity
        val alpha = (config.backgroundOpacity * 255 / 100).coerceIn(0, 255)
        canvas.drawARGB(alpha, 18, 18, 18)

        if (record.dataPoints.isEmpty()) return bitmap

        // 2. Setup Constants
        val paddingLeft = if (config.showLabels) 200f else 40f
        val paddingBottom = if (config.showLabels) 180f else 40f
        val paddingTop = if (config.showTitle) 120f else 60f
        val paddingRight = 60f
        val drawWidth = config.width - paddingLeft - paddingRight
        val drawHeight = config.height - paddingTop - paddingBottom
        
        val duration = (config.timeRange.last - config.timeRange.first).coerceAtLeast(1)
        val visiblePoints = record.dataPoints.filter { it.timestamp in config.timeRange }
        if (visiblePoints.isEmpty()) return bitmap

        val globalMinBpm = floor(record.minDataPoint!!.bpm / 10) * 10
        val globalMaxBpm = ceil(record.maxDataPoint!!.bpm / 10) * 10
        val globalBpmRange = (globalMaxBpm - globalMinBpm).coerceAtLeast(1.0)

        fun getX(ts: Long): Float = paddingLeft + (ts - config.timeRange.first).toFloat() / duration * drawWidth
        fun getY(bpm: Double): Float = paddingTop + (1f - (bpm - globalMinBpm).toFloat() / globalBpmRange.toFloat()) * drawHeight

        val paint = Paint().apply { isAntiAlias = true }

        // 3. Draw Title
        if (config.showTitle) {
            paint.color = config.labelsColor
            paint.textSize = 60f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText(record.metadata.title, paddingLeft + drawWidth / 2f, 80f, paint)
        }

        // 4. Draw Grid Lines and Labels
        if (config.showGrid || config.showLabels) {
            val gridCount = 5
            for (i in 0..gridCount) {
                val x = paddingLeft + (drawWidth / gridCount) * i
                val timeMs = config.timeRange.first + ((duration / gridCount) * i)
                
                if (config.showGrid) {
                    paint.color = config.gridColor
                    paint.strokeWidth = 2f
                    canvas.drawLine(x, paddingTop, x, paddingTop + drawHeight, paint)
                }
                
                if (config.showLabels) {
                    paint.color = config.labelsColor
                    paint.textSize = 32f
                    paint.textAlign = Paint.Align.CENTER
                    paint.isFakeBoldText = false
                    canvas.drawText(TimeUtils.formatMs(timeMs), x, paddingTop + drawHeight + 50f, paint)
                }
            }

            // Draw "Time" Axis Label
            if (config.showLabels) {
                paint.color = config.labelsColor
                paint.textSize = 42f
                paint.textAlign = Paint.Align.CENTER
                paint.isFakeBoldText = true
                canvas.drawText("Time", paddingLeft + drawWidth / 2f, paddingTop + drawHeight + 130f, paint)
            }

            val horizontalLines = 5
            val bpmStep = (globalBpmRange / horizontalLines).roundToInt().coerceAtLeast(1)
            for (i in 0..horizontalLines) {
                val bpm = globalMinBpm + (i * bpmStep)
                val y = getY(bpm)
                
                if (config.showGrid) {
                    paint.color = config.gridColor
                    paint.strokeWidth = 2f
                    canvas.drawLine(paddingLeft, y, paddingLeft + drawWidth, y, paint)
                }
                
                if (config.showLabels) {
                    paint.color = config.labelsColor
                    paint.textSize = 32f
                    paint.textAlign = Paint.Align.RIGHT
                    paint.isFakeBoldText = false
                    canvas.drawText(bpm.toString(), paddingLeft - 20f, y + 12f, paint)
                }
            }

            // Draw "BPM" Axis Label (Rotated)
            if (config.showLabels) {
                canvas.save()
                paint.color = config.labelsColor
                paint.textSize = 42f
                paint.textAlign = Paint.Align.CENTER
                paint.isFakeBoldText = true
                canvas.rotate(-90f, 60f, paddingTop + drawHeight / 2f)
                canvas.drawText("BPM", 60f, paddingTop + drawHeight / 2f, paint)
                canvas.restore()
            }
        }

        // 5. Draw Axes
        if (config.showAxes) {
            paint.color = config.axesColor
            paint.strokeWidth = 4f
            canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + drawHeight, paint) // Y axis
            canvas.drawLine(paddingLeft, paddingTop + drawHeight, paddingLeft + drawWidth, paddingTop + drawHeight, paint) // X axis
        }

        // 6. Draw Data Curve
        val path = Path()
        var first = true
        visiblePoints.forEach { point ->
            val x = getX(point.timestamp)
            val y = getY(point.bpm)
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }

        val curvePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            shader = LinearGradient(0f, paddingTop, 0f, paddingTop + drawHeight, 
                config.highBpmColor, config.lowBpmColor, Shader.TileMode.CLAMP)
        }

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            shader = LinearGradient(0f, paddingTop, 0f, paddingTop + drawHeight,
                intArrayOf(config.highBpmColor and 0x66FFFFFF, config.lowBpmColor and 0x11FFFFFF),
                null, Shader.TileMode.CLAMP)
        }

        val fillPath = Path(path).apply {
            lineTo(getX(visiblePoints.last().timestamp), paddingTop + drawHeight)
            lineTo(getX(visiblePoints.first().timestamp), paddingTop + drawHeight)
            close()
        }

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, curvePaint)

        return bitmap
    }

    /**
     * Saves a bitmap to a temporary file and shares it.
     */
    fun shareBitmap(context: Context, bitmap: Bitmap, title: String) {
        val fileName = "${title.replace(" ", "_")}_graph.png"
        val tempFile = File(context.cacheDir, fileName)
        
        try {
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            shareFile(context, tempFile, "image/png")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Export BPM Data"))
    }
}
