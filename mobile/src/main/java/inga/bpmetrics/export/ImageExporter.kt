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
import inga.bpmetrics.library.RecordNameFormatter
import inga.bpmetrics.library.PersonColors
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
        val showLabels: Boolean = true,
        val labelsColor: Int = inga.bpmetrics.ui.theme.BpmPalette.ON_SURFACE,
        val showGrid: Boolean = true,
        val gridColor: Int = inga.bpmetrics.ui.theme.BpmPalette.GRID,
        val lowBpmColor: Int = inga.bpmetrics.ui.theme.BpmPalette.LOW,
        val highBpmColor: Int = inga.bpmetrics.ui.theme.BpmPalette.HIGH,
        val showTitle: Boolean = true,
        val showCurrentStats: Boolean = true,
        val headerXPercent: Float = 0.85f,
        val futureOpacity: Float = 0.65f,
        val timeZoneId: String = java.time.ZoneId.systemDefault().id,
        val customRecordColors: Map<Long, Int> = emptyMap(),
        val alignByElapsedTime: Boolean = true,
        /**
         * Heading drawn on the graph.
         *
         * A single record uses its own title. Several have no title of their own, so exporting a
         * named analysis passes its name here — "Subtronics 2026" rather than the generic label
         * every multi-watch export would otherwise carry. Null keeps that default.
         */
        val graphTitle: String? = null,

        /** See [ExportPreset.showWordmark]. Off by default; the preset carries the choice. */
        val showWordmark: Boolean = false,
        val wordmarkCorner: WordmarkCorner = WordmarkCorner.BOTTOM_RIGHT,
        val wordmarkOpacity: Int = 55
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
        
        val firstPoint = points.first()
        if (timestampMs < firstPoint.timestamp) {
            return if (firstPoint.timestamp - timestampMs <= BpmRecord.GAP_THRESHOLD_MS) firstPoint.bpm else null
        }
        
        val lastPoint = points.last()
        if (timestampMs > lastPoint.timestamp) {
            return if (timestampMs - lastPoint.timestamp <= BpmRecord.GAP_THRESHOLD_MS) lastPoint.bpm else null
        }
        
        // Binary search rather than a scan: this runs for every wearer on every frame, and the
        // ranked pills call it many times more per frame to place their slide. The bounds checks
        // above already rely on the points being ordered by timestamp.
        var low = 0
        var high = points.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (points[mid].timestamp >= timestampMs) high = mid else low = mid + 1
        }
        val p2Index = low
        if (p2Index == 0) return points.first().bpm
        
        val p1 = points[p2Index - 1]
        val p2 = points[p2Index]
        
        val t1 = p1.timestamp.toDouble()
        val t2 = p2.timestamp.toDouble()
        if (t2 - t1 > BpmRecord.GAP_THRESHOLD_MS) {
            return null
        }
        
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

        // 2. Setup Ranges (Using expanded snippet bounds for stability)
        val ranges = calculateRanges(record, config, windowSizeMs)

        // 3. Setup Viewport (Time window)
        val viewport = calculateViewport(currentTimeMs, windowSizeMs, config, record)

        // 4. Draw Background with Rounded Corners
        val paint = Paint().apply { isAntiAlias = true; fontFeatureSettings = inga.bpmetrics.ui.theme.MetricNumerals }
        drawContainer(canvas, dims, config, paint)

        // 5. Draw Grid and Axes (Clipped to Graph Area)
        drawGridAndAxes(canvas, dims, ranges, config, paint)

        // 6. Draw Data Curve (Strictly clipped to 85% width to prevent HUD occlusion)
        drawDataCurve(canvas, dims, ranges, viewport, record, config, paint)

        // 7. Draw Glowing Head (Clipped to Graph Area)
        drawGlowingHead(canvas, dims, ranges, viewport, record, config, paint)

        // 8. Draw HUD (In the 15% Sidebar)
        if (config.showCurrentStats) {
            drawStatsHUD(canvas, dims, ranges, viewport, record, config, paint)
        }

        drawWordmark(canvas, dims, config, paint)

        canvas.restore() // Final restore from drawContainer's save
    }

    /**
     * Signs the graph panel, not the frame.
     *
     * In the corner of the panel rather than the corner of the canvas, because the panel is what
     * anyone looking at the export is already looking at — a mark in the corner of the video is in
     * the part of the picture nobody reads. Drawn before the container's restore, so the panel's
     * rounded clip holds it: it cannot spill onto the footage however the preset is framed.
     *
     * Inside the panel's padding rather than over the plot, so it sits beside the curve instead of
     * across it. Which corner is busiest depends on the preset — the title occupies the top and the
     * time labels the bottom — which is why the corner is a setting rather than a decision made
     * here.
     */
    private fun drawWordmark(
        canvas: Canvas,
        dims: RenderingDimensions,
        config: ImageExportConfig,
        paint: Paint
    ) {
        if (!config.showWordmark) return
        Wordmark.draw(
            canvas = canvas,
            bounds = dims.outerRect,
            corner = config.wordmarkCorner,
            opacityPercent = config.wordmarkOpacity,
            color = config.labelsColor,
            // The panel's own scale, not the canvas's. A preset that shrinks the graph to a quarter
            // of the frame should get a mark a quarter the size — one scaled off the canvas would
            // sit in a small panel looking like a headline.
            scale = dims.scaleFactor,
            paint = paint
        )
    }

    private fun drawContainer(canvas: Canvas, dims: RenderingDimensions, config: ImageExportConfig, paint: Paint) {
        val outerCornerRadius = 24f * dims.scaleFactor
        val outerClipPath = Path().apply {
            addRoundRect(dims.outerRect, outerCornerRadius, outerCornerRadius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(outerClipPath)

        // The app's surface, not black. This only shows on an opaque canvas — a video overlay is
        // transparent and keeps the footage — but where it does show, a still exported from
        // BPMetrics should be the same near-black the app itself is, rather than a flat #000 that
        // belongs to no palette.
        if (canvas.isOpaque) canvas.drawColor(inga.bpmetrics.ui.theme.BpmPalette.SURFACE)
        val bgAlpha = (config.backgroundOpacity * 255 / 100).coerceIn(0, 255)
        if (bgAlpha > 0) {
            paint.resetForExport()
            paint.color = inga.bpmetrics.ui.theme.BpmPalette.SURFACE
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
                paint.resetForExport()
                paint.color = config.gridColor
                paint.strokeWidth = 2f
                canvas.drawLine(dims.graphLeft, y, dims.graphRight, y, paint)
            }
            if (config.showLabels) {
                paint.resetForExport()
                paint.color = config.labelsColor
                paint.textSize = 28f * dims.scaleFactor
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(bpm.roundToInt().toString(), dims.graphLeft - 15f * dims.scaleFactor, y + 10f * dims.scaleFactor, paint)
            }
        }

        if (config.showTitle) {
            paint.resetForExport()
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
                val currentBpm = getInterpolatedBpm(record.dataPoints, viewport.playhead)

                val playheadX = dims.getX(viewport.playhead, viewport)
                val midY = dims.getY(currentBpm ?: 60.0, ranges)

                // 1. Continuous Fill Path
                val firstPoint = visiblePoints.first()
                val firstPointX = dims.getX(firstPoint.timestamp.toDouble(), viewport)
                val startX = maxOf(dims.graphLeft, firstPointX)
                val startY = if (firstPointX > dims.graphLeft) {
                    dims.getY(firstPoint.bpm, ranges)
                } else {
                    dims.getY(startBpm, ranges)
                }

                val fillPath = Path()
                fillPath.moveTo(startX, dims.graphBottom)
                fillPath.lineTo(startX, startY)

                var lastX = startX
                var lastY = startY

                visiblePoints.filter { it.timestamp <= viewport.playhead }.forEach { p ->
                    val x = dims.getX(p.timestamp.toDouble(), viewport)
                    val y = dims.getY(p.bpm, ranges)
                    if (x > startX) {
                        val cx = (lastX + x) / 2f
                        fillPath.cubicTo(cx, lastY, cx, y, x, y)
                        lastX = x
                        lastY = y
                    }
                }
                
                val cxMid = (lastX + playheadX) / 2f
                fillPath.cubicTo(cxMid, lastY, cxMid, midY, playheadX, midY)
                fillPath.lineTo(playheadX, dims.graphBottom)
                fillPath.close()

                // 2. Solid & Dashed past and future paths
                val pastSolidPath = Path()
                val pastDashedPath = Path()
                val futureSolidPath = Path()
                val futureDashedPath = Path()

                // Build Past Paths
                pastSolidPath.moveTo(startX, startY)
                pastDashedPath.moveTo(startX, startY)
                lastX = startX
                lastY = startY

                val pastPoints = visiblePoints.filter { it.timestamp <= viewport.playhead }
                pastPoints.forEachIndexed { index, p ->
                    val x = dims.getX(p.timestamp.toDouble(), viewport)
                    val y = dims.getY(p.bpm, ranges)
                    if (x > startX) {
                        val cx = (lastX + x) / 2f
                        
                        val prevTime = if (index == 0) viewport.start else pastPoints[index - 1].timestamp.toDouble()
                        val isGap = (p.timestamp - prevTime) > BpmRecord.GAP_THRESHOLD_MS

                        if (isGap) {
                            pastDashedPath.moveTo(lastX, lastY)
                            pastDashedPath.cubicTo(cx, lastY, cx, y, x, y)
                            pastSolidPath.moveTo(x, y)
                        } else {
                            pastSolidPath.cubicTo(cx, lastY, cx, y, x, y)
                            pastDashedPath.moveTo(x, y)
                        }
                        lastX = x
                        lastY = y
                    }
                }

                // Connect past to playhead
                val isGapToPlayhead = pastPoints.isNotEmpty() && (viewport.playhead - pastPoints.last().timestamp) > BpmRecord.GAP_THRESHOLD_MS
                if (isGapToPlayhead) {
                    pastDashedPath.moveTo(lastX, lastY)
                    pastDashedPath.cubicTo(cxMid, lastY, cxMid, midY, playheadX, midY)
                    pastSolidPath.moveTo(playheadX, midY)
                } else {
                    pastSolidPath.cubicTo(cxMid, lastY, cxMid, midY, playheadX, midY)
                    pastDashedPath.moveTo(playheadX, midY)
                }

                // Build Future Paths
                futureSolidPath.moveTo(playheadX, midY)
                futureDashedPath.moveTo(playheadX, midY)
                lastX = playheadX
                lastY = midY

                val futurePoints = visiblePoints.filter { it.timestamp > viewport.playhead }
                futurePoints.forEachIndexed { index, p ->
                    val x = dims.getX(p.timestamp.toDouble(), viewport)
                    val y = dims.getY(p.bpm, ranges)
                    val cx = (lastX + x) / 2f
                    
                    val prevTime = if (index == 0) viewport.playhead else futurePoints[index - 1].timestamp.toDouble()
                    val isGap = (p.timestamp - prevTime) > BpmRecord.GAP_THRESHOLD_MS

                    if (isGap) {
                        futureDashedPath.moveTo(lastX, lastY)
                        futureDashedPath.cubicTo(cx, lastY, cx, y, x, y)
                        futureSolidPath.moveTo(x, y)
                    } else {
                        futureSolidPath.cubicTo(cx, lastY, cx, y, x, y)
                        futureDashedPath.moveTo(x, y)
                    }
                    lastX = x
                    lastY = y
                }

                // Anchor future to right edge
                val lastPoint = visiblePoints.last()
                val lastPointX = dims.getX(lastPoint.timestamp.toDouble(), viewport)
                val endX = minOf(dims.graphRight, lastPointX)
                val finalY = if (lastPointX < dims.graphRight) {
                    dims.getY(lastPoint.bpm, ranges)
                } else {
                    dims.getY(endBpm, ranges)
                }

                if (endX > lastX) {
                    val cx = (lastX + endX) / 2f
                    val isGapToEnd = (endX - lastPointX) > BpmRecord.GAP_THRESHOLD_MS
                    if (isGapToEnd) {
                        futureDashedPath.moveTo(lastX, lastY)
                        futureDashedPath.cubicTo(cx, lastY, cx, finalY, endX, finalY)
                    } else {
                        futureSolidPath.cubicTo(cx, lastY, cx, finalY, endX, finalY)
                    }
                }

                renderShaders(
                    canvas = this,
                    dims = dims,
                    ranges = ranges,
                    viewport = viewport,
                    config = config,
                    fill = fillPath,
                    pastSolid = pastSolidPath,
                    pastDashed = pastDashedPath,
                    futureSolid = futureSolidPath,
                    futureDashed = futureDashedPath,
                    playheadX = playheadX,
                    firstPointX = startX,
                    lastPointX = endX
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
        pastSolid: Path,
        pastDashed: Path,
        futureSolid: Path,
        futureDashed: Path,
        playheadX: Float,
        firstPointX: Float,
        lastPointX: Float
    ) {
        val paint = Paint().apply { isAntiAlias = true; fontFeatureSettings = inga.bpmetrics.ui.theme.MetricNumerals }

        // --- 1. DEFINE HORIZONTAL ALPHA MASKS ---
        val pastAlphaMask = LinearGradient(
            firstPointX, 0f, playheadX, 0f,
            intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
            null, Shader.TileMode.CLAMP
        )

        val futureAlpha = (config.futureOpacity * 255).toInt().coerceIn(0, 255)
        val futureAlphaColor = android.graphics.Color.argb(futureAlpha, 0, 0, 0)

        val futureAlphaMask = LinearGradient(
            playheadX, 0f, lastPointX, 0f,
            intArrayOf(
                futureAlphaColor,
                futureAlphaColor,
                android.graphics.Color.TRANSPARENT
            ),
            floatArrayOf(0.0f, 0.6f, 1.0f),
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
        
        // Draw Solid Future
        paint.strokeWidth = 4f * dims.scaleFactor
        paint.pathEffect = null
        canvas.drawPath(futureSolid, paint)
        
        // Draw Dashed Future
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f * dims.scaleFactor, 15f * dims.scaleFactor), 0f)
        canvas.drawPath(futureDashed, paint)

        // --- 5. RENDER PAST LINE ---
        paint.shader = ComposeShader(colorGradient, pastAlphaMask, PorterDuff.Mode.DST_IN)
        paint.strokeWidth = 7f * dims.scaleFactor
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        
        // Draw Solid Past
        paint.pathEffect = null
        canvas.drawPath(pastSolid, paint)
        
        // Draw Dashed Past
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f * dims.scaleFactor, 15f * dims.scaleFactor), 0f)
        canvas.drawPath(pastDashed, paint)
    }
    /**
     * The size multiplier for a heart beating at [bpm], sampled at [playheadMs].
     *
     * Returns 1.0 (no pulse) when there is no reading, so a paused or finished session shows a
     * still heart rather than one stuck mid-beat.
     */
    private fun pulseScaleFor(bpm: Double?, playheadMs: Double): Float {
        if (bpm == null || bpm <= 0.0) return 1.0f
        val beatsPerSecond = bpm / 60.0
        val phase = (sin((playheadMs / 1000.0) * 2.0 * Math.PI * beatsPerSecond) * 0.5 + 0.5).toFloat()
        return 1.0f + (0.12f * phase)
    }

    /**
     * Draws a heart of [radius] centred near ([centerX], [centerY]) using the paint's current color.
     */
    private fun drawHeart(canvas: Canvas, centerX: Float, centerY: Float, radius: Float, paint: Paint) {
        val s = radius
        val heartPath = Path().apply {
            moveTo(centerX, centerY + s * 0.5f)
            cubicTo(centerX - s, centerY - s, centerX - s * 1.5f, centerY + s * 0.5f, centerX, centerY + s * 1.5f)
            cubicTo(centerX + s * 1.5f, centerY + s * 0.5f, centerX + s, centerY - s, centerX, centerY + s * 0.5f)
        }
        canvas.drawPath(heartPath, paint)
    }

    /**
     * The colour a record's curve, head and pill all share.
     *
     * The caller supplies it, resolved from the wearer's profile, so a person looks the same in a
     * video as they do everywhere else in the app. The fallback is only for a recording nobody is
     * assigned to.
     */
    private fun colorForRecord(record: BpmRecord, index: Int, config: ImageExportConfig): Int =
        config.customRecordColors[record.metadata.recordId]
            ?: PersonColors.defaultFor(index)

    /**
     * Shortens [text] with a trailing ellipsis until it fits [maxWidth] at the paint's current
     * text settings. Returns [text] untouched when it already fits.
     */
    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text

        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + "…") > maxWidth) end--
        return if (end <= 0) "" else text.take(end) + "…"
    }

    /**
     * The name to show for whoever wore the watch, falling back to the device then the title.
     *
     * The title is only used when it says something. A pill reading "Untitled 4" over someone's
     * heart rate is worse than one reading "Unknown", and it goes out in a video.
     */
    private fun wearerLabelOf(record: BpmRecord): String =
        record.metadata.wearerName.takeIf { it.isNotBlank() }
            ?: record.metadata.deviceId.takeIf { it.isNotBlank() }
            ?: record.metadata.title.takeIf { !RecordNameFormatter.isPlaceholder(it) }
            ?: "Unknown"

    private fun drawGlowingHead(
        canvas: Canvas,
        dims: RenderingDimensions,
        ranges: BpmRanges,
        viewport: Viewport,
        record: BpmRecord,
        config: ImageExportConfig,
        paint: Paint
    ) {
        // No reading means this session is not running at the playhead, so it has no head to mark.
        val currentBpm = getInterpolatedBpm(record.dataPoints, viewport.playhead) ?: return
        val headColor = getBpmColor(currentBpm, ranges, config)

        val headX = dims.getX(viewport.playhead, viewport)
        val headY = dims.getY(currentBpm, ranges)

        val pulseScale = pulseScaleFor(currentBpm, viewport.playhead)

        paint.resetForExport()
        paint.style = Paint.Style.FILL

        // Outer Glow
        paint.setShadowLayer(22f * dims.scaleFactor * pulseScale, 0f, 0f, headColor)
        canvas.drawCircle(headX, headY, 8f * dims.scaleFactor * pulseScale, paint)

        // Core
        paint.clearShadowLayer()
        paint.color = headColor
        canvas.drawCircle(headX, headY, 6f * dims.scaleFactor, paint)

        // Inner Spark. Left white deliberately where the surrounding text is not: this is a
        // specular highlight on the head of the curve, not a label, and tinting it to the theme
        // would flatten the one thing on the graph that is meant to look lit.
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
        val currentBpm = getInterpolatedBpm(record.dataPoints, viewport.playhead)
        val hudContentColor = if (currentBpm != null) getBpmColor(currentBpm, ranges, config) else config.labelsColor

        // UPDATED: Center the pill near the right edge of the graph
        val pillMargin = 80f * dims.scaleFactor
        val centerX = dims.graphRight - pillMargin
        val hudTop = dims.graphTop + 10f * dims.scaleFactor

        // 3. Setup BPM Text
        val bpmText = currentBpm?.roundToInt()?.toString() ?: "--"
        paint.resetForExport()
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
        
        // NEW: Calculate Elapsed Time
        val elapsedMs = viewport.playhead.toLong()
        val elapsedStr = StringFormatHelpers.getDurationString(elapsedMs)
        val elapsedY = timeY + 32f * dims.scaleFactor

        val bpmPillBottom = elapsedY + 25f * dims.scaleFactor

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
        val pulseScale = pulseScaleFor(currentBpm, viewport.playhead)

        // 7. Draw Pulsating Heart
        val contentStartX = hudRect.centerX() - (contentWidth / 2f)
        val hCenterX = contentStartX + (heartSize / 2f)

        paint.color = hudContentColor
        drawHeart(canvas, hCenterX, hCenterY, (heartSize / 2f) * pulseScale, paint)

        // 8. Draw BPM Digits
        paint.resetForExport()
        paint.color = hudContentColor
        paint.textSize = 72f * dims.scaleFactor
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(bpmText, contentStartX + heartSize + spacing, bpmY, paint)

        // 9. Draw Time
        val absTime = record.metadata.startTime + viewport.playhead.toLong()
        val timeStr = StringFormatHelpers.getTimeString(absTime, java.time.ZoneId.of(config.timeZoneId))
        paint.textSize = 24f * dims.scaleFactor
        paint.color = 0xCCFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(timeStr, hudRect.centerX(), timeY, paint)
        
        // 10. Draw Elapsed Recording Time
        canvas.drawText(elapsedStr, hudRect.centerX(), elapsedY, paint)
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
        val refWidth = if (fullHeight > fullWidth) 1080f else 1920f
        val scaleFactor = (drawAreaWidth / refWidth).coerceAtLeast(0.5f * (fullWidth / refWidth))
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

    private fun calculateRanges(record: BpmRecord, config: ImageExportConfig, windowSizeMs: Long? = null): BpmRanges {
        // Expand the search range by the window size (or 5s default) to capture everything that will be visible
        val margin = windowSizeMs ?: 5000L
        val searchStart = config.startTimeMs - margin
        val searchEnd = config.endTimeMs + margin

        val snippetPoints = record.dataPoints.filter { it.timestamp >= searchStart && it.timestamp <= searchEnd }
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
        // The same walk the on-screen chart uses, against this preset's endpoints — which default
        // to the app's own low and high.
        return inga.bpmetrics.ui.theme.BpmRamp.blend(
            config.lowBpmColor,
            config.highBpmColor,
            fraction
        )
    }

    // The multi-watch palette used to live here, as pairs of which only the first was ever read,
    // beside a second and longer palette in the export dialog that had drifted away from it.
    // Both are now inga.bpmetrics.library.PersonColors.PALETTE, which is also what a new profile
    // is coloured from — so the default a person starts with is the one the exporter would pick.

    /**
     * The shared time axis that a set of records is drawn against.
     *
     * @property originWallClockMs The wall clock instant that timeline position 0 represents.
     * Anything aligned against these records — a video in particular — must be positioned
     * relative to this instant.
     * @property durationMs Length of the timeline, measured from position 0.
     */
    data class Timeline(val originWallClockMs: Long, val durationMs: Long)

    /**
     * Records whose data point timestamps have been rewritten onto a shared [timeline].
     */
    data class AlignedRecords(val records: List<BpmRecord>, val timeline: Timeline)

    /**
     * Computes the shared time axis for [records] without rewriting any data points.
     *
     * Cheap enough to call from the UI; use [alignRecords] when the shifted points are needed.
     *
     * Two modes:
     * - **Elapsed time** ([alignByElapsedTime] true): every record is treated as starting at
     *   0:00, overlaying sessions that happened at different times so their shapes can be
     *   compared. The origin stays the first record's start, preserving how video sync behaved
     *   before clock alignment existed.
     * - **Clock time** ([alignByElapsedTime] false): the origin is the earliest session start,
     *   and each record sits at its real distance from it.
     */
    fun timelineFor(records: List<BpmRecord>, alignByElapsedTime: Boolean): Timeline {
        if (records.isEmpty()) return Timeline(0L, 0L)

        return if (alignByElapsedTime) {
            val duration = records.maxOf { rec -> lastTimestampOf(rec) - firstTimestampOf(rec) }
            Timeline(records.first().metadata.startTime, duration.coerceAtLeast(0L))
        } else {
            val origin = records.minOf { it.metadata.startTime }
            val duration = records.maxOf { rec ->
                (rec.metadata.startTime - origin) + lastTimestampOf(rec)
            }
            Timeline(origin, duration.coerceAtLeast(0L))
        }
    }

    /**
     * Places several records on one timeline so a single graph can show them together.
     *
     * In clock-time mode each record is offset by how long after [Timeline.originWallClockMs] it
     * actually started, so every record lands exactly where it would if it had been exported on
     * its own. That is what lets one video stay in sync with all of them at once.
     *
     * See [timelineFor] for the meaning of [alignByElapsedTime].
     */
    fun alignRecords(records: List<BpmRecord>, alignByElapsedTime: Boolean): AlignedRecords {
        val timeline = timelineFor(records, alignByElapsedTime)
        if (records.isEmpty()) return AlignedRecords(emptyList(), timeline)

        val shifted = records.map { rec ->
            val offset = if (alignByElapsedTime) {
                -firstTimestampOf(rec)
            } else {
                rec.metadata.startTime - timeline.originWallClockMs
            }

            if (offset == 0L) rec
            else rec.copy(dataPoints = rec.dataPoints.map { it.copy(timestamp = it.timestamp + offset) })
        }

        return AlignedRecords(shifted, timeline)
    }

    private fun firstTimestampOf(record: BpmRecord): Long =
        record.dataPoints.firstOrNull()?.timestamp ?: 0L

    private fun lastTimestampOf(record: BpmRecord): Long =
        record.dataPoints.lastOrNull()?.timestamp ?: record.metadata.durationMs

    /**
     * Renders multiple BPM records on a single canvas with distinct multi-color curves and a legend.
     */
    fun renderMultiRecordsOnCanvas(
        canvas: Canvas,
        records: List<BpmRecord>,
        config: ImageExportConfig,
        currentTimeMs: Double? = null,
        windowSizeMs: Long? = null,
        graphRect: RectF = RectF(0f, 0f, 1f, 1f)
    ) {
        if (records.isEmpty()) return
        if (records.size == 1) {
            renderOnCanvas(canvas, records.first(), config, currentTimeMs, windowSizeMs, graphRect)
            return
        }

        renderAlignedRecordsOnCanvas(
            canvas = canvas,
            aligned = alignRecords(records, config.alignByElapsedTime),
            config = config,
            currentTimeMs = currentTimeMs,
            windowSizeMs = windowSizeMs,
            graphRect = graphRect
        )
    }

    /**
     * Renders records that have already been placed on a shared timeline by [alignRecords].
     *
     * Video export aligns once and then calls this for every frame — re-aligning per frame would
     * copy every data point of every record thirty times a second.
     */
    fun renderAlignedRecordsOnCanvas(
        canvas: Canvas,
        aligned: AlignedRecords,
        config: ImageExportConfig,
        currentTimeMs: Double? = null,
        windowSizeMs: Long? = null,
        graphRect: RectF = RectF(0f, 0f, 1f, 1f)
    ) {
        val processedRecords = aligned.records
        if (processedRecords.isEmpty()) return

        val primaryRecord = processedRecords.first()
        val dims = RenderingDimensions(canvas, graphRect, config)
        val paint = Paint().apply { isAntiAlias = true; fontFeatureSettings = inga.bpmetrics.ui.theme.MetricNumerals }

        val allPoints = processedRecords.flatMap { it.dataPoints }
        if (allPoints.isEmpty()) return
        val minBpm = (allPoints.minOf { it.bpm } - 5.0).coerceAtLeast(30.0)
        val maxBpm = (allPoints.maxOf { it.bpm } + 5.0).coerceAtMost(220.0)
        val ranges = BpmRanges(
            minBpm, maxBpm, minBpm, maxBpm,
            config.graphTitle?.takeIf { it.isNotBlank() } ?: "Multi-Watch Session"
        )

        // Clock-aligned exports carry a crop chosen against the shared timeline, so it is honoured
        // as-is. Elapsed mode has no meaningful shared crop, and a caller may supply none at all,
        // so both fall back to spanning the whole timeline.
        val hasUsableRange = !config.alignByElapsedTime && config.endTimeMs > config.startTimeMs
        val multiConfig = if (hasUsableRange) {
            config
        } else {
            config.copy(startTimeMs = 0L, endTimeMs = aligned.timeline.durationMs)
        }

        val viewport = calculateViewport(currentTimeMs, windowSizeMs, multiConfig, primaryRecord)

        drawContainer(canvas, dims, config, paint)
        drawGridAndAxes(canvas, dims, ranges, multiConfig, paint)

        // A flat per-person colour is what lets several curves be told apart. With one curve there
        // is nothing to tell it apart *from*, and flattening it throws away the blue-to-red
        // gradient — the one thing the line's colour could still be saying. So a lone recording
        // keeps the gradient, and the person's colour stays an accent elsewhere on the frame.
        //
        // This is also where the preview and the render disagreed: the export drops to the
        // single-record path for one recording and gets the gradient, while the preview always
        // comes through here. Deciding it here means both agree whichever path they took.
        val recordConfigs = if (processedRecords.size == 1) {
            listOf(multiConfig)
        } else {
            processedRecords.mapIndexed { index, rec ->
                val assignedColor = colorForRecord(rec, index, config)
                multiConfig.copy(lowBpmColor = assignedColor, highBpmColor = assignedColor)
            }
        }

        processedRecords.forEachIndexed { index, rec ->
            drawDataCurve(canvas, dims, ranges, viewport, rec, recordConfigs[index], paint)
        }

        // Heads go on after every curve, so one wearer's line can never bury another's marker.
        processedRecords.forEachIndexed { index, rec ->
            drawGlowingHead(canvas, dims, ranges, viewport, rec, recordConfigs[index], paint)
        }

        if (config.showCurrentStats) {
            // The pills name each wearer and colour-match their curve, so they replace the legend.
            drawMultiStatsHUD(
                canvas = canvas,
                dims = dims,
                viewport = viewport,
                records = processedRecords,
                config = config,
                timelineOriginMs = aligned.timeline.originWallClockMs,
                paint = paint
            )
        } else {
            drawMultiLegend(canvas, dims, processedRecords, config, paint)
        }

        drawWordmark(canvas, dims, config, paint)

        canvas.restore()
    }

    /**
     * Draws a live BPM pill per wearer, stacked at the top right of the graph.
     *
     * Single-record export shows one large readout. With several wearers the same information is
     * repeated compactly — each heart in that wearer's line colour, beating at their own rate —
     * so a viewer can follow one person's heart rate without tracing their curve.
     *
     * Pills are ordered by current heart rate, fastest at the top, and slide between places as
     * one wearer overtakes another — the order is itself part of the story the video tells.
     *
     * A wearer whose session is not running at the playhead shows "--" and sinks to the bottom
     * rather than vanishing, so the stack keeps a stable height for the whole video. Digits are
     * right-aligned in a fixed-width slot so the pills do not jitter as the numbers change.
     *
     * Clock and elapsed time belong to the shared timeline, so they are drawn once underneath
     * rather than repeated in every pill.
     */
    private fun drawMultiStatsHUD(
        canvas: Canvas,
        dims: RenderingDimensions,
        viewport: Viewport,
        records: List<BpmRecord>,
        config: ImageExportConfig,
        timelineOriginMs: Long,
        paint: Paint
    ) {
        val scale = dims.scaleFactor
        val edgeMargin = 20f * scale
        val rowGap = 8f * scale

        // Pills are sized to fill the height rather than fixed small, so a two-wearer session
        // gets large readable numbers instead of the same cramped rows as a six-wearer one. The
        // ceiling stops a single pill becoming a slab; the floor keeps a crowded session legible
        // even if the column then runs slightly past the graph.
        val timeRowHeight = 96f * scale
        val available = (dims.graphBottom - dims.graphTop) - (edgeMargin * 2) - timeRowHeight

        // Height is also bounded by width. Everything inside a pill scales with its row height,
        // so on a tall narrow graph the heart and digits would eat the whole pill and leave the
        // name ellipsized away to nothing. This only binds when the graph is narrow.
        val widthCap = (dims.graphRight - dims.graphLeft) * 0.14f

        val rowHeight = ((available - rowGap * (records.size - 1)) / records.size)
            .coerceIn(64f * scale, minOf(170f * scale, widthCap))

        // Everything else follows the row height, so the proportions hold at any size.
        val digitSize = rowHeight * 0.50f
        val labelSize = rowHeight * 0.25f
        val heartSize = rowHeight * 0.42f
        val gap = rowHeight * 0.14f
        val padH = rowHeight * 0.22f
        val padV = rowHeight * 0.14f
        val cornerRadius = rowHeight * 0.24f

        paint.resetForExport()
        paint.isFakeBoldText = true
        paint.textSize = digitSize
        // Reserve the width of the widest plausible reading so pills share one width.
        val digitWidth = paint.measureText("888")
        val digitMetrics = paint.fontMetrics
        val digitBaselineOffset = (abs(digitMetrics.ascent) - digitMetrics.descent) / 2f

        paint.isFakeBoldText = false
        paint.textSize = labelSize
        val labelMetrics = paint.fontMetrics
        val labelBaselineOffset = (abs(labelMetrics.ascent) - labelMetrics.descent) / 2f

        // Never let a long wearer name — or a fallback to a long record title — grow the pills
        // across the graph. Half the plot width is the most the HUD may claim.
        val maxLabelWidth = ((dims.graphRight - dims.graphLeft) * 0.5f) -
                (padH * 2 + heartSize + gap + digitWidth + gap)
        val labels = records.map { ellipsize(wearerLabelOf(it), maxLabelWidth, paint) }
        val labelWidth = labels.maxOf { paint.measureText(it) }

        val contentWidth = heartSize + gap + digitWidth + gap + labelWidth
        val pillWidth = contentWidth + padH * 2

        val pillRight = minOf(dims.graphRight, dims.drawAreaRight - edgeMargin)
        val pillLeft = pillRight - pillWidth
        val blockTop = dims.graphTop + edgeMargin
        val slotPitch = rowHeight + rowGap

        val slots = animatedRankSlots(records, viewport.playhead)

        // Pills mid-slide are drawn last so one passes *over* the pill it is overtaking. Two
        // opaque pills swapping places must cross, and without an order the overlap looks torn.
        val drawOrder = records.indices.sortedBy { abs(slots[it] - slots[it].roundToInt()) }

        drawOrder.forEach { index ->
            val record = records[index]
            val bpm = getInterpolatedBpm(record.dataPoints, viewport.playhead)
            val recordColor = colorForRecord(record, index, config)
            val rowTop = blockTop + slots[index] * slotPitch
            val rect = RectF(pillLeft, rowTop, pillRight, rowTop + rowHeight)

            paint.resetForExport()
            // Denser than the single-record HUD: these sit over whatever the video is showing,
            // and a busy frame behind thin digits is what made them hard to read.
            paint.color = 0xD9000000.toInt()
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            // A rim in the wearer's own colour, which both ties the pill to its curve more firmly
            // than the heart alone and keeps the edge legible while two pills overlap in a swap.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rowHeight * 0.035f
            paint.color = (recordColor and 0x00FFFFFF) or 0xCC000000.toInt()
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            val contentLeft = pillLeft + padH
            val centerY = rect.centerY()

            paint.style = Paint.Style.FILL
            paint.color = if (bpm != null) recordColor else config.labelsColor
            drawHeart(
                canvas,
                contentLeft + heartSize / 2f,
                centerY - heartSize * 0.25f,
                (heartSize / 2f) * pulseScaleFor(bpm, viewport.playhead),
                paint
            )

            paint.textSize = digitSize
            paint.isFakeBoldText = true
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                bpm?.roundToInt()?.toString() ?: "--",
                contentLeft + heartSize + gap + digitWidth,
                centerY + digitBaselineOffset,
                paint
            )

            paint.textSize = labelSize
            paint.isFakeBoldText = true
            paint.color = config.labelsColor
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                labels[index],
                contentLeft + heartSize + gap + digitWidth + gap,
                centerY + labelBaselineOffset,
                paint
            )
        }

        // Anchored to the number of wearers rather than to wherever the loop finished, so the
        // clock holds still while the pills above it trade places. Uses the height reserved
        // above, so what is drawn matches what the pills made room for.
        val timeTop = blockTop + records.size * slotPitch
        val timeRect = RectF(pillLeft, timeTop, pillRight, timeTop + timeRowHeight)
        paint.resetForExport()
        paint.color = 0xD9000000.toInt()
        canvas.drawRoundRect(timeRect, cornerRadius, cornerRadius, paint)

        val elapsedMs = viewport.playhead.toLong().coerceAtLeast(0L)
        val absTime = timelineOriginMs + viewport.playhead.toLong()

        // Sized to the time pill rather than to the wearer rows, which change with how many
        // people are in the session — the clock should not shrink because a fifth watch joined.
        val timeTextSize = timeRowHeight * 0.30f
        paint.color = config.labelsColor
        paint.textSize = timeTextSize
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER

        // Two lines centred as a block, so the pair sits in the middle whatever the pill height.
        val lineGap = timeTextSize * 0.30f
        val firstBaseline = timeRect.centerY() - lineGap / 2f
        canvas.drawText(
            StringFormatHelpers.getTimeString(absTime, java.time.ZoneId.of(config.timeZoneId)),
            timeRect.centerX(),
            firstBaseline,
            paint
        )
        paint.isFakeBoldText = false
        paint.color = 0xCCFFFFFF.toInt()
        canvas.drawText(
            StringFormatHelpers.getDurationString(elapsedMs),
            timeRect.centerX(),
            firstBaseline + timeTextSize + lineGap,
            paint
        )
    }

    /**
     * Where each wearer's pill sits in the stack, as a fractional slot: 0 is the top place, 1 the
     * next, and anything between the two is a pill part-way through changing places.
     *
     * The slide is *derived* from the playhead rather than remembered between frames. Frames are
     * rendered independently and not necessarily in order, so a carried-over position would drift
     * apart from the data on a re-render and be simply wrong for a still image. Reading the order
     * over a short trailing window instead gives the same motion from nothing but the timestamp,
     * so any frame drawn twice is drawn identically.
     *
     * The window is weighted by 6s(1-s), which integrates to a smoothstep: a wearer overtaking
     * another eases away and eases back into place rather than jumping between them at a constant
     * rate. [RANK_ANIM_MS] is in playhead time, which video export maps one-to-one onto real time.
     */
    /** How long a pill takes to change places, in playhead milliseconds. */
    private const val RANK_ANIM_MS = 600.0

    /**
     * How finely the trailing window is read. The slide advances one step per sample, so too few
     * would stair-step; beyond this the motion is smoother than a frame can show.
     */
    private const val RANK_ANIM_SAMPLES = 16

    internal fun animatedRankSlots(records: List<BpmRecord>, playhead: Double): FloatArray {
        val slots = FloatArray(records.size)
        if (records.size < 2) return slots

        var totalWeight = 0f
        repeat(RANK_ANIM_SAMPLES) { step ->
            val age = (step + 0.5f) / RANK_ANIM_SAMPLES
            val weight = 6f * age * (1f - age)
            totalWeight += weight

            rankOrderAt(records, playhead - age * RANK_ANIM_MS)
                .forEachIndexed { slot, index -> slots[index] += weight * slot }
        }

        if (totalWeight > 0f) {
            for (i in slots.indices) slots[i] /= totalWeight
        }
        return slots
    }

    /**
     * Record indices in the order they should be stacked at [at], fastest heart rate first.
     *
     * A wearer with no reading at this instant sorts to the bottom rather than out of the list, and
     * equal readings keep their original order, so neither a dropout nor a tie shuffles the stack.
     */
    internal fun rankOrderAt(records: List<BpmRecord>, at: Double): List<Int> {
        val bpms = records.map { getInterpolatedBpm(it.dataPoints, at) }
        return records.indices.sortedWith(
            compareByDescending<Int> { bpms[it] ?: Double.NEGATIVE_INFINITY }.thenBy { it }
        )
    }

    private fun drawMultiLegend(
        canvas: Canvas,
        dims: RenderingDimensions,
        records: List<BpmRecord>,
        config: ImageExportConfig,
        paint: Paint
    ) {
        val legendLeft = dims.graphLeft + 16f * dims.scaleFactor
        var legendTop = dims.graphTop + 16f * dims.scaleFactor
        val itemHeight = 32f * dims.scaleFactor

        records.forEachIndexed { index, record ->
            val wearerLabel = wearerLabelOf(record)
            val color = colorForRecord(record, index, config)

            paint.resetForExport()
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawCircle(legendLeft + 10f * dims.scaleFactor, legendTop + 12f * dims.scaleFactor, 8f * dims.scaleFactor, paint)

            paint.color = config.labelsColor
            paint.textSize = 20f * dims.scaleFactor
            paint.isFakeBoldText = true
            canvas.drawText(wearerLabel, legendLeft + 28f * dims.scaleFactor, legendTop + 18f * dims.scaleFactor, paint)

            legendTop += itemHeight
        }
    }
}
