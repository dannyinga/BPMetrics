package inga.bpmetrics.export

import kotlin.math.ceil

/**
 * Where the live readout pills go: how many columns, how tall, and where each place in the ranking
 * sits.
 *
 * The pills used to be one column whose rows had a minimum height, and the minimum won over fitting:
 * from three people the column ran past the bottom of the panel, and at five the last person was
 * cut off (UX Audit O1). Here the plan always fits the area it's given and never drops anyone.
 *
 * - **One column** while its rows stay readable, which is the layout the ranking reads best in.
 * - **More columns** when they don't: rows get taller as there are fewer of them, and the block
 *   gets wider, so each extra column is allowed a little more of the plot's width.
 * - **Smaller pills** when nothing reaches readable: the plan with the tallest rows, shrunk until
 *   it fits. Never a person left out.
 *
 * A pill's width comes from what it says (see [Measure]), so a name gets the room it needs up to
 * the column's allowance rather than a fixed share of the panel, which on a 9:16 story left
 * every name as "D…" (UX Audit O4).
 *
 * Pure, so the rule is tested without drawing anything.
 */
internal object ReadoutLayout {

    /** How wide a pill is at a row height, with names cut to fit [allowance] where they must. */
    fun interface Measure {
        fun widthAt(rowHeight: Float, allowance: Float): Float
    }

    /**
     * Where the readouts go.
     *
     * Places are numbered by rank, filling the first column top to bottom and then the next, so the
     * fastest heart is always top left of the block and the order reads like text.
     */
    data class Plan(
        val rowHeight: Float,
        val columns: Int,
        val rows: Int,
        val pillWidth: Float,
        /** The width each pill was measured against: what a name may be cut to. */
        val allowance: Float,
        val left: Float,
        val top: Float,
        val gap: Float
    ) {
        val blockWidth: Float get() = columns * pillWidth + (columns - 1) * gap
        val blockHeight: Float get() = rows * rowHeight + (rows - 1) * gap

        /** The top-left corner of the pill in place [slot]. */
        fun corner(slot: Int): Pair<Float, Float> {
            val s = slot.coerceIn(0, columns * rows - 1)
            val column = s / rows
            val row = s % rows
            return (left + column * (pillWidth + gap)) to (top + row * (rowHeight + gap))
        }

        /**
         * The top-left corner for a pill part-way between places, as the ranking animation has it.
         *
         * Straight from one place to the next, so a pill changing columns slides across rather than
         * jumping.
         */
        fun corner(slot: Float): Pair<Float, Float> {
            val below = kotlin.math.floor(slot).toInt()
            val fraction = slot - below
            val (x0, y0) = corner(below)
            if (fraction <= 0f) return x0 to y0
            val (x1, y1) = corner(below + 1)
            return (x0 + (x1 - x0) * fraction) to (y0 + (y1 - y0) * fraction)
        }
    }

    /**
     * Plans [count] pills inside the area from [areaLeft], [areaTop] to [areaRight], [areaBottom].
     *
     * @param onLeft whether the block hugs the left edge rather than the right.
     * @param margin kept clear between the block and the area's edges.
     * @param gap between pills, both ways.
     * @param maxRowHeight the tallest a row gets, so one person isn't a slab.
     * @param minRowHeight the shortest row that is still readable.
     * @param sizeScale the preset's pill size, applied to what fits; it can shrink pills but never
     *   push them out of the area.
     */
    fun plan(
        count: Int,
        areaLeft: Float,
        areaTop: Float,
        areaRight: Float,
        areaBottom: Float,
        onLeft: Boolean,
        margin: Float,
        gap: Float,
        maxRowHeight: Float,
        minRowHeight: Float,
        sizeScale: Float = 1f,
        measure: Measure
    ): Plan {
        val n = count.coerceAtLeast(1)
        val width = (areaRight - areaLeft - 2 * margin).coerceAtLeast(1f)
        val height = (areaBottom - areaTop - 2 * margin).coerceAtLeast(1f)

        fun option(columns: Int): Plan {
            val rows = ceil(n / columns.toFloat()).toInt()
            val share = (FIRST_COLUMN_SHARE + EXTRA_COLUMN_SHARE * (columns - 1)).coerceAtMost(1f)
            val allowance = ((width * share - gap * (columns - 1)) / columns).coerceAtLeast(1f)

            val fitsHeight = ((height - gap * (rows - 1)) / rows).coerceAtLeast(1f)
            var rowHeight = (minOf(fitsHeight, maxRowHeight) * sizeScale).coerceAtMost(fitsHeight)
            // Shrunk until the pill fits its allowance. Everything on a pill is sized off its row
            // height, so its width scales with it and one proportional step all but lands it; the
            // repeats absorb what doesn't scale, like rounding and names cut to fit.
            repeat(4) {
                val w = measure.widthAt(rowHeight, allowance)
                if (w > allowance) rowHeight *= allowance / w
            }
            val pillWidth = measure.widthAt(rowHeight, allowance).coerceAtMost(allowance)

            val blockWidth = columns * pillWidth + (columns - 1) * gap
            val left = if (onLeft) areaLeft + margin else areaRight - margin - blockWidth
            return Plan(rowHeight, columns, rows, pillWidth, allowance, left, areaTop + margin, gap)
        }

        val options = (1..n).map(::option)
        return options.firstOrNull { it.rowHeight >= minRowHeight }
            ?: options.maxBy { it.rowHeight }
    }

    /**
     * How much of the plot's width one column may claim.
     *
     * A third. The pills sit over the faded future half of the curve, and past about this they stop
     * annotating it and start hiding it.
     */
    private const val FIRST_COLUMN_SHARE = 0.32f

    /** What each further column adds to that, since a block of short rows is worth a wider one. */
    private const val EXTRA_COLUMN_SHARE = 0.2f
}
