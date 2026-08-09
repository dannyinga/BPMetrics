package inga.bpmetrics.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.roundToLong
import androidx.core.graphics.withClip
import inga.bpmetrics.export.ExportUtils.adjustAlpha
import kotlin.math.abs

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
        /**
         * The wearer's photograph for each recording, already cropped, keyed by record id.
         *
         * Decoded by the caller rather than here: this runs once per frame, and reading a file off
         * disk thirty times a second for a picture that never changes is the kind of thing that
         * turns a two-minute render into a ten-minute one.
         *
         * Absent is the ordinary case, and the pill falls back to the wearer's colour and initial —
         * the same fallback the avatar uses everywhere in the app.
         */
        val recordPhotos: Map<Long, Bitmap> = emptyMap(),
        val alignByElapsedTime: Boolean = true,
        /**
         * Heading drawn on the graph.
         *
         * A single record uses its own title. Several have no title of their own, so exporting a
         * named analysis passes its name here — "Subtronics 2026" rather than the generic label
         * every multi-watch export would otherwise carry. Null keeps that default.
         */
        val graphTitle: String? = null,

        /** How the live readouts are drawn. See [ExportPreset] for why these are a preset. */
        val pillShowPhoto: Boolean = true,
        val pillShowName: Boolean = true,
        val pillBpmInPersonColor: Boolean = true,
        val pillScale: Float = 1f,
        val pillPhotoScale: Float = 1f,
        val pillBpmScale: Float = 1f,
        val pillNameScale: Float = 1f,
        val pillCorner: PillCorner = PillCorner.TOP_RIGHT,
        /** See [ExportPreset.clockMode]. */
        val clockMode: ClockMode = ClockMode.CLOCK,

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
        // Origin zero, because this path's playhead is already an absolute timestamp — the
        // multi-record one is relative to a shared timeline and needs its origin added back.
        // Passing the record's start time here added it twice and put the clock hours out.
        drawGridAndAxes(canvas, dims, ranges, config, paint, viewport, timelineOriginMs = 0L)

        // 6. Draw Data Curve (Strictly clipped to 85% width to prevent HUD occlusion)
        drawDataCurve(canvas, dims, ranges, viewport, record, config, paint)

        // 7. Draw Glowing Head (Clipped to Graph Area)
        drawGlowingHead(canvas, dims, ranges, viewport, record, config, 0L, paint)

        // 8. The readout, drawn by the same code the multi-wearer path uses.
        //
        // There were two implementations of "show the current reading", and the solo one — the
        // commonest export there is — never received any of the work done on the other: no
        // photograph, no name, no configurable size, still a pulsing heart beside the digits. Two
        // sites drawing the same thing is how one of them quietly stops matching, and this is that
        // having already happened.
        if (config.showCurrentStats) {
            drawMultiStatsHUD(
                canvas = canvas,
                dims = dims,
                viewport = viewport,
                records = listOf(record),
                config = config,
                ranges = ranges,
                // Absolute already on this path — see the note beside drawGridAndAxes above.
                timelineOriginMs = 0L,
                paint = paint
            )
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
    /**
     * The wearer's face in a pill, or their colour and initial where there is no photograph.
     *
     * The same fallback the app's own avatar uses, deliberately: someone with no picture should
     * look on an export exactly as they do in the library, and a pill that simply omits the circle
     * would make the column ragged as soon as one person in a group had a photo and another did
     * not.
     *
     * Centre-cropped into the circle. The stored photograph is already framed — a crop rectangle in
     * fractions, applied when the caller decoded it — so what arrives here is the part that was
     * chosen, and all this does is fit it to a circle without stretching a face.
     */
    private fun drawPillAvatar(
        canvas: Canvas,
        photo: Bitmap?,
        initial: String,
        colorArgb: Int,
        centerX: Float,
        centerY: Float,
        size: Float,
        /**
         * How far into a beat, 0 at rest and 1 at the snap — from [BeatPhase.envelope].
         *
         * Zero rather than one for "no beat": this is an amount of pulse, not a scale factor. The
         * two were briefly the same parameter and the resting ring came out permanently enlarged.
         */
        pulse: Float = 0f,
        paint: Paint
    ) {
        val radius = size / 2f
        // Only the ring moves, never the face. Scaling a photograph every frame makes it shimmer as
        // it resamples, and a head that grows and shrinks reads as a fault rather than a heartbeat.
        // At rest the ring hugs the picture; on a beat it steps outward and falls back.
        val ringRadius = radius * (1f + PULSE_DEPTH * pulse.coerceIn(0f, 1f))

        if (photo != null && !photo.isRecycled && photo.width > 0 && photo.height > 0) {
            canvas.save()
            val circle = Path().apply {
                addCircle(centerX, centerY, radius, Path.Direction.CW)
            }
            canvas.clipPath(circle)

            // The largest square of the photograph, centred — anything else puts a face in a circle
            // squashed one way or the other.
            val edge = minOf(photo.width, photo.height)
            val src = android.graphics.Rect(
                (photo.width - edge) / 2,
                (photo.height - edge) / 2,
                (photo.width - edge) / 2 + edge,
                (photo.height - edge) / 2 + edge
            )
            val dst = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

            paint.resetForExport()
            paint.isFilterBitmap = true
            canvas.drawBitmap(photo, src, dst, paint)
            canvas.restore()

            // A ring in their colour, tying the face to the curve it belongs to — and beating,
            // which is what the heart beside it used to do before the pill was narrowed.
            paint.resetForExport()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.08f
            paint.color = colorArgb
            canvas.drawCircle(centerX, centerY, ringRadius - paint.strokeWidth / 2f, paint)
            paint.style = Paint.Style.FILL
            return
        }

        paint.resetForExport()
        paint.color = colorArgb
        canvas.drawCircle(centerX, centerY, ringRadius, paint)

        if (initial.isNotEmpty()) {
            paint.color = readableOn(colorArgb)
            paint.textSize = size * 0.58f
            paint.isFakeBoldText = true
            paint.textAlign = Paint.Align.CENTER
            val metrics = paint.fontMetrics
            canvas.drawText(
                initial,
                centerX,
                centerY + (abs(metrics.ascent) - metrics.descent) / 2f,
                paint
            )
            paint.textAlign = Paint.Align.LEFT
        }
    }

    /**
     * Black or white, whichever can be read on [background].
     *
     * People choose their own colours and some of them choose yellow. The same luminance test the
     * app's avatar uses, so an initial that is legible on screen is legible in the export.
     */
    private fun readableOn(background: Int): Int {
        val r = ((background shr 16) and 0xFF) / 255f
        val g = ((background shr 8) and 0xFF) / 255f
        val b = (background and 0xFF) / 255f
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance > 0.6f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }

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

    private fun drawGridAndAxes(
        canvas: Canvas,
        dims: RenderingDimensions,
        ranges: BpmRanges,
        config: ImageExportConfig,
        paint: Paint,
        /** The window and playhead, for the time axis. Null on a still, which has neither. */
        viewport: Viewport? = null,
        /** When the session began, for the clock. */
        timelineOriginMs: Long = 0L
    ) {
        val labelSize = 28f * dims.scaleFactor

        // Gridlines on values a person counts in — 5s, 10s, 20s, 25s — rather than the data range
        // cut into six. That produced labels like 154 and 167, which are true and useless: an axis
        // is read by landing on a value, and nobody lands on 167.
        GraphAxes.bpmGridLines(ranges.uiMin..ranges.uiMax).forEach { bpm ->
            val y = dims.getY(bpm, ranges)
            if (y !in dims.graphTop..dims.graphBottom) return@forEach

            if (config.showGrid) {
                paint.resetForExport()
                paint.color = config.gridColor
                paint.strokeWidth = 2f
                canvas.drawLine(dims.graphLeft, y, dims.graphRight, y, paint)
            }
            if (config.showLabels) {
                paint.resetForExport()
                paint.color = config.labelsColor
                paint.textSize = labelSize
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(
                    GraphAxes.bpmLabel(bpm),
                    dims.graphLeft - 15f * dims.scaleFactor,
                    y + 10f * dims.scaleFactor,
                    paint
                )
            }
        }

        if (viewport != null) {
            drawTimeAxis(canvas, dims, viewport, config, paint, labelSize)
        }

        drawHeader(canvas, dims, ranges, config, paint, viewport, timelineOriginMs)
    }

    /**
     * The time axis: where now is, and how far either side of it can be seen.
     *
     * There was none at all, which left the graph unable to answer the only question anyone asks of
     * it while watching — *how much of what is coming can I see?* The window is a preset, ten
     * seconds on one and a minute on another, so nothing on the frame distinguished the two.
     *
     * Labelled relative to the playhead rather than in clock time. "+15s" answers the question;
     * "21:47:30" is a fact about a different one, and the clock in the header covers that.
     */
    private fun drawTimeAxis(
        canvas: Canvas,
        dims: RenderingDimensions,
        viewport: Viewport,
        config: ImageExportConfig,
        paint: Paint,
        labelSize: Float
    ) {
        val spanMs = viewport.duration.roundToLong()
        if (spanMs <= 0L) return

        GraphAxes.timeOffsetsMs(spanMs).forEach { offset ->
            val x = dims.getX(viewport.playhead + offset, viewport)
            if (x < dims.graphLeft || x > dims.graphRight) return@forEach

            val isNow = offset == 0L

            // No line at the present. The glowing head already sits exactly there and is the
            // brightest thing on the graph — a second marker through the same point adds nothing
            // and draws a bar across the curve at the one place it is most worth seeing clearly.
            // The label stays, because the offsets either side are measured from it.
            if (config.showGrid && !isNow) {
                paint.resetForExport()
                // The grid colour as it is, dimmed further — not an alpha of its own.
                //
                // These were drawn at alpha 90 while `gridColor` is itself only alpha 31, which
                // made every vertical line about three times the weight of the horizontals beside
                // it. A time axis is a reference, not a subject: it should be found when looked for
                // and invisible when not, and anything louder than the heart rate lines is reading
                // as a feature of the data.
                paint.color = config.gridColor
                paint.alpha = (Color.alpha(config.gridColor) * 0.7f).roundToInt()
                paint.strokeWidth = 2f
                canvas.drawLine(x, dims.graphTop, x, dims.graphBottom, paint)
            }

            if (config.showLabels) {
                paint.resetForExport()
                paint.color = config.labelsColor
                paint.alpha = if (isNow) 255 else 180
                paint.textSize = labelSize * 0.82f
                paint.isFakeBoldText = isNow
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    GraphAxes.offsetLabel(offset),
                    // Kept inside the plot, so the first and last labels are not half off the frame.
                    x.coerceIn(dims.graphLeft + labelSize, dims.graphRight - labelSize),
                    dims.graphBottom + labelSize * 1.15f,
                    paint
                )
            }
        }
    }

    /**
     * The band above the plot: what this is on the left, when it is on the right.
     *
     * The clock lives here rather than in a corner of the graph because the header is the only
     * space on the frame that costs nothing. Every corner is contested — the readouts hold one, and
     * anything placed inside the plot covers curve, which on a graph whose job is showing a climb
     * arriving is the one thing not to spend.
     */
    private fun drawHeader(
        canvas: Canvas,
        dims: RenderingDimensions,
        ranges: BpmRanges,
        config: ImageExportConfig,
        paint: Paint,
        viewport: Viewport?,
        timelineOriginMs: Long
    ) {
        val clock = viewport?.let { clockTextFor(config, it, timelineOriginMs) }
        val baseline = dims.drawAreaTop + 60f * dims.scaleFactor

        val centreX = dims.graphLeft + dims.graphWidth / 2f
        val gap = 24f * dims.scaleFactor

        // Measured before the title is drawn, because it is what decides how much room the title
        // has. The clock is right-aligned and the title is centred, so the space the title may
        // occupy is the plot minus the clock's width *on both sides* — reserving it only on the
        // right would centre the title in the remainder, which is not the middle of the graph.
        var clockWidth = 0f
        if (clock != null) {
            paint.resetForExport()
            paint.textSize = 40f * dims.scaleFactor
            paint.isFakeBoldText = true
            clockWidth = paint.measureText(clock)
        }

        if (config.showTitle) {
            paint.resetForExport()
            paint.color = config.labelsColor
            paint.textSize = 48f * dims.scaleFactor
            paint.isFakeBoldText = true
            paint.textAlign = Paint.Align.CENTER

            val room = (dims.graphWidth - (clockWidth + gap) * 2f).coerceAtLeast(0f)
            canvas.drawText(ellipsize(ranges.title, room, paint), centreX, baseline, paint)
        }

        if (clock != null) {
            paint.resetForExport()
            paint.color = config.labelsColor
            paint.textSize = 40f * dims.scaleFactor
            paint.isFakeBoldText = true
            // To the right where it shares the band, centred where it has the band to itself.
            if (config.showTitle) {
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(clock, dims.graphRight, baseline, paint)
            } else {
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(clock, centreX, baseline, paint)
            }
        }
    }

    /** What the clock says, or null when it is switched off. */
    private fun clockTextFor(
        config: ImageExportConfig,
        viewport: Viewport,
        timelineOriginMs: Long
    ): String? = when (config.clockMode) {
        ClockMode.NONE -> null
        ClockMode.CLOCK -> StringFormatHelpers.getTimeString(
            timelineOriginMs + viewport.playhead.toLong(),
            java.time.ZoneId.of(config.timeZoneId)
        )
        // From the start of the session, which is well defined however many people are on the
        // frame — unlike "into the recording", which with five wearers is five different numbers.
        ClockMode.ELAPSED -> StringFormatHelpers.getDurationString(
            viewport.playhead.toLong().coerceAtLeast(0L)
        )
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

    /** How far a pill's ring steps out at the top of a beat, as a share of its radius. */
    private const val PULSE_DEPTH = 0.11f

    /**
     * How far the curve's head swells at the top of a beat.
     *
     * Matches the amplitude the old sine had, so only the *timing* changed here — the head beats
     * the same amount, it just no longer jumps to an unrelated point every time the reading ticks.
     */
    private const val HEAD_PULSE_DEPTH = 0.12f

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
        /** When the session began, so the beat can be placed on an absolute clock. */
        timelineOriginMs: Long,
        paint: Paint
    ) {
        // No reading means this session is not running at the playhead, so it has no head to mark.
        val currentBpm = getInterpolatedBpm(record.dataPoints, viewport.playhead) ?: return
        val headColor = getBpmColor(currentBpm, ranges, config)

        val headX = dims.getX(viewport.playhead, viewport)
        val headY = dims.getY(currentBpm, ranges)

        // The integrated beat, the same one the readout rings use. This was the last place still
        // multiplying the current rate by absolute time, which jumps by many whole cycles whenever
        // the reading changes — on a hard-edged ring that reads as a stutter, and on a soft glow as
        // an unsteady flicker. Same defect, quieter symptom, so it survived longer.
        val pulseScale = 1f + HEAD_PULSE_DEPTH * BeatPhase.envelope(
            BeatPhase.phaseAt(record, timelineOriginMs + viewport.playhead.toLong())
        )

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

    // Helper Classes to clean up variable passing
    private class RenderingDimensions(
        canvas: Canvas,
        rect: RectF,
        config: ImageExportConfig
    ) {
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

        // Widened out to whole gridline steps, so the top and bottom of the plot *are* gridlines
        // rather than two arbitrary edges with lines floating inside them. Outward only: cropping
        // to a tidy number would put a peak off the top, and a graph that hides the highest
        // reading is worse than one with an untidy edge.
        val snapped = GraphAxes.snapBpmRange(
            adjMin - (minSpread * 0.1),
            adjMax + (minSpread * 0.1)
        )

        return BpmRanges(sMin, sMax, snapped.start, snapped.endInclusive, record.metadata.title)
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
        drawGridAndAxes(
            canvas, dims, ranges, multiConfig, paint, viewport, aligned.timeline.originWallClockMs
        )

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
            drawGlowingHead(
                canvas, dims, ranges, viewport, rec, recordConfigs[index],
                aligned.timeline.originWallClockMs, paint
            )
        }

        if (config.showCurrentStats) {
            // The pills name each wearer and colour-match their curve, so they replace the legend.
            drawMultiStatsHUD(
                canvas = canvas,
                dims = dims,
                viewport = viewport,
                records = processedRecords,
                config = config,
                ranges = ranges,
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
    /** Everything about a readout column that both measuring and drawing need to agree on. */
    private class ReadoutMetrics(
        val rowHeight: Float,
        val rowGap: Float,
        val digitSize: Float,
        val labelSize: Float,
        val gap: Float,
        val padH: Float,
        val cornerRadius: Float,
        val avatarSize: Float,
        val digitWidth: Float,
        val textBlockWidth: Float,
        val labelWidth: Float,
        val pillWidth: Float,
        val shownLabels: List<String>,
        /** Whether the name sits under the reading rather than beside it. */
        val stacked: Boolean
    )

    private fun measureReadouts(
        dims: RenderingDimensions,
        records: List<BpmRecord>,
        config: ImageExportConfig,
        paint: Paint
    ): ReadoutMetrics {
        val scale = dims.scaleFactor
        val edgeMargin = 20f * scale
        val rowGap = 8f * scale

        // Pills are sized to fill the height rather than fixed small, so a two-wearer session
        // gets large readable numbers instead of the same cramped rows as a six-wearer one. The
        // ceiling stops a single pill becoming a slab; the floor keeps a crowded session legible
        // even if the column then runs slightly past the graph.
        //
        // Nothing is reserved at the bottom any more. The clock used to hang off the end of this
        // column and took a row's worth of height out of it; now it sits in the opposite corner and
        // the pills have the full run.
        // Against the whole panel, not the plot inside it.
        //
        // The plot has the title band taken off the top and the time axis off the bottom, and on a
        // lower-third framing that leaves very little: five wearers on a 1920-wide canvas were
        // getting about forty pixels of row each, which is a readout nobody can read. The pills are
        // a HUD drawn on the frame, not marks on the chart, so the space they get should be the
        // panel's — the title is centred and the axis labels are on the left, so a column down the
        // right has no quarrel with either.
        val available = dims.drawAreaHeight - (edgeMargin * 2)

        // Measured against the *draw area*, not the plot — and that is load-bearing rather than a
        // detail. The plot's width now depends on how wide this column turns out to be, so sizing
        // the column from the plot would be circular: a wider pill narrows the plot, which narrows
        // the cap, which narrows the pill. Against the frame it settles in one pass, and the width
        // worked out here is the width the gutter was reserved at.
        val widthCap = dims.drawAreaWidth * 0.16f

        // The preset scales what the renderer works out rather than replacing it, so no setting can
        // produce pills that do not fit the graph they are drawn on. The bounds still bind.
        val rowHeight = ((available - rowGap * (records.size - 1)) / records.size * config.pillScale)
            .coerceIn(48f * scale, minOf(190f * scale, widthCap * 1.4f))

        // Everything else follows the row height, so the proportions hold at any size.
        // Tighter than they were. Every millimetre of pill is a millimetre of curve not visible,
        // and on a graph whose whole purpose is showing a climb arriving, that is not a neutral
        // trade — the readouts were claiming space from the thing they are annotating.
        //
        // The name sits under the reading rather than beside it. In a row, every character of a
        // name was another millimetre of width, so "Kyle" was fine and "Alexandra" was a pill
        // halfway across the graph — and the fix for that, ellipsis, threw away the name. Stacked,
        // the two share a column as wide as the wider of them, and since three digits are already
        // reserved, most names cost nothing at all.
        // Whether the name sits beside the reading or under it, decided by what fits rather than
        // fixed. The two trade the same space in opposite directions: side by side is short and
        // wide, stacked is tall and narrow.
        //
        // Which is right depends entirely on how many people are on the frame, and stacking
        // unconditionally got that backwards for the common case. Five wearers on a landscape clip
        // have almost no vertical room and a great deal of horizontal, so cramming two lines into
        // each of five short rows made every one of them unreadable while most of the width sat
        // empty. Laid out flat the same rows carry a larger number and a legible name.
        //
        // Decided below, once both arrangements have been measured; the sizes here are the ones
        // both are measured at.
        // Tight. This sits between the face, the name and the reading, and at the previous 0.11 the
        // pill read as three separate things that happened to share a background rather than one
        // readout.
        val gap = rowHeight * 0.075f
        val padH = rowHeight * 0.15f
        val cornerRadius = rowHeight * 0.24f

        // A face and a number, and whatever else the preset asks for.
        //
        // Nothing here collapses when a part is switched off: the widths are summed from the parts
        // that are actually present, so hiding the name closes the gap rather than leaving a hole
        // where it used to be.
        val avatarSize = if (config.pillShowPhoto) {
            rowHeight * 0.58f * config.pillPhotoScale
        } else {
            0f
        }

        // How much of the frame the column may claim, and therefore the point at which the name
        // folds under the reading instead of sitting beside it.
        //
        // A fifth. A third was too generous: a readout is an annotation on the footage, and past
        // about this much it stops annotating and starts competing. Because flat is preferred and
        // stacking is the fallback, this number *is* the rule — the pill lays out flat while it
        // fits inside a fifth of the frame and stacks the moment it would not.
        val widthBudget = dims.drawAreaWidth * 0.22f
        val labelSize = rowHeight * 0.24f * config.pillNameScale

        paint.resetForExport()
        paint.isFakeBoldText = true
        paint.textSize = rowHeight * 0.50f * config.pillBpmScale
        // Reserve the width of the widest plausible reading so pills share one width. Without it a
        // wearer crossing from 99 to 100 would widen their pill mid-render and break the column.
        val flatDigitWidth = paint.measureText("888")

        paint.isFakeBoldText = false
        paint.textSize = labelSize
        val labels = records.map { wearerLabelOf(it) }
        val fullNameWidth = if (config.pillShowName) {
            labels.maxOfOrNull { paint.measureText(it) } ?: 0f
        } else {
            0f
        }

        // Flat is preferred and stacking is the fallback, rather than the other way round. Side by
        // side keeps the reading at full size and the name at full length; it is only worth folding
        // the name underneath when laying it out would push the column past its share of the frame.
        val flatWidth = padH * 2 + avatarSize + gap + flatDigitWidth +
            (if (fullNameWidth > 0f) gap + fullNameWidth else 0f)
        val stacked = config.pillShowName && flatWidth > widthBudget

        val digitSize = rowHeight * (if (stacked) 0.44f else 0.50f) * config.pillBpmScale

        paint.resetForExport()
        paint.isFakeBoldText = true
        paint.textSize = digitSize
        val digitWidth = paint.measureText("888")
        val digitMetrics = paint.fontMetrics
        val digitBaselineOffset = (abs(digitMetrics.ascent) - digitMetrics.descent) / 2f

        paint.isFakeBoldText = false
        paint.textSize = labelSize
        val labelMetrics = paint.fontMetrics

        val maxLabelWidth = widthBudget - (padH * 2 + avatarSize + gap) -
            (if (stacked) 0f else gap + digitWidth)
        val shownLabels = if (config.pillShowName && maxLabelWidth > labelSize) {
            labels.map { ellipsize(it, maxLabelWidth, paint) }
        } else {
            List(records.size) { "" }
        }
        val labelWidth = shownLabels.maxOfOrNull { paint.measureText(it) } ?: 0f

        // Stacked, the reading and the name share one column as wide as the wider of the two. Flat,
        // they sit end to end.
        val textBlockWidth = if (stacked) {
            maxOf(digitWidth, labelWidth)
        } else {
            digitWidth + (if (labelWidth > 0f) gap + labelWidth else 0f)
        }
        val contentWidth = if (avatarSize > 0f) {
            avatarSize + gap + textBlockWidth
        } else {
            textBlockWidth
        }
        val pillWidth = contentWidth + padH * 2

        return ReadoutMetrics(
            rowHeight = rowHeight,
            rowGap = rowGap,
            digitSize = digitSize,
            labelSize = labelSize,
            gap = gap,
            padH = padH,
            cornerRadius = cornerRadius,
            avatarSize = avatarSize,
            digitWidth = digitWidth,
            textBlockWidth = textBlockWidth,
            labelWidth = labelWidth,
            pillWidth = pillWidth,
            shownLabels = shownLabels,
            stacked = stacked
        )
    }

    private fun drawMultiStatsHUD(
        canvas: Canvas,
        dims: RenderingDimensions,
        viewport: Viewport,
        records: List<BpmRecord>,
        config: ImageExportConfig,
        /** The vertical scale, so a lone wearer's reading can be coloured by value. */
        ranges: BpmRanges,
        timelineOriginMs: Long,
        paint: Paint
    ) {
        val scale = dims.scaleFactor
        val edgeMargin = 20f * scale
        val m = measureReadouts(dims, records, config, paint)
        val rowHeight = m.rowHeight
        val rowGap = m.rowGap
        val digitSize = m.digitSize
        val labelSize = m.labelSize
        val gap = m.gap
        val padH = m.padH
        val cornerRadius = m.cornerRadius
        val avatarSize = m.avatarSize
        val digitWidth = m.digitWidth
        val textBlockWidth = m.textBlockWidth
        val labelWidth = m.labelWidth
        val pillWidth = m.pillWidth
        val shownLabels = m.shownLabels
        val stacked = m.stacked
        val labels = records.map { wearerLabelOf(it) }

        // Line metrics for the baselines. Recomputed from the sizes above rather than carried in
        // ReadoutMetrics: they depend on nothing but the paint and the size, so passing them would
        // be storing something already known.
        paint.resetForExport()
        paint.isFakeBoldText = true
        paint.textSize = digitSize
        val digitMetrics = paint.fontMetrics
        val digitBaselineOffset = (abs(digitMetrics.ascent) - digitMetrics.descent) / 2f
        paint.isFakeBoldText = false
        paint.textSize = labelSize
        val labelMetrics = paint.fontMetrics

        // Inside the plot, against whichever edge the preset chose.
        //
        // Over the curve rather than beside it, and on the right that is a deliberately cheap place
        // to be: the playhead is centred and everything past it is drawn at `futureOpacity`, so the
        // right of the plot is already the faded half. The pills sit on the part of the graph that
        // is deliberately quiet, and the solid, full-strength past stays clear.
        val onLeft = config.pillCorner == PillCorner.TOP_LEFT
        val pillLeft = if (onLeft) {
            dims.graphLeft + edgeMargin
        } else {
            dims.graphRight - edgeMargin - pillWidth
        }
        val pillRight = pillLeft + pillWidth
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

            // A rim in the wearer's colour — but only where there is more than one wearer.
            //
            // It does two jobs, and both are about telling people apart: it ties a pill to its
            // curve, and it keeps the edge legible while two pills overlap mid-swap. With one
            // person there is nobody to distinguish and no swap to survive, so it is decoration
            // that also happens to be misleading — a lone recording's curve is drawn as the
            // blue-to-red ramp, not in their colour, so a coloured rim points at a colour the graph
            // is not using. The avatar keeps its ring, which is about them rather than the curve.
            if (records.size > 1) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = rowHeight * 0.035f
                paint.color = (recordColor and 0x00FFFFFF) or 0xCC000000.toInt()
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            }

            val contentLeft = pillLeft + padH
            val centerY = rect.centerY()

            // Laid out left to right from whatever the preset left switched on, so a hidden part
            // closes its gap instead of leaving a hole where it used to be.
            var cursor = contentLeft

            if (avatarSize > 0f) {
                drawPillAvatar(
                    canvas = canvas,
                    photo = config.recordPhotos[record.metadata.recordId],
                    initial = labels[index].trim().firstOrNull()?.uppercase() ?: "",
                    colorArgb = recordColor,
                    centerX = cursor + avatarSize / 2f,
                    centerY = centerY,
                    size = avatarSize,
                    // Integrated phase, against this wearer's own rate at this instant — so six
                    // rings beat independently and each keeps time with the number beside it.
                    pulse = if (bpm == null) {
                        0f
                    } else {
                        BeatPhase.envelope(
                            BeatPhase.phaseAt(record, timelineOriginMs + viewport.playhead.toLong())
                        )
                    },
                    paint = paint
                )
                cursor += avatarSize + gap
            }

            paint.resetForExport()
            // The reading takes the colour of the curve it belongs to — which is not the same
            // colour in both cases, and that is the point.
            //
            // With several wearers, a curve is drawn flat in that person's colour, because the
            // thing the colour has to say is *whose* it is. With one, there is nobody to tell them
            // apart from, so the curve keeps the blue-to-red ramp and the colour says how high the
            // rate is instead. A pill sitting beside a curve that shades from cool to hot, printing
            // one fixed colour throughout, was contradicting the line right next to it.
            //
            // Never when there is no reading: a greyed "--" must not look like somebody's colour,
            // nor like a heart rate at the bottom of the scale.
            val readingColour = when {
                bpm == null -> config.labelsColor
                !config.pillBpmInPersonColor -> config.labelsColor
                records.size == 1 -> getBpmColor(bpm, ranges, config)
                else -> recordColor
            }
            paint.color = readingColour
            paint.textSize = digitSize
            paint.isFakeBoldText = true
            // Centred in the shared column rather than aligned to an edge. With two lines of
            // different widths stacked, aligning either one to a side leaves the other looking
            // like it slipped.
            paint.textAlign = Paint.Align.CENTER
            val blockCentreX = cursor + textBlockWidth / 2f

            val label = shownLabels[index]
            val reading = bpm?.roundToInt()?.toString() ?: "--"

            if (label.isEmpty()) {
                canvas.drawText(reading, blockCentreX, centerY + digitBaselineOffset, paint)
            } else if (!stacked) {
                // Face, name, reading — identity together, then the number.
                //
                // The reading used to sit between the two, which split the one thing on the pill
                // that answers "whose" across either side of the one thing that answers "what". A
                // face and a name belong next to each other; putting the number after them also
                // lines the readings up as a column down the right, which is what a ranked stack
                // wants.
                val labelBaseline = centerY + (abs(labelMetrics.ascent) - labelMetrics.descent) / 2f

                paint.resetForExport()
                paint.textSize = labelSize
                paint.isFakeBoldText = true
                // Always the label colour, never theirs — with the reading already carrying their
                // colour, a name in it too leaves no neutral text on the pill at all.
                paint.color = config.labelsColor
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText(label, cursor, labelBaseline, paint)

                // Advanced by the *widest* name, not this one, so every reading starts at the same
                // x and the column of numbers is straight.
                paint.resetForExport()
                paint.textSize = digitSize
                paint.isFakeBoldText = true
                paint.color = readingColour
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText(
                    reading,
                    cursor + labelWidth + gap,
                    centerY + digitBaselineOffset,
                    paint
                )
            } else {
                // Measured off the real line heights rather than guessed from the font sizes, so
                // the pair sits centred in the pill whatever the two sizes work out to.
                val digitLine = abs(digitMetrics.ascent) + digitMetrics.descent
                val labelLine = abs(labelMetrics.ascent) + labelMetrics.descent
                val lineGap = rowHeight * 0.02f
                val blockTopY = centerY - (digitLine + lineGap + labelLine) / 2f

                canvas.drawText(reading, blockCentreX, blockTopY + abs(digitMetrics.ascent), paint)

                paint.resetForExport()
                paint.textSize = labelSize
                paint.isFakeBoldText = true
                paint.textAlign = Paint.Align.CENTER
                // Always the label colour, never theirs. With the reading already carrying their
                // colour, a name in it too is two of the same signal and leaves the pill with no
                // neutral text on it at all.
                paint.color = config.labelsColor
                canvas.drawText(
                    label,
                    blockCentreX,
                    blockTopY + digitLine + lineGap + abs(labelMetrics.ascent),
                    paint
                )
            }
        }

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
