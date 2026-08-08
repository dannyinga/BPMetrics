package inga.bpmetrics.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A whole timeline as a single picture.
 *
 * Separate from [ImageExporter] rather than a mode of it, because almost everything that renderer
 * does is organised around a playhead: a scrolling viewport, a fade over what has not happened yet,
 * a header reading the heart rate *right now*. None of that means anything in a still. "Current
 * BPM" on an image of a two-hour set is not a simplification of the truth, it is a number picked at
 * random.
 *
 * So this draws the other thing: every curve end to end at full strength, the summary of each
 * person beside the graph rather than over it, and — where the picture covers several events — the
 * events marked out along it, so a festival day reads as a day rather than as one long squiggle.
 */
object TimelineImageExporter {

    /** One person's curve across the window. Timestamps are offsets from the window's start. */
    data class Series(
        val label: String,
        val colorArgb: Int,
        val points: List<Point>
    ) {
        data class Point(val timestampMs: Long, val bpm: Double)

        val min: Double? get() = points.minOfOrNull { it.bpm }
        val max: Double? get() = points.maxOfOrNull { it.bpm }
        val avg: Double? get() = points.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()
    }

    /** A stretch of the window that was one event, marked along the top of the plot. */
    data class Section(val label: String, val startMs: Long, val endMs: Long)

    data class Spec(
        val width: Int,
        val height: Int,
        val title: String?,
        val windowStartWallClockMs: Long,
        val windowEndWallClockMs: Long,
        val series: List<Series>,
        val sections: List<Section> = emptyList(),
        val showTitle: Boolean = true,
        val showGrid: Boolean = true,
        val showLabels: Boolean = true,
        val showStats: Boolean = true,
        val lowBpmColor: Int = inga.bpmetrics.ui.theme.BpmPalette.LOW,
        val highBpmColor: Int = inga.bpmetrics.ui.theme.BpmPalette.HIGH,
        val labelsColor: Int = inga.bpmetrics.ui.theme.BpmPalette.ON_SURFACE,
        val gridColor: Int = inga.bpmetrics.ui.theme.BpmPalette.GRID,
        val backgroundOpacity: Int = 100,
        val timeZoneId: String = ZoneId.systemDefault().id
    ) {
        val durationMs: Long get() = (windowEndWallClockMs - windowStartWallClockMs).coerceAtLeast(1L)
    }

    /** How many days the picture covers. Two means every time label needs a date on it. */
    private fun daysSpanned(spec: Spec, zone: ZoneId): Int {
        val first = Instant.ofEpochMilli(spec.windowStartWallClockMs).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(spec.windowEndWallClockMs).atZone(zone).toLocalDate()
        return (java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1).toInt().coerceAtLeast(1)
    }

    fun render(spec: Spec): Bitmap? {
        if (spec.width <= 0 || spec.height <= 0) return null
        val drawable = spec.series.filter { it.points.isNotEmpty() }
        if (drawable.isEmpty()) return null

        val zone = runCatching { ZoneId.of(spec.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val bitmap = createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = true }

        // Scaled off the short edge, so a 1080×1920 story and a 1920×1080 landscape get text of
        // comparable weight rather than one of them being unreadable.
        val scale = minOf(spec.width, spec.height) / 1080f

        // Nothing is drawn under this. A fully transparent panel is the point of the setting: the
        // picture is meant to drop onto footage in something that composites.
        val bgAlpha = (spec.backgroundOpacity * 255 / 100).coerceIn(0, 255)
        if (bgAlpha > 0) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = inga.bpmetrics.ui.theme.BpmPalette.SURFACE
            paint.alpha = bgAlpha
            canvas.drawRoundRect(
                RectF(0f, 0f, spec.width.toFloat(), spec.height.toFloat()),
                24f * scale, 24f * scale, paint
            )
        }

        val margin = 48f * scale
        var top = margin

        if (spec.showTitle && !spec.title.isNullOrBlank()) {
            top = drawHeader(canvas, spec, zone, scale, margin, top, paint)
        }

        // The summary sits below the graph, in its own band. Over the curve it would cover the one
        // thing the picture is of, and beside it on a phone-shaped canvas there is no room.
        //
        // Measured before the plot is sized, because how many rows it needs depends on how many
        // columns fit — four people on a landscape canvas are two rows, not four.
        val statsLayout = if (spec.showStats) {
            statsLayout(spec, drawable, margin, scale, paint)
        } else {
            null
        }
        val statsHeight = statsLayout?.let { layout ->
            val rows = (drawable.size + layout.columns - 1) / layout.columns
            layout.rowHeight * rows + 26f * scale
        } ?: 0f
        val axisHeight = if (spec.showLabels) 46f * scale else 12f * scale
        val sectionHeight = if (spec.sections.size > 1) 40f * scale else 0f

        val plot = RectF(
            margin + (if (spec.showLabels) 96f * scale else 0f),
            top + sectionHeight,
            spec.width - margin,
            spec.height - margin - statsHeight - axisHeight
        )
        if (plot.height() < 40f * scale || plot.width() < 40f * scale) return bitmap

        val range = BpmRange.of(drawable)

        if (spec.sections.size > 1) {
            drawSections(canvas, spec, plot, top, sectionHeight, scale, paint)
        }
        if (spec.showGrid || spec.showLabels) {
            drawGridAndScale(canvas, spec, plot, range, scale, paint)
        }
        drawable.forEach { series ->
            drawCurve(canvas, spec, plot, range, series, drawable.size == 1, scale, paint)
        }
        if (spec.showLabels) {
            drawTimeAxis(canvas, spec, plot, zone, scale, paint)
        }
        if (statsLayout != null) {
            drawStats(canvas, spec, drawable, plot.bottom + axisHeight, margin, scale, statsLayout, paint)
        }

        return bitmap
    }

    /** Title, and the span it covers — with dates, since a group can run over several days. */
    private fun drawHeader(
        canvas: Canvas,
        spec: Spec,
        zone: ZoneId,
        scale: Float,
        margin: Float,
        top: Float,
        paint: Paint
    ): Float {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = spec.labelsColor
        paint.textSize = 46f * scale
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(spec.title.orEmpty(), margin, top + 40f * scale, paint)

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = 28f * scale
        paint.alpha = 170
        canvas.drawText(spanLabel(spec, zone), margin, top + 78f * scale, paint)
        paint.alpha = 255

        return top + 104f * scale
    }

    /**
     * The window in words.
     *
     * Dates are included whenever the picture covers more than one of them — a group spanning a
     * festival weekend is exactly the case where "14:20 – 01:35" is unreadable without them.
     */
    private fun spanLabel(spec: Spec, zone: ZoneId): String {
        val date = DateTimeFormatter.ofPattern("d MMM yyyy")
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val from = Instant.ofEpochMilli(spec.windowStartWallClockMs).atZone(zone)
        val to = Instant.ofEpochMilli(spec.windowEndWallClockMs).atZone(zone)

        return if (from.toLocalDate() == to.toLocalDate()) {
            "${date.format(from)} · ${time.format(from)} – ${time.format(to)}"
        } else {
            "${date.format(from)} ${time.format(from)} – ${date.format(to)} ${time.format(to)}"
        }
    }

    /**
     * Each event as a labelled band above the plot.
     *
     * What turns one long line into a readable day. Without it, a group on a single timeline is a
     * two-hour squiggle with no way to tell which part was which set.
     */
    private fun drawSections(
        canvas: Canvas,
        spec: Spec,
        plot: RectF,
        top: Float,
        height: Float,
        scale: Float,
        paint: Paint
    ) {
        val bandTop = top + 4f * scale
        val bandBottom = top + height - 10f * scale

        spec.sections.forEachIndexed { index, section ->
            val x0 = plot.left + plot.width() * (section.startMs.toFloat() / spec.durationMs)
            val x1 = plot.left + plot.width() * (section.endMs.toFloat() / spec.durationMs)
            if (x1 <= x0) return@forEachIndexed

            paint.reset()
            paint.isAntiAlias = true
            // Alternating weight rather than colour: the curves already own colour, and a second
            // colour scheme competing with them makes both harder to read.
            paint.color = spec.labelsColor
            paint.alpha = if (index % 2 == 0) 38 else 20
            canvas.drawRoundRect(
                RectF(x0, bandTop, x1 - 2f * scale, bandBottom),
                6f * scale, 6f * scale, paint
            )

            paint.alpha = 220
            paint.textSize = 22f * scale
            val label = ellipsize(section.label, x1 - x0 - 14f * scale, paint)
            if (label.isNotEmpty()) {
                canvas.drawText(label, x0 + 8f * scale, bandBottom - 9f * scale, paint)
            }

            // A hairline down the plot, so a section boundary is findable against the curve too.
            if (index > 0) {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = spec.gridColor
                paint.strokeWidth = 1.5f * scale
                canvas.drawLine(x0, plot.top, x0, plot.bottom, paint)
            }
        }
    }

    private fun drawGridAndScale(
        canvas: Canvas,
        spec: Spec,
        plot: RectF,
        range: BpmRange,
        scale: Float,
        paint: Paint
    ) {
        val lines = 5
        for (i in 0..lines) {
            val bpm = range.min + (range.span * i / lines)
            val y = plot.bottom - plot.height() * (i.toFloat() / lines)

            if (spec.showGrid) {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = spec.gridColor
                paint.strokeWidth = 1.5f * scale
                canvas.drawLine(plot.left, y, plot.right, y, paint)
            }
            if (spec.showLabels) {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = spec.labelsColor
                paint.alpha = 190
                paint.textSize = 24f * scale
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(
                    bpm.toInt().toString(),
                    plot.left - 14f * scale,
                    y + 8f * scale,
                    paint
                )
                paint.textAlign = Paint.Align.LEFT
            }
        }
    }

    /**
     * One curve, drawn end to end at full strength.
     *
     * No fade over any part of it: the fade a video uses marks what has not happened yet, and in a
     * still nothing has "not happened yet". Half a faded curve would just look like missing data.
     *
     * A lone curve is coloured by value — blue through red — because with nobody to tell it apart
     * from, the colour is free to say how hard the heart was working. Several curves take a colour
     * each instead, which is the only way to tell whose is whose.
     */
    private fun drawCurve(
        canvas: Canvas,
        spec: Spec,
        plot: RectF,
        range: BpmRange,
        series: Series,
        colourByValue: Boolean,
        scale: Float,
        paint: Paint
    ) {
        val path = Path()
        var started = false
        var lastTs = Long.MIN_VALUE

        series.points.forEach { point ->
            val x = plot.left + plot.width() * (point.timestampMs.toFloat() / spec.durationMs)
            val y = plot.bottom - plot.height() *
                ((point.bpm - range.min) / range.span).toFloat().coerceIn(0f, 1f)

            // A gap in the data breaks the line rather than being bridged. Joining across a
            // dropout draws a heart rate that was never measured.
            val isGap = lastTs != Long.MIN_VALUE && (point.timestampMs - lastTs) > GAP_THRESHOLD_MS
            if (!started || isGap) path.moveTo(x, y) else path.lineTo(x, y)
            started = true
            lastTs = point.timestampMs
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4.5f * scale
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        if (colourByValue) {
            paint.shader = LinearGradient(
                0f, plot.bottom, 0f, plot.top,
                intArrayOf(spec.lowBpmColor, blend(spec.lowBpmColor, spec.highBpmColor), spec.highBpmColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            paint.color = series.colorArgb
        }

        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    /** Clock times along the bottom, carrying dates when the picture crosses midnight. */
    private fun drawTimeAxis(
        canvas: Canvas,
        spec: Spec,
        plot: RectF,
        zone: ZoneId,
        scale: Float,
        paint: Paint
    ) {
        val multiDay = daysSpanned(spec, zone) > 1
        val formatter = DateTimeFormatter.ofPattern(if (multiDay) "d MMM\nHH:mm" else "HH:mm")
        // Fewer labels when each carries a date, or they collide.
        val ticks = if (multiDay) 4 else 6

        paint.reset()
        paint.isAntiAlias = true
        paint.color = spec.labelsColor
        paint.alpha = 190
        paint.textSize = 22f * scale
        paint.textAlign = Paint.Align.CENTER

        for (i in 0..ticks) {
            val fraction = i.toFloat() / ticks
            val x = plot.left + plot.width() * fraction
            val at = spec.windowStartWallClockMs + (spec.durationMs * fraction).toLong()
            val text = formatter.format(Instant.ofEpochMilli(at).atZone(zone))

            // Pulled inside at the ends so the first and last labels are not half off the canvas.
            val drawX = x.coerceIn(plot.left + 30f * scale, plot.right - 30f * scale)
            text.split('\n').forEachIndexed { line, part ->
                canvas.drawText(part, drawX, plot.bottom + (24f + line * 22f) * scale, paint)
            }
        }
        paint.textAlign = Paint.Align.LEFT
    }

    /**
     * Everyone's summary for the window, below the graph.
     *
     * The thing a still can say that a video frame cannot: not what the heart rate was at one
     * instant, but what it did across the whole thing. Placed under the plot rather than over it,
     * because the graph is what the picture is for.
     */
    private fun drawStats(
        canvas: Canvas,
        spec: Spec,
        series: List<Series>,
        top: Float,
        margin: Float,
        scale: Float,
        layout: StatsLayout,
        paint: Paint
    ) {
        val y = top + 26f * scale

        series.forEachIndexed { index, entry ->
            val column = index % layout.columns
            val row = index / layout.columns
            val x = margin + column * layout.columnWidth
            val rowY = y + row * layout.rowHeight

            paint.reset()
            paint.isAntiAlias = true
            paint.color = entry.colorArgb
            canvas.drawCircle(x + 8f * scale, rowY - 8f * scale, 8f * scale, paint)

            paint.color = spec.labelsColor
            paint.textSize = NAME_SIZE * scale
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText(ellipsize(entry.label, layout.nameWidth, paint), x + 24f * scale, rowY, paint)

            // Numbers immediately after the widest name rather than pushed to the far edge. On a
            // 1920-wide canvas, right-aligning them put someone's readings the better part of a
            // foot from their name, which is a caption for nobody.
            var cursor = x + 24f * scale + layout.nameWidth + 16f * scale
            cursor = drawStat(canvas, spec, "min", entry.min, cursor, rowY, scale, paint)
            cursor = drawStat(canvas, spec, "avg", entry.avg, cursor, rowY, scale, paint)
            drawStat(canvas, spec, "max", entry.max, cursor, rowY, scale, paint)
        }
    }

    /**
     * One `min 62` pair: the word small and dim, the number full size.
     *
     * Two weights rather than one run of text, because the numbers are what is being read and the
     * words are only there to say which is which. Returns where the next pair should start.
     */
    private fun drawStat(
        canvas: Canvas,
        spec: Spec,
        label: String,
        value: Double?,
        x: Float,
        y: Float,
        scale: Float,
        paint: Paint
    ): Float {
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = LABEL_SIZE * scale
        paint.color = spec.labelsColor
        paint.alpha = 130
        canvas.drawText(label, x, y, paint)
        val labelWidth = paint.measureText(label)

        paint.textSize = NAME_SIZE * scale
        paint.alpha = 235
        val text = value?.toInt()?.toString() ?: "–"
        canvas.drawText(text, x + labelWidth + 5f * scale, y, paint)
        paint.alpha = 255

        return x + labelWidth + 5f * scale + paint.measureText(text) + 18f * scale
    }

    /**
     * How the summary packs into the space below the plot.
     *
     * Columns, because a landscape canvas is wide and a stack of four names down the left of it
     * wastes most of the band while making it taller than it needs to be. Sized off the longest
     * name so the numbers line up between rows without drifting away from them.
     */
    private fun statsLayout(
        spec: Spec,
        series: List<Series>,
        margin: Float,
        scale: Float,
        paint: Paint
    ): StatsLayout {
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = NAME_SIZE * scale

        val available = spec.width - margin * 2
        // Names are allowed a share of one column, not of the canvas, or a long name would push
        // the numbers off the edge in a two-column layout.
        val nameCap = available * 0.22f
        val nameWidth = series
            .maxOf { paint.measureText(it.label) }
            .coerceAtMost(nameCap)

        // Widest plausible reading, so the columns do not shift when someone hits three digits.
        paint.textSize = LABEL_SIZE * scale
        val labelsWidth = paint.measureText("minavgmax")
        paint.textSize = NAME_SIZE * scale
        val numbersWidth = paint.measureText("888") * 3

        val columnWidth = 24f * scale + nameWidth + 16f * scale +
            labelsWidth + numbersWidth + 15f * scale + 36f * scale
        val columns = (available / columnWidth).toInt().coerceIn(1, series.size.coerceAtLeast(1))

        return StatsLayout(
            columns = columns,
            columnWidth = available / columns,
            nameWidth = nameWidth,
            rowHeight = 32f * scale
        )
    }

    private class StatsLayout(
        val columns: Int,
        val columnWidth: Float,
        val nameWidth: Float,
        val rowHeight: Float
    )

    private const val NAME_SIZE = 26f
    private const val LABEL_SIZE = 19f

    /** Trims text to fit, with an ellipsis, rather than letting it run off the canvas. */
    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        var cut = text.length
        while (cut > 1 && paint.measureText(text.take(cut) + "…") > maxWidth) cut--
        return if (cut <= 1) "" else text.take(cut) + "…"
    }

    private fun blend(from: Int, to: Int): Int {
        val hsvA = FloatArray(3)
        val hsvB = FloatArray(3)
        android.graphics.Color.colorToHSV(from, hsvA)
        android.graphics.Color.colorToHSV(to, hsvB)
        var startHue = hsvA[0]
        var endHue = hsvB[0]
        if (endHue < startHue) endHue += 360f
        return android.graphics.Color.HSVToColor(
            floatArrayOf(
                (startHue + (endHue - startHue) * 0.5f) % 360f,
                (hsvA[1] + hsvB[1]) / 2f,
                (hsvA[2] + hsvB[2]) / 2f
            )
        )
    }

    /** The vertical scale, padded so the curve does not touch the frame. */
    private class BpmRange(val min: Double, val max: Double) {
        val span: Double get() = (max - min).coerceAtLeast(1.0)

        companion object {
            fun of(series: List<Series>): BpmRange {
                val all = series.flatMap { it.points }.map { it.bpm }
                if (all.isEmpty()) return BpmRange(60.0, 180.0)
                val low = all.min()
                val high = all.max()
                // A flat recording still deserves a readable scale rather than one 2bpm tall.
                val padded = ((high - low) * 0.12).coerceAtLeast(8.0)
                return BpmRange(
                    (low - padded).coerceAtLeast(30.0),
                    (high + padded).coerceAtMost(220.0)
                )
            }
        }
    }

    /** Longer than this between readings and the line breaks rather than bridging the gap. */
    private const val GAP_THRESHOLD_MS = 10_000L
}
