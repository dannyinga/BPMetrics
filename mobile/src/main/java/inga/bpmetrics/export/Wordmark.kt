package inga.bpmetrics.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Which side of the graph the live readouts stack down.
 *
 * Only the two top corners: the pills stack downward from wherever they start, so a bottom corner
 * would either grow off the frame or have to stack upward, which puts the fastest wearer at the
 * bottom and reverses the meaning of the column.
 */
enum class PillCorner(val label: String) {
    // Declared left-then-right because the chips are laid out in this order, and a control offering
    // "Right" on the left of "Left" is asking to be misread. Gson stores an enum by name, so the
    // order here is presentation only and no saved preset is affected by changing it.
    TOP_LEFT("Left"),
    TOP_RIGHT("Right")
}

/**
 * What the graph says about when it is.
 *
 * A setting rather than a consequence of how many people are on the frame. It used to appear only
 * on multi-wearer exports, for no better reason than that it was written inside the multi-wearer
 * HUD — so a solo export, the commonest kind, had no time on it at all.
 */
enum class ClockMode(val label: String) {
    NONE("Off"),

    /** Wall clock. What lets an export be matched against footage, a set list, or a memory. */
    CLOCK("Time of day"),

    /**
     * How far into the session the playhead is.
     *
     * Measured from when the *earliest* recording on the frame began, which is well defined however
     * many people are on it — unlike "time into the recording", which with five wearers who started
     * five minutes apart is five different numbers.
     */
    ELAPSED("Time into session"),

    /** The time of day, with the time into the session small above it. */
    BOTH("Both")
}

/**
 * How the date beside the clock is written. Each with the year: an export is shared and kept, and a
 * date without one stops saying when within a year.
 */
enum class DateStyle(val pattern: String) {
    MONTH_DAY_YEAR("MMM d, yyyy"),
    NUMERIC("M/d/yyyy"),
    YEAR_MONTH_DAY("yyyy-MM-dd");

    /** How [atMs] reads in [zone] in this style, as the export draws it. */
    fun format(atMs: Long, zone: java.time.ZoneId): String =
        java.time.Instant.ofEpochMilli(atMs).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.getDefault()))
}

/**
 * The app's signature on an export.
 *
 * Deliberately small and deliberately quiet. The thing being shared is someone's footage and their
 * heart rate through it; a mark that competes with either is a mark they will crop out, which is
 * worse than not having put one there. So: one line, no logo, no box.
 *
 * Placed in the top-left corner of the *graph* rather than of the frame. A mark in the corner of a
 * video sits in the part of the picture nobody reads, whereas the graph is the thing the export was
 * made to show, so a credit beside it is a credit that gets seen. [bounds] is therefore the graph's
 * rect, and [scale] the renderer's own, so the mark keeps its proportion when a preset frames the
 * graph small.
 *
 * Drawn by both renderers from here rather than by each of them, because a signature that appears
 * in a different place on a still than on a video is not a signature, it is two of them.
 */
object Wordmark {

    /** What the mark says. One word — this is a credit line, not a title card. */
    const val TEXT = "BPMetrics"

    /**
     * Draws the mark inside [bounds] if [opacityPercent] leaves anything to see.
     *
     * @param scale The renderer's own scale factor, so the mark grows with the rest of the drawing.
     * @param color The label colour of the export, so the mark belongs to the same palette as
     * everything else on the canvas rather than being a fixed white that fights a light preset.
     */
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        opacityPercent: Int,
        color: Int,
        scale: Float,
        paint: Paint
    ) {
        val alpha = (opacityPercent.coerceIn(0, 100) * 255 / 100)
        if (alpha <= 0) return

        paint.resetForExport()
        paint.color = color
        paint.alpha = alpha
        paint.textSize = TEXT_SIZE * scale
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        // Letterspacing rather than size for presence. A credit reads as deliberate when it is
        // small and spaced; the same text simply set larger reads as a watermark.
        paint.letterSpacing = 0.14f

        val inset = INSET * scale
        val metrics = paint.fontMetrics

        // Top left: the title is centered and the clock is on the right, so this corner of the
        // header is the one nothing else uses.
        paint.textAlign = Paint.Align.LEFT
        val x = bounds.left + inset
        val y = bounds.top + inset - metrics.ascent

        canvas.drawText(TEXT, x, y, paint)
        paint.letterSpacing = 0f
    }

    /**
     * Size at the renderer's own scale — a credit line, well under any label beside it.
     *
     * Scaled off the graph rather than the canvas, so a preset that frames the graph small gets a
     * proportionally small mark instead of one sized for a frame it no longer fills.
     */
    private const val TEXT_SIZE = 28f

    /** Tucked into the panel's padding: enough to clear the edge, not enough to float. */
    private const val INSET = 24f
}
