package inga.bpmetrics.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.graph.TimeUtils
import inga.bpmetrics.ui.util.StringFormatHelpers
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import androidx.core.graphics.withClip
import inga.bpmetrics.export.ExportUtils.adjustAlpha
import kotlin.math.abs
import kotlin.math.sin

/**
 * Handles static image rendering of BPM graphs and shared configuration for rendering.
 */
object ImageExporter {



    /**
     * Configuration for image and video rendering.
     */
    data class ImageExportConfig(
        val width: Int = 1920,
        val height: Int = 1080,
        val startTimeMs: Long = 0L,
        val endTimeMs: Long = 0L,
        val backgroundOpacity: Int = 100,
        val showAxes: Boolean = true,
        val axesColor: Int = 0xFFCCCCCC.toInt(),
        val showLabels: Boolean = true,
        val labelsColor: Int = 0xFFFFFFFF.toInt(),
        val showGrid: Boolean = true,
        val gridColor: Int = 0x33CCCCCC,
        val lowBpmColor: Int = 0xFF42A5F5.toInt(),
        val highBpmColor: Int = 0xFFF44336.toInt(),
        val showTitle: Boolean = true,
        val showCurrentStats: Boolean = true,
        val headerXPercent: Float = 0.85f,
        val futureOpacity: Float = 0.65f
    )

    /**
     * Renders the BPM graph to a [Bitmap].
     *
     * @param record The BPM record to render.
     * @param config The configuration for rendering.
     * @return A [Bitmap] containing the rendered graph.
     */
    fun renderGraphToBitmap(record: BpmRecord, config: ImageExportConfig): Bitmap {
        val bitmap = createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        renderOnCanvas(canvas, record, config)
        return bitmap
    }

    /**
     * Shares a [Bitmap] as a PNG file using an Intent.
     *
     * @param context Android context.
     * @param bitmap The bitmap to share.
     * @param title The title for the filename.
     */
    fun shareBitmap(context: android.content.Context, bitmap: Bitmap, title: String) {
        val sanitizedTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(" ", "_")
        val fileName = "${sanitizedTitle}_graph.png"
        val tempFile = File(context.cacheDir, fileName)
        try {
            FileOutputStream(tempFile).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            ExportUtils.shareFile(context, tempFile, "image/png")
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * Interpolates the BPM value at a specific timestamp.
     *
     * @param points The sorted list of data points.
     * @param timestampMs The timestamp to interpolate at.
     * @return The interpolated BPM value, or null if the list is empty.
     */
    private fun getInterpolatedBpm(points: List<BpmDataPointEntity>, timestampMs: Double): Double? {
        if (points.isEmpty()) return null
        if (timestampMs <= (points.firstOrNull()?.timestamp ?: 0)) return points.firstOrNull()?.bpm
        if (timestampMs >= (points.lastOrNull()?.timestamp ?: 0)) return points.lastOrNull()?.bpm
        
        val p2Index = points.indexOfFirst { it.timestamp >= timestampMs }
        if (p2Index <= 0) return points.firstOrNull()?.bpm
        
        val p1 = points[p2Index - 1]
        val p2 = points[p2Index]
        
        val t1 = p1.timestamp.toDouble()
        val t2 = p2.timestamp.toDouble()
        val ratio = (timestampMs - t1) / (t2 - t1)
        return p1.bpm + ratio * (p2.bpm - p1.bpm)
    }

    /**
     * Core rendering logic that draws the BPM graph onto a [Canvas].
     *
     * This method handles drawing the background, grid, axes, data curve, and current stats overlay.
     * It is used for both static image export and video frame generation.
     *
     * @param canvas The canvas to draw on.
     * @param record The BPM record data.
     * @param config Rendering configuration.
     * @param currentTimeMs Optional current playback time for video rendering (affects scrolling window).
     * @param windowSizeMs Optional duration of the visible time window for scrolling.
     * @param backgroundBitmap Optional bitmap to draw as a background.
     * @param graphRect Defines the normalized rectangle (0-1) where the graph should be drawn within the canvas.
     */
    fun renderOnCanvas(
        canvas: Canvas,
        record: BpmRecord,
        config: ImageExportConfig,
        currentTimeMs: Double? = null,
        windowSizeMs: Long? = null,
        graphRect: RectF = RectF(0f, 0f, 1f, 1f)
    ) {
        if (record.dataPoints.isEmpty()) return

        // 1. Setup Dimensions and Scaling
        val dims = RenderingDimensions(canvas, graphRect, config)
        val scaleFactor = dims.scaleFactor

        // 2. Setup Ranges (Snippet for color, UI for stability)
        val ranges = calculateRanges(record, config)

        // 3. Setup Viewport (Time window)
        val viewport = calculateViewport(currentTimeMs, windowSizeMs, config, record)

        // 4. Draw Background with Rounded Corners
        val paint = Paint().apply { isAntiAlias = true }
        drawContainer(canvas, dims, config, paint)

        // 5. Draw Grid and Axes (Clipped to Graph Area)
        drawGridAndAxes(canvas, dims, ranges, config, paint)

        // 6. Draw Data Curve (Strictly clipped to 85% width to prevent HUD occlusion)
        drawDataCurve(canvas, dims, ranges, viewport, record, config, paint)

        // 7. Draw Glowing Head (Clipped to Graph Area)
        drawGlowingHead(canvas, dims, ranges, viewport, record, config, paint)

        // 8. Draw HUD (In the 15% Sidebar)
        drawStatsHUD(canvas, dims, ranges, viewport, record, config, paint)

        canvas.restore() // Final restore from drawContainer's save
    }

    private fun drawContainer(canvas: Canvas, dims: RenderingDimensions, config: ImageExportConfig, paint: Paint) {
        val outerCornerRadius = 24f * dims.scaleFactor
        val outerClipPath = Path().apply {
            addRoundRect(dims.outerRect, outerCornerRadius, outerCornerRadius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(outerClipPath)

        if (canvas.isOpaque) canvas.drawColor(android.graphics.Color.BLACK)
        val bgAlpha = (config.backgroundOpacity * 255 / 100).coerceIn(0, 255)
        if (bgAlpha > 0) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = 0xFF121212.toInt()
            paint.alpha = bgAlpha
            canvas.drawRect(dims.outerRect, paint)
        }
    }

    private fun drawGridAndAxes(canvas: Canvas, dims: RenderingDimensions, ranges: BpmRanges, config: ImageExportConfig, paint: Paint) {
        val lines = 5
        for (i in 0..lines) {
            val bpm = ranges.uiMin + (i * (ranges.uiRange / lines))
            val y = dims.getY(bpm, ranges)
            if (y !in dims.graphTop..dims.graphBottom) continue

            if (config.showGrid) {
                paint.reset()
                paint.color = config.gridColor
                paint.strokeWidth = 2f
                canvas.drawLine(dims.graphLeft, y, dims.graphRight, y, paint)
            }
            if (config.showLabels) {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = config.labelsColor
                paint.textSize = 28f * dims.scaleFactor
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(bpm.roundToInt().toString(), dims.graphLeft - 15f * dims.scaleFactor, y + 10f * dims.scaleFactor, paint)
            }
        }

        if (config.showTitle) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = config.labelsColor
            paint.textSize = 48f * dims.scaleFactor
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText(ranges.title, dims.graphLeft + dims.graphWidth / 2f, dims.drawAreaTop + 60f * dims.scaleFactor, paint)
        }
    }

    private fun drawDataCurve(canvas: Canvas, dims: RenderingDimensions, ranges: BpmRanges, viewport: Viewport, record: BpmRecord, config: ImageExportConfig, paint: Paint) {
        canvas.withClip(dims.graphLeft, dims.graphTop, dims.graphRight, dims.graphBottom) {
            val visiblePoints = record.dataPoints.filter {
                it.timestamp >= (viewport.start - viewport.lookahead) && it.timestamp <= (viewport.end + viewport.lookahead)
            }

            if (visiblePoints.isNotEmpty()) {
                val startBpm = getInterpolatedBpm(record.dataPoints, viewport.start) ?: 60.0
                val endBpm = getInterpolatedBpm(record.dataPoints, viewport.end) ?: 60.0
                val currentBpm = getInterpolatedBpm(record.dataPoints, viewport.playhead) ?: 60.0

                // Determine actual start position based on data availability
                val firstPoint = visiblePoints.first()
                val firstPointX = dims.getX(firstPoint.timestamp.toDouble(), viewport)

                // If the first point is after the viewport start, start there.
                // Otherwise, start at the left edge.
                val startX = maxOf(dims.graphLeft, firstPointX)
                val startY = if (firstPointX > dims.graphLeft) {
                    dims.getY(firstPoint.bpm, ranges)
                } else {
                    dims.getY(startBpm, ranges)
                }

                val pastPath = Path()
                val futurePath = Path()
                val fillPath = Path()
                val playheadX = dims.getX(viewport.playhead, viewport)
                val midY = dims.getY(currentBpm, ranges)

                // Initialize paths at the calculated start point
                pastPath.moveTo(startX, startY)
                fillPath.moveTo(startX, dims.graphBottom)
                fillPath.lineTo(startX, startY)

                var lastX = startX
                var lastY = startY

                // Build Past
                visiblePoints.filter { it.timestamp <= viewport.playhead }.forEach { p ->
                    val x = dims.getX(p.timestamp.toDouble(), viewport)
                    val y = dims.getY(p.bpm, ranges)

                    // Skip drawing if the point is at or before our startX
                    // (prevents drawing backwards if the first point is slightly off-screen)
                    if (x > startX) {
                        val cx = (lastX + x) / 2f
                        pastPath.cubicTo(cx, lastY, cx, y, x, y)
                        fillPath.cubicTo(cx, lastY, cx, y, x, y)
                        lastX = x
                        lastY = y
                    }
                }

                // Connect to playhead
                val cxMid = (lastX + playheadX) / 2f
                pastPath.cubicTo(cxMid, lastY, cxMid, midY, playheadX, midY)
                fillPath.cubicTo(cxMid, lastY, cxMid, midY, playheadX, midY)
                fillPath.lineTo(playheadX, dims.graphBottom)
                fillPath.close()

                // Build Future
                futurePath.moveTo(playheadX, midY)
                lastX = playheadX
                lastY = midY

                visiblePoints.filter { it.timestamp > viewport.playhead }.forEach { p ->
                    val x = dims.getX(p.timestamp.toDouble(), viewport)
                    val y = dims.getY(p.bpm, ranges)
                    val cx = (lastX + x) / 2f
                    futurePath.cubicTo(cx, lastY, cx, y, x, y)
                    lastX = x
                    lastY = y
                }

                // Determine actual end position based on data availability
                val lastPoint = visiblePoints.last()
                val lastPointX = dims.getX(lastPoint.timestamp.toDouble(), viewport)

                // If the last point is before the viewport end, stop there.
                // Otherwise, anchor exactly to the right edge.
                val endX = minOf(dims.graphRight, lastPointX)
                val finalY = if (lastPointX < dims.graphRight) {
                    dims.getY(lastPoint.bpm, ranges)
                } else {
                    dims.getY(endBpm, ranges)
                }

                // Only draw the connecting curve if there's horizontal distance to cover
                if (endX > lastX) {
                    futurePath.cubicTo((lastX + endX) / 2f, lastY, (lastX + endX) / 2f, finalY, endX, finalY)
                }

                // Render with updated bounds
                renderShaders(
                    canvas = this,
                    dims = dims,
                    ranges = ranges,
                    viewport = viewport,
                    config = config,
                    fill = fillPath,
                    past = pastPath,
                    future = futurePath,
                    playheadX = playheadX,
                    firstPointX = startX,
                    lastPointX = endX // Use calculated endX for the fade-out gradient
                )
            }
        }
    }
    private fun renderShaders(
        canvas: Canvas,
        dims: RenderingDimensions,
        ranges: BpmRanges,
        viewport: Viewport,
        config: ImageExportConfig,
        fill: Path,
        past: Path,
        future: Path,
        playheadX: Float,
        firstPointX: Float, // Pass the X of the first visible point
        lastPointX: Float   // Pass the X of the last visible point
    ) {
        val paint = Paint().apply { isAntiAlias = true }

        // --- 1. DEFINE HORIZONTAL ALPHA MASKS ---

        // Past Mask: Fades in from the FIRST DATA POINT to playhead
        // This ensures no ghost lines appearing before the data starts
        val pastAlphaMask = LinearGradient(
            firstPointX, 0f, playheadX, 0f,
            intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
            null, Shader.TileMode.CLAMP
        )

        // 1. Calculate the color with current opacity
        val futureAlpha = (config.futureOpacity * 255).toInt().coerceIn(0, 255)
        val futureAlphaColor = android.graphics.Color.argb(futureAlpha, 0, 0, 0)

        // 2. Future Mask: Use color stops to keep it visible for 60% of the distance
        // then fade to transparent in the last 40%
        val futureAlphaMask = LinearGradient(
            playheadX, 0f, lastPointX, 0f,
            intArrayOf(
                futureAlphaColor,   // Start at playhead
                futureAlphaColor,   // Still full "future" opacity at 60% mark
                android.graphics.Color.TRANSPARENT // Fade out by the end
            ),
            floatArrayOf(0.0f, 0.6f, 1.0f), // The "stops" (0% to 100%)
            Shader.TileMode.CLAMP
        )

        // --- 2. DEFINE VERTICAL COLOR GRADIENT (UI BASED) ---
        val midUiColor = getBpmColor(ranges.uiMin + (ranges.uiRange / 2.0), ranges, config)
        val colorGradient = LinearGradient(
            0f, dims.getY(ranges.uiMax, ranges),
            0f, dims.getY(ranges.uiMin, ranges),
            intArrayOf(config.highBpmColor, midUiColor, config.lowBpmColor),
            null, Shader.TileMode.CLAMP
        )

        // --- 3. RENDER FILL (Past Only) ---
        val fillGradient = LinearGradient(
            0f, dims.getY(ranges.uiMax, ranges),
            0f, dims.getY(ranges.uiMin, ranges),
            intArrayOf((config.highBpmColor and 0x00FFFFFF) or 0x55000000, 0x00000000),
            null, Shader.TileMode.CLAMP
        )
        paint.shader = ComposeShader(fillGradient, pastAlphaMask, PorterDuff.Mode.DST_IN)
        paint.style = Paint.Style.FILL
        canvas.drawPath(fill, paint)

        // --- 4. RENDER FUTURE LINE ---
        paint.shader = ComposeShader(colorGradient, futureAlphaMask, PorterDuff.Mode.DST_IN)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f * dims.scaleFactor
        canvas.drawPath(future, paint)

        // --- 5. RENDER PAST LINE ---
        paint.shader = ComposeShader(colorGradient, pastAlphaMask, PorterDuff.Mode.DST_IN)
        paint.strokeWidth = 7f * dims.scaleFactor
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(past, paint)
    }
    private fun drawGlowingHead(
        canvas: Canvas,
        dims: RenderingDimensions,
        ranges: BpmRanges,
        viewport: Viewport,
        record: BpmRecord,
        config: ImageExportConfig,
        paint: Paint
    ) {
        val currentBpm = getInterpolatedBpm(record.dataPoints, viewport.playhead) ?: 60.0
        val headColor = getBpmColor(currentBpm, ranges, config)

        val headX = dims.getX(viewport.playhead, viewport)
        val headY = dims.getY(currentBpm, ranges)

        val beatsPerSecond = currentBpm / 60.0
        val pulseFactor = (sin((viewport.playhead / 1000.0) * 2.0 * Math.PI * beatsPerSecond) * 0.5 + 0.5).toFloat()
        val pulseScale = 1.0f + (0.12f * pulseFactor)

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL

        // Outer Glow
        paint.setShadowLayer(22f * dims.scaleFactor * pulseScale, 0f, 0f, headColor)
        canvas.drawCircle(headX, headY, 8f * dims.scaleFactor * pulseScale, paint)

        // Core
        paint.clearShadowLayer()
        paint.color = headColor
        canvas.drawCircle(headX, headY, 6f * dims.scaleFactor, paint)

        // Inner Spark
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(headX, headY, 2.5f * dims.scaleFactor, paint)
    }

    private fun drawStatsHUD(
        canvas: Canvas,
        dims: RenderingDimensions,
        ranges: BpmRanges,
        viewport: Viewport,
        record: BpmRecord,
        config: ImageExportConfig,
        paint: Paint
    ) {
        val currentBpm = getInterpolatedBpm(record.dataPoints, viewport.playhead) ?: 60.0
        val hudContentColor = getBpmColor(currentBpm, ranges, config)

        // UPDATED: Center the pill near the right edge of the graph
        val pillMargin = 80f * dims.scaleFactor
        val centerX = dims.graphRight - pillMargin
        val hudTop = dims.graphTop + 10f * dims.scaleFactor

        // 3. Setup BPM Text
        val bpmText = currentBpm.roundToInt().toString()
        paint.reset()
        paint.isAntiAlias = true
        paint.isFakeBoldText = true
        paint.textSize = 72f * dims.scaleFactor

        val textWidth = paint.measureText(bpmText)
        val fontMetrics = paint.fontMetrics
        val textHeight = abs(fontMetrics.ascent) + fontMetrics.descent

        val heartSize = 48f * dims.scaleFactor
        val spacing = 16f * dims.scaleFactor
        val contentWidth = heartSize + spacing + textWidth

        // 4. Tighten top space: Move heart/bpm closer to top of pill
        // Reduced from +5f to +2f
        val bpmY = hudTop + textHeight + 2f * dims.scaleFactor
        val hCenterY = bpmY - (textHeight / 2f)

        // 5. Horizontal Pill Bounds: Use centerX to avoid side-clipping
        val horizontalPadding = 35f * dims.scaleFactor

        // Increase space between BPM and Time (from +32f to +40f)
        val timeY = bpmY + 40f * dims.scaleFactor
        val bpmPillBottom = timeY + 25f * dims.scaleFactor

        // Define Rect centered on our new centerX
        val hudRect = RectF(
            centerX - (contentWidth / 2f) - horizontalPadding,
            hudTop,
            centerX + (contentWidth / 2f) + horizontalPadding,
            bpmPillBottom
        )

        // Safety check to ensure it doesn't bleed off the right screen edge
        val edgeMargin = 20f * dims.scaleFactor
        if (hudRect.right > dims.drawAreaRight - edgeMargin) {
            hudRect.offset(-(hudRect.right - (dims.drawAreaRight - edgeMargin)), 0f)
        }

        // Draw Pill
        paint.color = 0xAA000000.toInt()
        val cornerRadius = 24f * dims.scaleFactor
        canvas.drawRoundRect(hudRect, cornerRadius, cornerRadius, paint)

        // 6. Pulse logic
        val beatsPerSecond = currentBpm / 60.0
        val pulseFactor = (sin((viewport.playhead / 1000.0) * 2.0 * Math.PI * beatsPerSecond) * 0.5 + 0.5).toFloat()
        val pulseScale = 1.0f + (0.12f * pulseFactor)

        // 7. Draw Pulsating Heart
        val contentStartX = hudRect.centerX() - (contentWidth / 2f)
        val hCenterX = contentStartX + (heartSize / 2f)

        paint.color = hudContentColor
        val s = (heartSize / 2f) * pulseScale
        val heartPath = Path().apply {
            moveTo(hCenterX, hCenterY + s * 0.5f)
            cubicTo(hCenterX - s, hCenterY - s, hCenterX - s * 1.5f, hCenterY + s * 0.5f, hCenterX, hCenterY + s * 1.5f)
            cubicTo(hCenterX + s * 1.5f, hCenterY + s * 0.5f, hCenterX + s, hCenterY - s, hCenterX, hCenterY + s * 0.5f)
        }
        canvas.drawPath(heartPath, paint)

        // 8. Draw BPM Digits
        paint.reset()
        paint.isAntiAlias = true
        paint.color = hudContentColor
        paint.textSize = 72f * dims.scaleFactor
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(bpmText, contentStartX + heartSize + spacing, bpmY, paint)

        // 9. Draw Time
        val absTime = record.metadata.startTime + viewport.playhead.toLong()
        val timeStr = StringFormatHelpers.getTimeString(absTime)
        paint.textSize = 24f * dims.scaleFactor
        paint.color = 0xCCFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(timeStr, hudRect.centerX(), timeY, paint)
    }

    // Helper Classes to clean up variable passing
    private class RenderingDimensions(canvas: Canvas, rect: RectF, config: ImageExportConfig) {
        val fullWidth = canvas.width.toFloat()
        val fullHeight = canvas.height.toFloat()
        val drawAreaLeft = rect.left * fullWidth
        val drawAreaTop = rect.top * fullHeight
        val drawAreaRight = rect.right * fullWidth
        val drawAreaBottom = rect.bottom * fullHeight
        val drawAreaWidth = drawAreaRight - drawAreaLeft
        val drawAreaHeight = drawAreaBottom - drawAreaTop
        val scaleFactor = (drawAreaWidth / 1920f).coerceAtLeast(0.5f)
        val outerRect = RectF(drawAreaLeft, drawAreaTop, drawAreaRight, drawAreaBottom)

        // UPDATED: Standardized padding
        val paddingLeft = (if (config.showLabels) 140f else 60f) * scaleFactor
        val paddingRight = 60f * scaleFactor // Fixed padding instead of a variable buffer
        val paddingTop = (if (config.showTitle) 120f else 60f) * scaleFactor
        val paddingBottom = (if (config.showLabels) 100f else 40f) * scaleFactor

        // UPDATED: Graph now takes full width minus standard padding
        val graphWidth = drawAreaWidth - paddingLeft - paddingRight
        val graphHeight = drawAreaHeight - paddingTop - paddingBottom

        val graphLeft = drawAreaLeft + paddingLeft
        val graphRight = graphLeft + graphWidth
        val graphTop = drawAreaTop + paddingTop
        val graphBottom = graphTop + graphHeight

        fun getY(bpm: Double, ranges: BpmRanges): Float = graphTop + (1f - (bpm - ranges.uiMin).toFloat() / ranges.uiRange.toFloat()) * graphHeight
        fun getX(ts: Double, vp: Viewport): Float = graphLeft + ((ts - vp.start) / vp.duration * graphWidth).toFloat()
    }

    private class BpmRanges(val snippetMin: Double, val snippetMax: Double, val uiMin: Double, val uiMax: Double, val title: String) {
        val snippetRange = (snippetMax - snippetMin).coerceAtLeast(1.0)
        val uiRange = (uiMax - uiMin).coerceAtLeast(1.0)
    }

    private class Viewport(val start: Double, val end: Double, val playhead: Double, val duration: Double, val lookahead: Long)

    private fun calculateRanges(record: BpmRecord, config: ImageExportConfig): BpmRanges {
        val snippetPoints = record.dataPoints.filter { it.timestamp >= config.startTimeMs && it.timestamp <= config.endTimeMs }
        val sMin = snippetPoints.minOfOrNull { it.bpm } ?: record.minDataPoint?.bpm ?: 60.0
        val sMax = snippetPoints.maxOfOrNull { it.bpm } ?: record.maxDataPoint?.bpm ?: 180.0
        val sRange = (sMax - sMin).coerceAtLeast(1.0)

        val minSpread = 20.0
        val center = (sMax + sMin) / 2.0
        val adjMin = if (sRange < minSpread) center - 10.0 else sMin
        val adjMax = if (sRange < minSpread) center + 10.0 else sMax

        return BpmRanges(sMin, sMax, adjMin - (minSpread * 0.1), adjMax + (minSpread * 0.1), record.metadata.title)
    }

    private fun calculateViewport(currentTime: Double?, windowSize: Long?, config: ImageExportConfig, record: BpmRecord): Viewport {
        // 1. Determine the playhead position (50% through the selected snippet if currentTime is null)
        val playhead = currentTime ?: ((config.startTimeMs + config.endTimeMs) / 2.0)

        val start: Double
        val end: Double

        if (windowSize != null) {
            // 2. If we have a fixed window size (e.g. 30 seconds),
            // center the playhead by splitting the window 50/50
            start = playhead - (windowSize / 2.0)
            end = playhead + (windowSize / 2.0)
        } else {
            // 3. If no window size is provided (static image of the whole snippet),
            // the viewport IS the snippet range.
            start = config.startTimeMs.toDouble()
            end = config.endTimeMs.toDouble()
        }

        val duration = (end - start).coerceAtLeast(1.0)
        val lookahead = if (windowSize != null) (windowSize * 0.5).toLong() else 5000L

        return Viewport(start, end, playhead, duration, lookahead)
    }

    private fun getBpmColor(bpm: Double, ranges: BpmRanges, config: ImageExportConfig): Int {
        val fraction = ((bpm - ranges.uiMin) / ranges.uiRange).coerceIn(0.0, 1.0).toFloat()
        val hsvStart = FloatArray(3); val hsvEnd = FloatArray(3)
        android.graphics.Color.colorToHSV(config.lowBpmColor, hsvStart)
        android.graphics.Color.colorToHSV(config.highBpmColor, hsvEnd)
        var sH = hsvStart[0]; var eH = hsvEnd[0]
        if (eH < sH) eH += 360f
        return android.graphics.Color.HSVToColor(floatArrayOf((sH + (eH - sH) * fraction) % 360f,
            hsvStart[1] + (hsvEnd[1] - hsvStart[1]) * fraction, hsvStart[2] + (hsvEnd[2] - hsvStart[2]) * fraction))
    }
}
