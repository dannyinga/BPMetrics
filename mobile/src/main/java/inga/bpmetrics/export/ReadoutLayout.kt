package inga.bpmetrics.export

/**
 * Where the live readout pills go: one column, pinned at a corner, growing away from it.
 *
 * One column, always (S14C). Two columns kept everyone on a short panel, but a ranking read across
 * and then down is no longer a ranking, and a pill sliding from the bottom of one column to the top
 * of the next to change places read as a jump rather than an overtake.
 *
 * Pills are a fixed size, set by the look, rather than whatever share of the panel was left: five
 * people take more room than two, and the column grows to make it. It only shrinks when growing
 * would take it past the edge of the area it's allowed (the panel while it sits on the graph, the
 * canvas once it's been moved off). Nobody is ever dropped.
 *
 * A pill's width comes from what it says (see [Measure]), up to [column]'s `maxWidth`, so a name
 * gets the room it needs rather than a fixed share of the canvas (UX Audit O4).
 *
 * Pure, so the rule is tested without drawing anything.
 */
internal object ReadoutLayout {

    /** How wide a pill is at a row height, with names cut to fit [allowance] where they must. */
    fun interface Measure {
        fun widthAt(rowHeight: Float, allowance: Float): Float
    }

    /** The space between pills, as a share of a row. */
    const val GAP_SHARE = 0.12f

    /** Where the readouts go: [count] rows of [rowHeight], top to bottom from ([left], [top]). */
    data class Plan(
        val rowHeight: Float,
        val pillWidth: Float,
        /** The width each pill was measured against: what a name may be cut to. */
        val allowance: Float,
        val left: Float,
        val top: Float,
        val gap: Float,
        val count: Int
    ) {
        val blockWidth: Float get() = pillWidth
        val blockHeight: Float get() = count * rowHeight + (count - 1).coerceAtLeast(0) * gap

        /**
         * The top-left corner of the pill in place [slot], or part-way between two places as the
         * ranking animation has it: a pill overtaking another slides straight up past it.
         */
        fun corner(slot: Float): Pair<Float, Float> {
            val s = slot.coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat())
            return left to top + s * (rowHeight + gap)
        }

        fun corner(slot: Int): Pair<Float, Float> = corner(slot.toFloat())
    }

    /**
     * Plans [count] pills pinned by their [pin] corner at ([anchorX], [anchorY]), inside the area
     * from [areaLeft], [areaTop] to [areaRight], [areaBottom].
     *
     * @param rowHeight how tall a pill is, when there's room for it.
     * @param maxWidth the widest a pill may get; past it, names are cut and then the pill shrinks.
     */
    fun column(
        count: Int,
        anchorX: Float,
        anchorY: Float,
        pin: ReadoutPin,
        areaLeft: Float,
        areaTop: Float,
        areaRight: Float,
        areaBottom: Float,
        rowHeight: Float,
        maxWidth: Float,
        measure: Measure
    ): Plan {
        val n = count.coerceAtLeast(1)
        val x = anchorX.coerceIn(areaLeft, areaRight)
        val y = anchorY.coerceIn(areaTop, areaBottom)

        // Room the way it grows, and the row that fills it exactly: n rows and n - 1 gaps, each a
        // share of a row.
        val room = (if (pin.onTop) areaBottom - y else y - areaTop).coerceAtLeast(1f)
        val fitting = room / (n + (n - 1) * GAP_SHARE)
        var row = minOf(rowHeight, fitting).coerceAtLeast(1f)

        val allowance = minOf(maxWidth, areaRight - areaLeft).coerceAtLeast(1f)
        // Shrunk until the pill fits its allowance. Everything on a pill is sized off its row, so
        // one proportional step all but lands it; the repeats absorb what doesn't scale.
        repeat(4) {
            val w = measure.widthAt(row, allowance)
            if (w > allowance) row *= allowance / w
        }
        val width = measure.widthAt(row, allowance).coerceAtMost(allowance)
        val gap = row * GAP_SHARE
        val height = n * row + (n - 1) * gap

        // Grown away from the pin, then held inside the area: a column pinned near an edge slides
        // in rather than hanging off it.
        val left = (if (pin.onLeft) x else x - width).coerceIn(areaLeft, (areaRight - width).coerceAtLeast(areaLeft))
        val top = (if (pin.onTop) y else y - height).coerceIn(areaTop, (areaBottom - height).coerceAtLeast(areaTop))
        return Plan(row, width, allowance, left, top, gap, n)
    }
}
