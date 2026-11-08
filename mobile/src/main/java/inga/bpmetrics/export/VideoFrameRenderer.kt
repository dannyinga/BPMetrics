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
import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.RecordNameFormatter
import inga.bpmetrics.library.PersonColors
import inga.bpmetrics.ui.graph.TimeUtils
import inga.bpmetrics.ui.util.StringFormatHelpers
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import androidx.core.graphics.withClip
import kotlin.math.abs

/**
 * Draws one frame of a video: the moving graph, its readouts, header and clock, on a transparent
 * canvas laid over the footage. Also what the Look step previews with, so the two agree.
 *
 * Named for what it does. It was `ImageExporter`, though it never exported an image; the picture
 * export is [ImageRenderer].
 */
object VideoFrameRenderer {



    /**
     * What one frame is drawn from: the content, the look, and the clock it's read in.
     *
     * Not a config to fill in. It holds the two things it was given and adds only the zone, which
     * depends on both (see [inga.bpmetrics.library.exportZoneOf]). The thirty-field
     * `ImageExportConfig` this replaces was filled by hand-copying a preset into it, and the copies
     * drifted (Model and Export Consolidation §6).
     */
    class Frame internal constructor(
        val content: ExportContent,
        val look: ExportPreset,
        /** The zone clock readings are shown in. */
        val zoneId: String
    ) {
        companion object {
            fun of(content: ExportContent, look: ExportPreset, records: List<inga.bpmetrics.library.BpmRecordEntity>) =
                Frame(content, look, inga.bpmetrics.library.exportZoneOf(records, content.clipZoneId))
        }
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
            return if (firstPoint.timestamp - timestampMs <= ChartKit.GAP_MS) firstPoint.bpm else null
        }
        
        val lastPoint = points.last()
        if (timestampMs > lastPoint.timestamp) {
            return if (timestampMs - lastPoint.timestamp <= ChartKit.GAP_MS) lastPoint.bpm else null
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
        if (t2 - t1 > ChartKit.GAP_MS) {
            return null
        }
        
        val ratio = (timestampMs - t1) / (t2 - t1)
        return p1.bpm + ratio * (p2.bpm - p1.bpm)
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
        config: Frame,
        paint: Paint
    ) {
        if (!config.look.showWordmark) return
        Wordmark.draw(
            canvas = canvas,
            bounds = dims.outerRect,
            opacityPercent = config.look.wordmarkOpacity,
            color = config.look.labelsColor,
            // The panel's own scale, not the canvas's. A preset that shrinks the graph to a quarter
            // of the frame should get a mark a quarter the size — one scaled off the canvas would
            // sit in a small panel looking like a headline.
            scale = dims.scaleFactor,
            paint = paint
        )
    }

    private fun drawContainer(canvas: Canvas, dims: RenderingDimensions, config: Frame, paint: Paint) {
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
        if (canvas.isOpaque) canvas.drawColor(inga.bpmetrics.core.BpmPalette.SURFACE)
        val bgAlpha = (config.look.backgroundOpacity * 255 / 100).coerceIn(0, 255)
        if (bgAlpha > 0) {
            paint.resetForExport()
            paint.color = inga.bpmetrics.core.BpmPalette.SURFACE
            paint.alpha = bgAlpha
            canvas.drawRect(dims.outerRect, paint)
        }
    }

    private fun drawGridAndAxes(
        canvas: Canvas,
        dims: RenderingDimensions,
        ranges: BpmRanges,
        config: Frame,
        paint: Paint,
        /** The window and playhead, for the time axis. Null on a still, which has neither. */
        viewport: Viewport? = null,
        /** When the session began, for the clock. */
        timelineOriginMs: Long = 0L
    ) {
        val contrast = contrastOf(config)

        // Gridlines on values a person counts in — 5s, 10s, 20s, 25s — rather than the data range
        // cut into six. That produced labels like 154 and 167, which are true and useless: an axis
        // is read by landing on a value, and nobody lands on 167.
        val lines = GraphAxes.bpmGridLines(ranges.uiMin..ranges.uiMax)
        val spacing = if (lines.size >= 2) {
            abs(dims.getY(lines[0], ranges) - dims.getY(lines[1], ranges))
        } else {
            dims.graphHeight
        }
        // On a short plot, every other label, or every third, rather than all of them piled into
        // one smudge (UX Audit O3). Gridlines thin the same way once they would be a hatching.
        val labelEvery = kotlin.math.ceil(dims.axisText * 1.3f / spacing.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        val gridEvery = kotlin.math.ceil(dims.axisText * 0.5f / spacing.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        // Fewer than two labels is not a scale, and one alone reads as a value.
        val showLabels = config.look.showLabels && lines.size >= 2 * labelEvery

        lines.forEachIndexed { index, bpm ->
            val y = dims.getY(bpm, ranges)
            if (y !in dims.graphTop..dims.graphBottom) return@forEachIndexed

            if (config.look.showGrid && index % gridEvery == 0) {
                paint.resetForExport()
                paint.color = config.look.gridColor
                paint.strokeWidth = 2f
                canvas.drawLine(dims.graphLeft, y, dims.graphRight, y, paint)
            }
            if (showLabels && index % labelEvery == 0) {
                paint.resetForExport()
                paint.color = config.look.labelsColor
                paint.textSize = dims.axisText
                paint.textAlign = Paint.Align.RIGHT
                drawText(
                    canvas, GraphAxes.bpmLabel(bpm),
                    dims.graphLeft - 12f * dims.scaleFactor,
                    // Centred on its line: a cap height is about 0.7 of the size.
                    y + dims.axisText * 0.35f,
                    paint, contrast, dims
                )
            }
        }

        if (viewport != null && dims.showTimeAxis) {
            drawTimeAxis(canvas, dims, viewport, config, paint, contrast)
        }

        drawHeader(canvas, dims, ranges, config, paint, viewport, timelineOriginMs, contrast)
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
        config: Frame,
        paint: Paint,
        contrast: Contrast
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
            if (config.look.showGrid && !isNow) {
                paint.resetForExport()
                // The grid colour, dimmed further. A time axis is a reference, not a subject: it
                // should be found when looked for and invisible when not.
                paint.color = config.look.gridColor
                paint.alpha = (Color.alpha(config.look.gridColor) * 0.7f).roundToInt()
                paint.strokeWidth = 2f
                canvas.drawLine(x, dims.graphTop, x, dims.graphBottom, paint)
            }

            if (config.look.showLabels) {
                paint.resetForExport()
                paint.color = config.look.labelsColor
                paint.alpha = if (isNow) 255 else 180
                paint.textSize = dims.timeText
                paint.isFakeBoldText = isNow
                paint.textAlign = Paint.Align.CENTER
                drawText(
                    canvas,
                    GraphAxes.offsetLabel(offset),
                    // Kept inside the plot, so the first and last labels are not half off the frame.
                    x.coerceIn(dims.graphLeft + dims.timeText * 1.2f, dims.graphRight - dims.timeText * 1.2f),
                    dims.graphBottom + dims.timeText * 1.2f,
                    paint, contrast, dims
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
        config: Frame,
        paint: Paint,
        viewport: Viewport?,
        timelineOriginMs: Long,
        contrast: Contrast
    ) {
        val clock = viewport?.let { clockTextFor(config, it, timelineOriginMs) }
        val baseline = dims.headerBaseline

        val centreX = dims.graphLeft + dims.graphWidth / 2f
        val gap = 24f * dims.scaleFactor

        // Measured before the title is drawn, because it is what decides how much room the title
        // has. The clock is right-aligned and the title is centred, so the space the title may
        // occupy is the plot minus the clock's width *on both sides* — reserving it only on the
        // right would centre the title in the remainder, which is not the middle of the graph.
        var clockWidth = 0f
        if (clock != null) {
            paint.resetForExport()
            paint.textSize = dims.clockText
            paint.isFakeBoldText = true
            clockWidth = paint.measureText(clock)
        }

        if (config.look.showTitle) {
            paint.resetForExport()
            paint.color = config.look.labelsColor
            paint.isFakeBoldText = true
            paint.textAlign = Paint.Align.CENTER

            val room = (dims.graphWidth - (clockWidth + gap) * 2f).coerceAtLeast(0f)
            // Smaller before shorter. A title is usually an artist and a venue, and "Excision B2B
            // SLA…" loses the part that says who; a title a size down loses nothing (UX Audit O4).
            val floor = maxOf(dims.readableText, dims.titleText * 0.7f)
            paint.textSize = dims.titleText
            while (paint.measureText(ranges.title) > room && paint.textSize * 0.92f >= floor) {
                paint.textSize *= 0.92f
            }
            drawText(canvas, ChartKit.ellipsize(ranges.title, room, paint), centreX, baseline, paint, contrast, dims)
        }

        if (clock != null) {
            paint.resetForExport()
            paint.color = config.look.labelsColor
            paint.textSize = dims.clockText
            paint.isFakeBoldText = true
            // To the right where it shares the band, centred where it has the band to itself.
            if (config.look.showTitle) {
                paint.textAlign = Paint.Align.RIGHT
                drawText(canvas, clock, dims.graphRight, baseline, paint, contrast, dims)
            } else {
                paint.textAlign = Paint.Align.CENTER
                drawText(canvas, clock, centreX, baseline, paint, contrast, dims)
            }
        }
    }

    /** What keeps this frame's curves and text readable over footage. */
    private fun contrastOf(config: Frame): Contrast =
        config.look.contrast.resolvedFor(config.look.backgroundOpacity)

    /**
     * Text with whatever [contrast] puts behind it.
     *
     * A soft shadow is enough over most footage; an outline holds over anything, sky and stage
     * lights included, at the cost of looking heavier.
     */
    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        contrast: Contrast,
        dims: RenderingDimensions
    ) {
        when (contrast) {
            Contrast.SHADOW -> {
                paint.setShadowLayer(
                    maxOf(5f * dims.scaleFactor, 3f), 0f, 1.5f * dims.scaleFactor, CONTRAST_SHADOW
                )
                canvas.drawText(text, x, y, paint)
                paint.clearShadowLayer()
            }
            Contrast.OUTLINE -> {
                val fill = paint.color
                val style = paint.style
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = maxOf(paint.textSize * 0.14f, 2f)
                // As faded as the text it outlines, so a dimmed label keeps a dimmed edge.
                paint.color = (CONTRAST_OUTLINE and 0x00FFFFFF) or
                    ((Color.alpha(CONTRAST_OUTLINE) * Color.alpha(fill) / 255) shl 24)
                canvas.drawText(text, x, y, paint)
                paint.style = style
                paint.color = fill
                canvas.drawText(text, x, y, paint)
            }
            else -> canvas.drawText(text, x, y, paint)
        }
    }

    /** What sits behind text and curves for [Contrast.SHADOW] and [Contrast.OUTLINE]. */
    private const val CONTRAST_SHADOW = 0xE6000000.toInt()
    private const val CONTRAST_OUTLINE = 0xE6000000.toInt()

    /** What the clock says, or null when it is switched off. */
    private fun clockTextFor(
        config: Frame,
        viewport: Viewport,
        timelineOriginMs: Long
    ): String? = when (config.look.clockMode) {
        ClockMode.NONE -> null
        // With seconds: this one is a running clock rather than a stamp, and it advances a frame
        // at a time.
        ClockMode.CLOCK -> StringFormatHelpers.getTimeString(
            timelineOriginMs + viewport.playhead.toLong(),
            ChartKit.zone(config.zoneId),
            withSeconds = true
        )
        // From the start of the session, which is well defined however many people are on the
        // frame — unlike "into the recording", which with five wearers is five different numbers.
        ClockMode.ELAPSED -> StringFormatHelpers.getDurationString(
            viewport.playhead.toLong().coerceAtLeast(0L)
        )
    }

    private fun drawDataCurve(canvas: Canvas, dims: RenderingDimensions, ranges: BpmRanges, viewport: Viewport, record: BpmRecordWithPoints, config: Frame, paint: Paint) {
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
                        val isGap = (p.timestamp - prevTime) > ChartKit.GAP_MS

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
                val isGapToPlayhead = pastPoints.isNotEmpty() && (viewport.playhead - pastPoints.last().timestamp) > ChartKit.GAP_MS
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
                    val isGap = (p.timestamp - prevTime) > ChartKit.GAP_MS

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
                    // In time, not pixels. This compared the segment's width in pixels with a gap in
                    // milliseconds, so it was never a gap and the dashed end was never drawn (Audit #26).
                    val msAcross = (endX - lastX) / dims.graphWidth * viewport.duration
                    val isGapToEnd = msAcross > ChartKit.GAP_MS
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
        config: Frame,
        fill: Path,
        pastSolid: Path,
        pastDashed: Path,
        futureSolid: Path,
        futureDashed: Path,
        playheadX: Float,
        firstPointX: Float,
        lastPointX: Float
    ) {
        val paint = ChartKit.paint()

        // --- 1. DEFINE HORIZONTAL ALPHA MASKS ---
        val pastAlphaMask = LinearGradient(
            firstPointX, 0f, playheadX, 0f,
            intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
            null, Shader.TileMode.CLAMP
        )

        val futureAlpha = (config.look.futureOpacity * 255).toInt().coerceIn(0, 255)
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
            intArrayOf(config.look.highBpmColor, midUiColor, config.look.lowBpmColor),
            null, Shader.TileMode.CLAMP
        )

        // --- 3. RENDER FILL (Past Only) ---
        val fillGradient = LinearGradient(
            0f, dims.getY(ranges.uiMax, ranges),
            0f, dims.getY(ranges.uiMin, ranges),
            intArrayOf((config.look.highBpmColor and 0x00FFFFFF) or 0x55000000, 0x00000000),
            null, Shader.TileMode.CLAMP
        )
        paint.shader = ComposeShader(fillGradient, pastAlphaMask, PorterDuff.Mode.DST_IN)
        paint.style = Paint.Style.FILL
        canvas.drawPath(fill, paint)

        // --- 3b. CONTRAST, UNDER BOTH LINES ---
        // The same paths, wider and dark, faded by the same masks so the halo ends where the line
        // does. Without it a thin coloured line over bright footage disappears (UX Audit O5).
        val contrast = contrastOf(config)
        if (contrast != Contrast.OFF) {
            val haloColour = if (contrast == Contrast.SHADOW) CONTRAST_SHADOW else CONTRAST_OUTLINE
            val solid = LinearGradient(0f, 0f, 1f, 0f, haloColour, haloColour, Shader.TileMode.CLAMP)
            val halo = ChartKit.paint().apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                if (contrast == Contrast.SHADOW) {
                    maskFilter = android.graphics.BlurMaskFilter(
                        5f * dims.scaleFactor, android.graphics.BlurMaskFilter.Blur.NORMAL
                    )
                }
            }
            val extra = (if (contrast == Contrast.SHADOW) 6f else 4f) * dims.scaleFactor
            val dash = android.graphics.DashPathEffect(floatArrayOf(15f * dims.scaleFactor, 15f * dims.scaleFactor), 0f)
            listOf(
                Triple(futureSolid, futureAlphaMask, 4f) to null,
                Triple(futureDashed, futureAlphaMask, 4f) to dash,
                Triple(pastSolid, pastAlphaMask, 7f) to null,
                Triple(pastDashed, pastAlphaMask, 7f) to dash
            ).forEach { (line, effect) ->
                val (path, mask, width) = line
                halo.shader = ComposeShader(solid, mask, PorterDuff.Mode.DST_IN)
                halo.strokeWidth = width * dims.scaleFactor + extra
                halo.pathEffect = effect
                canvas.drawPath(path, halo)
            }
        }

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
    private fun colorForRecord(record: BpmRecordWithPoints, index: Int, config: Frame): Int =
        ChartKit.colourFor(record.metadata.recordId, index, config.content.colours)

    /**
     * The name to show for whoever wore the watch, falling back to the device then the title.
     *
     * The title is only used when it says something. A pill reading "Untitled 4" over someone's
     * heart rate is worse than one reading "Unknown", and it goes out in a video.
     */

    private fun drawGlowingHead(
        canvas: Canvas,
        dims: RenderingDimensions,
        ranges: BpmRanges,
        viewport: Viewport,
        record: BpmRecordWithPoints,
        config: Frame,
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

        // Core, on a dark ring where the footage needs one: the glow is the head's own colour, so
        // over a bright frame it vanishes along with the line.
        paint.clearShadowLayer()
        val contrast = contrastOf(config)
        if (contrast != Contrast.OFF) {
            paint.color = if (contrast == Contrast.SHADOW) CONTRAST_SHADOW else CONTRAST_OUTLINE
            canvas.drawCircle(headX, headY, 8.5f * dims.scaleFactor, paint)
        }
        paint.color = headColor
        canvas.drawCircle(headX, headY, 6f * dims.scaleFactor, paint)

        // Inner Spark. Left white deliberately where the surrounding text is not: this is a
        // specular highlight on the head of the curve, not a label, and tinting it to the theme
        // would flatten the one thing on the graph that is meant to look lit.
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(headX, headY, 2.5f * dims.scaleFactor, paint)
    }

    /**
     * Where everything goes on the panel, worked out once per frame.
     *
     * Text is sized from the panel and held to [readableText], and each band is sized from the text
     * in it. The bands used to be fixed allowances (120 for a 48-unit title, 140 for three-digit
     * labels), which on a short panel left a plot a few pixels tall (UX Audit O3). When the plot
     * would still come out shorter than [MIN_PLOT_LINES] axis labels, the time axis gives way first.
     */
    private class RenderingDimensions(
        val fullWidth: Float,
        val fullHeight: Float,
        rect: RectF,
        config: Frame
    ) {
        val drawAreaLeft = rect.left * fullWidth
        val drawAreaTop = rect.top * fullHeight
        val drawAreaRight = rect.right * fullWidth
        val drawAreaBottom = rect.bottom * fullHeight
        val drawAreaWidth = drawAreaRight - drawAreaLeft
        val drawAreaHeight = drawAreaBottom - drawAreaTop
        // The canvas's scale (see [ChartKit.scale]), shrunk with the share of the width the graph
        // takes, but never below half of it, so a small graph keeps readable text.
        private val canvasScale = ChartKit.scale(fullWidth, fullHeight)
        val scaleFactor = (canvasScale * drawAreaWidth / fullWidth).coerceAtLeast(0.5f * canvasScale)
        val outerRect = RectF(drawAreaLeft, drawAreaTop, drawAreaRight, drawAreaBottom)

        /** The smallest text drawn anywhere on this frame. See [ChartKit.readableText]. */
        val readableText = ChartKit.readableText(fullWidth, fullHeight)

        private val textScale = config.look.textScale
        val axisText = maxOf(28f * scaleFactor * textScale, readableText)
        val timeText = maxOf(23f * scaleFactor * textScale, readableText)
        val titleText = maxOf(48f * scaleFactor * textScale, readableText * 1.4f)
        val clockText = maxOf(40f * scaleFactor * textScale, readableText * 1.2f)

        /** Kept clear inside the panel's edge. */
        val edge = 16f * scaleFactor

        private val hasHeader = config.look.showTitle || config.look.clockMode != ClockMode.NONE
        private val headerText = if (config.look.showTitle) titleText else clockText

        /** Where the title and clock sit, a line of [headerText] below the panel's top edge. */
        val headerBaseline = drawAreaTop + edge + headerText

        private val paddingTop = if (hasHeader) edge + headerText * 1.45f else edge
        // Room for "200" right-aligned against the plot, and the gap before it.
        private val paddingLeft = if (config.look.showLabels) axisText * 1.9f + 12f * scaleFactor else edge
        private val paddingRight = edge * 1.5f
        private val timeBand = if (config.look.showLabels) timeText * 1.6f + 6f * scaleFactor else edge

        /**
         * Whether the time axis is drawn. Off when keeping its band would leave a plot shorter than
         * a few axis labels: of everything round the plot, the time axis is what the graph can best
         * do without, since the clock in the header already says where in time it is.
         */
        val showTimeAxis = drawAreaHeight - paddingTop - timeBand >= axisText * MIN_PLOT_LINES
        private val paddingBottom = if (showTimeAxis) timeBand else edge

        val graphWidth = (drawAreaWidth - paddingLeft - paddingRight).coerceAtLeast(1f)
        val graphHeight = (drawAreaHeight - paddingTop - paddingBottom).coerceAtLeast(1f)

        val graphLeft = drawAreaLeft + paddingLeft
        val graphRight = graphLeft + graphWidth
        val graphTop = drawAreaTop + paddingTop
        val graphBottom = graphTop + graphHeight

        fun getY(bpm: Double, ranges: BpmRanges): Float = graphTop + (1f - (bpm - ranges.uiMin).toFloat() / ranges.uiRange.toFloat()) * graphHeight
        fun getX(ts: Double, vp: Viewport): Float = graphLeft + ((ts - vp.start) / vp.duration * graphWidth).toFloat()

        companion object {
            /** The shortest plot worth drawing, in axis-label heights. */
            const val MIN_PLOT_LINES = 3.5f
        }
    }

    private class BpmRanges(val uiMin: Double, val uiMax: Double, val title: String) {
        val uiRange = (uiMax - uiMin).coerceAtLeast(1.0)
    }

    private class Viewport(val start: Double, val end: Double, val playhead: Double, val duration: Double, val lookahead: Long)

    private fun calculateViewport(currentTime: Double?, windowSize: Long?, config: Frame, record: BpmRecordWithPoints): Viewport {
        // 1. Determine the playhead position (50% through the selected snippet if currentTime is null)
        val playhead = currentTime ?: ((config.content.startMs + config.content.endMs) / 2.0)

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
            start = config.content.startMs.toDouble()
            end = config.content.endMs.toDouble()
        }

        val duration = (end - start).coerceAtLeast(1.0)
        val lookahead = if (windowSize != null) (windowSize * 0.5).toLong() else 5000L

        return Viewport(start, end, playhead, duration, lookahead)
    }

    private fun getBpmColor(bpm: Double, ranges: BpmRanges, config: Frame): Int {
        val fraction = ((bpm - ranges.uiMin) / ranges.uiRange).coerceIn(0.0, 1.0).toFloat()
        // The same walk the on-screen chart uses, against this preset's endpoints — which default
        // to the app's own low and high.
        return inga.bpmetrics.core.BpmRamp.blend(
            config.look.lowBpmColor,
            config.look.highBpmColor,
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
    data class AlignedRecords(val records: List<BpmRecordWithPoints>, val timeline: Timeline)

    /**
     * Computes the shared time axis for [records] without rewriting any data points.
     *
     * Clock time: the origin is the earliest session start, and each record sits at its real
     * distance from it. Cheap enough to call from the UI; use [alignRecords] when the shifted
     * points are needed.
     *
     * There was an elapsed-time mode, starting every record at 0:00 to compare shapes. Nothing
     * reached it: every caller passed false, because a recording happened at a particular time and
     * a video is in sync with that time (Model and Export Consolidation §6).
     */
    fun timelineFor(records: List<BpmRecordWithPoints>): Timeline {
        if (records.isEmpty()) return Timeline(0L, 0L)
        val origin = records.minOf { it.metadata.startTime }
        val duration = records.maxOf { rec ->
            (rec.metadata.startTime - origin) + lastTimestampOf(rec)
        }
        return Timeline(origin, duration.coerceAtLeast(0L))
    }

    /**
     * Places several records on one timeline so a single graph can show them together.
     *
     * Each record is offset by how long after [Timeline.originWallClockMs] it actually started, so
     * every record lands exactly where it would if it had been exported on its own. That is what
     * lets one video stay in sync with all of them at once.
     */
    fun alignRecords(records: List<BpmRecordWithPoints>): AlignedRecords {
        val timeline = timelineFor(records)
        if (records.isEmpty()) return AlignedRecords(emptyList(), timeline)

        val shifted = records.map { rec ->
            val offset = rec.metadata.startTime - timeline.originWallClockMs
            if (offset == 0L) rec
            else rec.copy(dataPoints = rec.dataPoints.map { it.copy(timestamp = it.timestamp + offset) })
        }

        return AlignedRecords(shifted, timeline)
    }

    private fun lastTimestampOf(record: BpmRecordWithPoints): Long =
        record.dataPoints.lastOrNull()?.timestamp ?: record.metadata.durationMs

    /** The parts of a frame someone can point at, to open their settings (S14B.2). */
    enum class Region { READOUTS, TITLE, CLOCK, PLOT, WORDMARK }

    /**
     * What is drawn at ([x], [y]) on a [width] × [height] frame, or null outside the panel.
     *
     * Worked out from the same layout the frame is drawn with, so a tap on a pill finds the pills
     * wherever this cast and this panel put them.
     */
    fun regionAt(
        width: Int,
        height: Int,
        aligned: AlignedRecords,
        content: ExportContent,
        look: ExportPreset,
        graphRect: RectF,
        x: Float,
        y: Float,
        readouts: ReadoutSpot? = look.frameFor(aligned.records.size).readouts
    ): Region? {
        // First, and before the panel test: a column moved off the graph is outside the panel.
        readoutBlock(width, height, aligned.records, content, look, graphRect, readouts)?.let { block ->
            if (block.contains(x, y)) return Region.READOUTS
        }

        val config = Frame.of(content, look, aligned.records.map { it.metadata })
        val dims = RenderingDimensions(width.toFloat(), height.toFloat(), graphRect, config)
        if (!dims.outerRect.contains(x, y)) return null
        if (look.showWordmark) {
            // The mark is one short line in the top-left corner: the first fifth across, sixth down.
            val inX = x < dims.drawAreaLeft + dims.drawAreaWidth * 0.2f
            val inY = y < dims.drawAreaTop + dims.drawAreaHeight / 6f
            if (inX && inY) return Region.WORDMARK
        }
        if (y < dims.graphTop) {
            val clockShown = look.clockMode != ClockMode.NONE
            // The clock is right-aligned beside a title, or the whole band without one.
            return when {
                clockShown && (!look.showTitle || x > dims.graphLeft + dims.graphWidth * 0.7f) -> Region.CLOCK
                else -> Region.TITLE
            }
        }
        return Region.PLOT
    }

    /**
     * Where the readout column is drawn on a [width] × [height] frame, in pixels, or null when the
     * look draws none.
     *
     * For the preview's outline, which has to sit exactly on the column the render draws. Takes the
     * records as they are rather than aligned: only who they are matters to the column's size, and
     * aligning copies every data point.
     */
    fun readoutBlock(
        width: Int,
        height: Int,
        records: List<BpmRecordWithPoints>,
        content: ExportContent,
        look: ExportPreset,
        graphRect: RectF,
        readouts: ReadoutSpot?
    ): RectF? {
        if (!look.showCurrentStats || records.isEmpty()) return null
        val config = Frame.of(content, look, records.map { it.metadata })
        val dims = RenderingDimensions(width.toFloat(), height.toFloat(), graphRect, config)
        val plan = measureReadouts(dims, records, config, ChartKit.paint(), readouts).plan ?: return null
        return RectF(plan.left, plan.top, plan.left + plan.blockWidth, plan.top + plan.blockHeight)
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
        content: ExportContent,
        look: ExportPreset,
        currentTimeMs: Double? = null,
        graphRect: RectF = look.frameFor(aligned.records.size).toRectF(),
        /** Where the readout column sits. Null keeps it on the graph. See [ReadoutSpot]. */
        readouts: ReadoutSpot? = look.frameFor(aligned.records.size).readouts
    ) = renderAlignedRecordsOnCanvasInZone(
        canvas, aligned, Frame.of(content, look, aligned.records.map { it.metadata }),
        currentTimeMs, look.windowSizeMs, graphRect, readouts
    )

    private fun renderAlignedRecordsOnCanvasInZone(
        canvas: Canvas,
        aligned: AlignedRecords,
        config: Frame,
        currentTimeMs: Double?,
        windowSizeMs: Long?,
        graphRect: RectF,
        readouts: ReadoutSpot?
    ) {
        val processedRecords = aligned.records
        if (processedRecords.isEmpty()) return

        val primaryRecord = processedRecords.first()

        val dims = RenderingDimensions(canvas.width.toFloat(), canvas.height.toFloat(), graphRect, config)
        val paint = ChartKit.paint()

        val allPoints = processedRecords.flatMap { it.dataPoints }
        if (allPoints.isEmpty()) return
        // The one scale both renderers use, so a picture and a video of the same recordings read
        // against the same gridlines.
        val wholeRange = ChartKit.bpmRange(allPoints.map { it.bpm })

        // The crop is chosen against the shared timeline, so it is honoured as-is. A caller may
        // supply none at all, which falls back to the whole timeline.
        val hasUsableRange = config.content.endMs > config.content.startMs
        val multiConfig = if (hasUsableRange) {
            config
        } else {
            Frame(
                config.content.copy(startMs = 0L, endMs = aligned.timeline.durationMs),
                config.look, config.zoneId
            )
        }

        val viewport = calculateViewport(currentTimeMs, windowSizeMs, multiConfig, primaryRecord)

        // What's on screen, by default: thirty seconds of a heart rate against the whole
        // recording's range is a flat line (UX Audit O2). Only where there is a moving window; a
        // still of a whole stretch already shows everything its range covers.
        val bpmRange = if (config.look.bpmScale == BpmScale.ON_SCREEN && windowSizeMs != null && currentTimeMs != null) {
            ChartKit.windowRange(processedRecords.map { it.dataPoints }, viewport.playhead, windowSizeMs, wholeRange)
        } else {
            wholeRange
        }
        val ranges = BpmRanges(
            bpmRange.start, bpmRange.endInclusive,
            config.content.title?.takeIf { it.isNotBlank() } ?: "Multi-Watch Session"
        )

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
                // Each person's curve in their own colour: the look with both ends of the ramp set to it.
                Frame(
                    multiConfig.content,
                    multiConfig.look.copy(lowBpmColor = assignedColor, highBpmColor = assignedColor),
                    multiConfig.zoneId
                )
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

        // The pills name each wearer and colour-match their curve, so they replace the legend.
        if (!config.look.showCurrentStats) drawMultiLegend(canvas, dims, processedRecords, config, paint)

        drawWordmark(canvas, dims, config, paint)

        canvas.restore()

        // After the panel's clip is lifted: a column moved off the graph sits outside the panel.
        if (config.look.showCurrentStats) {
            drawMultiStatsHUD(
                canvas = canvas,
                dims = dims,
                viewport = viewport,
                records = processedRecords,
                config = config,
                ranges = ranges,
                timelineOriginMs = aligned.timeline.originWallClockMs,
                paint = paint,
                readouts = readouts
            )
        }
    }

    /**
     * Draws a live BPM pill per wearer, in one column on the graph or wherever it was moved to.
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
    /** Everything about a readout pill that both measuring and drawing need to agree on. */
    private class ReadoutMetrics(
        val rowHeight: Float,
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
        val stacked: Boolean,
        /** Where each place in the ranking goes. Null only while measuring, before there is one. */
        val plan: ReadoutLayout.Plan? = null
    )

    /**
     * The readouts' layout for this frame: the plan (see [ReadoutLayout]) and the pill it settled on.
     *
     * On the graph ([readouts] null), the column starts inside the plot's top corner on the look's
     * side and may run down over the panel, never past it. Moved off it, the column starts where it
     * was put and may use the whole canvas (S14C).
     */
    private fun measureReadouts(
        dims: RenderingDimensions,
        records: List<BpmRecordWithPoints>,
        config: Frame,
        paint: Paint,
        readouts: ReadoutSpot?
    ): ReadoutMetrics {
        val canvasScale = ChartKit.scale(dims.fullWidth, dims.fullHeight)
        val onGraph = readouts == null
        val pin = readouts?.pin
            ?: if (config.look.pillCorner == PillCorner.TOP_LEFT) ReadoutPin.TOP_LEFT else ReadoutPin.TOP_RIGHT

        val margin = if (onGraph) dims.edge else READOUT_MARGIN * canvasScale
        val areaLeft = if (onGraph) dims.graphLeft + margin else margin
        val areaTop = if (onGraph) dims.graphTop + margin else margin
        val areaRight = if (onGraph) dims.graphRight - margin else dims.fullWidth - margin
        val areaBottom = if (onGraph) dims.drawAreaBottom - margin else dims.fullHeight - margin

        val plan = ReadoutLayout.column(
            count = records.size,
            anchorX = readouts?.let { it.x * dims.fullWidth } ?: if (pin.onLeft) areaLeft else areaRight,
            anchorY = readouts?.let { it.y * dims.fullHeight } ?: areaTop,
            pin = pin,
            areaLeft = areaLeft,
            areaTop = areaTop,
            areaRight = areaRight,
            areaBottom = areaBottom,
            // The look's size on any canvas, held to the readable floor: the reading is under half
            // the row, and the name is never smaller than the smallest text elsewhere.
            rowHeight = maxOf(READOUT_ROW * canvasScale, dims.readableText / DIGIT_SHARE_STACKED) *
                config.look.pillScale,
            // On the graph, no more than half the plot: past that the pills stop annotating the
            // curve and start hiding the half of it that has happened.
            maxWidth = if (onGraph) minOf(dims.fullWidth * READOUT_MAX_WIDTH, dims.graphWidth * 0.5f)
            else dims.fullWidth * READOUT_MAX_WIDTH,
            measure = { rowHeight, allowance ->
                pillAt(dims, rowHeight, allowance, records, config, paint).pillWidth
            }
        )
        val pill = pillAt(dims, plan.rowHeight, plan.allowance, records, config, paint)
        return ReadoutMetrics(
            rowHeight = pill.rowHeight,
            digitSize = pill.digitSize,
            labelSize = pill.labelSize,
            gap = pill.gap,
            padH = pill.padH,
            cornerRadius = pill.cornerRadius,
            avatarSize = pill.avatarSize,
            digitWidth = pill.digitWidth,
            textBlockWidth = pill.textBlockWidth,
            labelWidth = pill.labelWidth,
            pillWidth = plan.pillWidth,
            shownLabels = pill.shownLabels,
            stacked = pill.stacked,
            plan = plan
        )
    }

    /** A pill's height at 1080 on the canvas's short edge, before the look's size. */
    private const val READOUT_ROW = 64f

    /** The widest a pill gets, as a share of the canvas's width. */
    private const val READOUT_MAX_WIDTH = 0.42f

    /** Kept clear between a moved column and the canvas's edge, at 1080 on the short edge. */
    private const val READOUT_MARGIN = 24f

    /** The reading's share of a pill's height with the name beside it, and with it underneath. */
    private const val DIGIT_SHARE_FLAT = 0.50f
    private const val DIGIT_SHARE_STACKED = 0.44f

    /**
     * Between a name and the reading beside it, as a share of the row. Wider than the gap between
     * the face and the name, which belong together: at the face's gap, the longest name ran into
     * its number ("Alexandra106").
     */
    private const val NAME_GAP = 0.22f

    /**
     * One pill at [rowHeight], with names cut to fit [allowance].
     *
     * Whether the name sits beside the reading or under it is decided by what fits. Side by side is
     * short and wide, stacked is tall and narrow; flat is preferred because it keeps the reading at
     * full size and the name at full length, and the name only folds underneath when flat would
     * pass the allowance.
     *
     * Nothing collapses when a part is switched off: widths are summed from the parts actually
     * present, so hiding the name closes the gap rather than leaving a hole.
     */
    private fun pillAt(
        dims: RenderingDimensions,
        rowHeight: Float,
        allowance: Float,
        records: List<BpmRecordWithPoints>,
        config: Frame,
        paint: Paint
    ): ReadoutMetrics {
        // Tight. This sits between the face, the name and the reading, and any looser reads as three
        // things sharing a background rather than one readout.
        val gap = rowHeight * 0.075f
        val padH = rowHeight * 0.15f
        val cornerRadius = rowHeight * 0.24f

        val avatarSize = if (config.look.pillShowPhoto) {
            rowHeight * 0.58f * config.look.pillPhotoScale
        } else {
            0f
        }

        // Held to the readable floor. Sized off the row alone, a name on a crowded panel
        // came out about half the size of the smallest text anywhere else on the frame.
        val labelSize = maxOf(rowHeight * 0.28f * config.look.pillNameScale, dims.readableText)

        paint.resetForExport()
        paint.isFakeBoldText = true
        paint.textSize = rowHeight * DIGIT_SHARE_FLAT * config.look.pillBpmScale
        // The widest plausible reading, so every pill is one width and a wearer crossing from 99 to
        // 100 doesn't widen theirs mid-render.
        val flatDigitWidth = paint.measureText("888")

        paint.isFakeBoldText = false
        paint.textSize = labelSize
        val labels = ChartKit.distinctLabels(records)
        val fullNameWidth = if (config.look.pillShowName) {
            labels.maxOfOrNull { paint.measureText(it) } ?: 0f
        } else {
            0f
        }

        val flatWidth = padH * 2 + avatarSize + gap + flatDigitWidth +
            (if (fullNameWidth > 0f) rowHeight * NAME_GAP + fullNameWidth else 0f)
        val stacked = config.look.pillShowName && flatWidth > allowance

        val digitSize = rowHeight * (if (stacked) DIGIT_SHARE_STACKED else DIGIT_SHARE_FLAT) *
            config.look.pillBpmScale

        paint.resetForExport()
        paint.isFakeBoldText = true
        paint.textSize = digitSize
        val digitWidth = paint.measureText("888")

        paint.isFakeBoldText = false
        paint.textSize = labelSize

        val maxLabelWidth = allowance - (padH * 2 + avatarSize + gap) -
            (if (stacked) 0f else rowHeight * NAME_GAP + digitWidth)
        val shownLabels = if (config.look.pillShowName && maxLabelWidth > labelSize) {
            labels.map { ChartKit.ellipsize(it, maxLabelWidth, paint) }
        } else {
            List(records.size) { "" }
        }
        val labelWidth = shownLabels.maxOfOrNull { paint.measureText(it) } ?: 0f

        // Stacked, the reading and the name share one column as wide as the wider of the two. Flat,
        // they sit end to end.
        val textBlockWidth = if (stacked) {
            maxOf(digitWidth, labelWidth)
        } else {
            digitWidth + (if (labelWidth > 0f) rowHeight * NAME_GAP + labelWidth else 0f)
        }
        val contentWidth = if (avatarSize > 0f) avatarSize + gap + textBlockWidth else textBlockWidth

        return ReadoutMetrics(
            rowHeight = rowHeight,
            digitSize = digitSize,
            labelSize = labelSize,
            gap = gap,
            padH = padH,
            cornerRadius = cornerRadius,
            avatarSize = avatarSize,
            digitWidth = digitWidth,
            textBlockWidth = textBlockWidth,
            labelWidth = labelWidth,
            pillWidth = contentWidth + padH * 2,
            shownLabels = shownLabels,
            stacked = stacked
        )
    }

    private fun drawMultiStatsHUD(
        canvas: Canvas,
        dims: RenderingDimensions,
        viewport: Viewport,
        records: List<BpmRecordWithPoints>,
        config: Frame,
        /** The vertical scale, so a lone wearer's reading can be coloured by value. */
        ranges: BpmRanges,
        timelineOriginMs: Long,
        paint: Paint,
        readouts: ReadoutSpot?
    ) {
        val m = measureReadouts(dims, records, config, paint, readouts)
        val plan = m.plan ?: return
        val rowHeight = m.rowHeight
        val digitSize = m.digitSize
        val labelSize = m.labelSize
        val gap = m.gap
        val padH = m.padH
        val cornerRadius = m.cornerRadius
        val avatarSize = m.avatarSize
        val textBlockWidth = m.textBlockWidth
        val labelWidth = m.labelWidth
        val pillWidth = m.pillWidth
        val shownLabels = m.shownLabels
        val stacked = m.stacked
        val labels = ChartKit.distinctLabels(records)

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

        // One column, wherever the plan put it. On the graph's right that is a cheap place to be:
        // the playhead is centred and everything past it is drawn at `futureOpacity`, so the pills
        // sit on the part of the graph that is deliberately quiet.
        val slots = animatedRankSlots(records, viewport.playhead)

        // Pills mid-slide are drawn last so one passes *over* the pill it is overtaking. Two
        // opaque pills swapping places must cross, and without an order the overlap looks torn.
        val drawOrder = records.indices.sortedBy { abs(slots[it] - slots[it].roundToInt()) }

        drawOrder.forEach { index ->
            val record = records[index]
            val bpm = getInterpolatedBpm(record.dataPoints, viewport.playhead)
            val recordColor = colorForRecord(record, index, config)
            val (pillLeft, rowTop) = plan.corner(slots[index])
            val rect = RectF(pillLeft, rowTop, pillLeft + pillWidth, rowTop + rowHeight)

            paint.resetForExport()
            // Denser than the single-record HUD: these sit over whatever the video is showing,
            // and a busy frame behind thin digits is what made them hard to read.
            paint.color = 0xD9000000.toInt()
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            // A rim on every pill, so one person and five look like the same design (UX Audit O8).
            //
            // In the wearer's colour where there is more than one: it ties a pill to its curve and
            // keeps the edge legible while two pills cross mid-swap. With one, a coloured rim would
            // point at a colour the graph isn't using, since a lone curve is drawn as the
            // blue-to-red ramp, so the rim is the label colour, quietly.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rowHeight * 0.035f
            paint.color = if (records.size > 1) {
                (recordColor and 0x00FFFFFF) or 0xCC000000.toInt()
            } else {
                (config.look.labelsColor and 0x00FFFFFF) or 0x55000000
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            val contentLeft = pillLeft + padH
            val centerY = rect.centerY()

            // Laid out left to right from whatever the preset left switched on, so a hidden part
            // closes its gap instead of leaving a hole where it used to be.
            var cursor = contentLeft

            if (avatarSize > 0f) {
                drawPillAvatar(
                    canvas = canvas,
                    photo = config.content.photos[record.metadata.recordId],
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
                bpm == null -> config.look.labelsColor
                !config.look.pillBpmInPersonColor -> config.look.labelsColor
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
                paint.color = config.look.labelsColor
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
                    cursor + labelWidth + rowHeight * NAME_GAP,
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
                paint.color = config.look.labelsColor
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

    internal fun animatedRankSlots(records: List<BpmRecordWithPoints>, playhead: Double): FloatArray {
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
    internal fun rankOrderAt(records: List<BpmRecordWithPoints>, at: Double): List<Int> {
        val bpms = records.map { getInterpolatedBpm(it.dataPoints, at) }
        return records.indices.sortedWith(
            compareByDescending<Int> { bpms[it] ?: Double.NEGATIVE_INFINITY }.thenBy { it }
        )
    }

    private fun drawMultiLegend(
        canvas: Canvas,
        dims: RenderingDimensions,
        records: List<BpmRecordWithPoints>,
        config: Frame,
        paint: Paint
    ) {
        val legendLeft = dims.graphLeft + 16f * dims.scaleFactor
        var legendTop = dims.graphTop + 16f * dims.scaleFactor
        val itemHeight = 32f * dims.scaleFactor

        val legendLabels = ChartKit.distinctLabels(records)
        records.forEachIndexed { index, record ->
            val wearerLabel = legendLabels[index]
            val color = colorForRecord(record, index, config)

            paint.resetForExport()
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawCircle(legendLeft + 10f * dims.scaleFactor, legendTop + 12f * dims.scaleFactor, 8f * dims.scaleFactor, paint)

            paint.color = config.look.labelsColor
            paint.textSize = maxOf(20f * dims.scaleFactor, dims.readableText)
            paint.isFakeBoldText = true
            canvas.drawText(wearerLabel, legendLeft + 28f * dims.scaleFactor, legendTop + 18f * dims.scaleFactor, paint)

            legendTop += itemHeight
        }
    }
}
