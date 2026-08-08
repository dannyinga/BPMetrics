package inga.bpmetrics.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Which corner of the canvas an export signs itself in. */
enum class WordmarkCorner(val label: String) {
    TOP_LEFT("Top left"),
    TOP_RIGHT("Top right"),
    BOTTOM_LEFT("Bottom left"),
    BOTTOM_RIGHT("Bottom right")
}

/**
 * The app's signature on an export.
 *
 * Deliberately small and deliberately quiet. The thing being shared is someone's footage and their
 * heart rate through it; a mark that competes with either is a mark they will crop out, which is
 * worse than not having put one there. So: one line, no logo, no box.
 *
 * Placed in a corner of the *graph* rather than of the frame. A mark in the corner of a video sits
 * in the part of the picture nobody reads — whereas the graph is the thing the export was made to
 * show, so a credit beside it is a credit that gets seen. [bounds] is therefore the graph's rect,
 * and [scale] the renderer's own, so the mark keeps its proportion when a preset frames the graph
 * small.
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
        corner: WordmarkCorner,
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

        val atTop = corner == WordmarkCorner.TOP_LEFT || corner == WordmarkCorner.TOP_RIGHT
        val y = if (atTop) bounds.top + inset - metrics.ascent else bounds.bottom - inset - metrics.descent

        val atLeft = corner == WordmarkCorner.TOP_LEFT || corner == WordmarkCorner.BOTTOM_LEFT
        val x: Float
        if (atLeft) {
            paint.textAlign = Paint.Align.LEFT
            x = bounds.left + inset
        } else {
            paint.textAlign = Paint.Align.RIGHT
            // Trailing letterspacing is added after the last glyph too, so a right-aligned string
            // sits a hair inside where it was asked to. Given back, or the mark looks misaligned
            // against anything else pinned to the same edge.
            x = bounds.right - inset + paint.letterSpacing * paint.textSize
        }

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
