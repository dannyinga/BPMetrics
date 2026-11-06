package inga.bpmetrics.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the live readout pills go (UX Audit O1, O4).
 *
 * The old column had a minimum row height that won over fitting, so from three people it ran off
 * the bottom of the panel and at five the last person was cut off. These pin down the promise that
 * replaced it: the block always fits, and nobody is ever left out.
 */
class ReadoutLayoutTest {

    /**
     * A pill like the real one: face, reading and padding all sized off the row, about 2.4 rows
     * wide, plus a name of [nameRows] rows that is cut to fit whatever allowance is left.
     */
    private fun pill(nameRows: Float = 1.2f) = ReadoutLayout.Measure { rowHeight, allowance ->
        val bare = rowHeight * 2.4f
        val name = rowHeight * nameRows
        if (bare + name <= allowance) bare + name else maxOf(bare, allowance)
    }

    private fun plan(
        count: Int,
        width: Float = 1700f,
        height: Float = 260f,
        onLeft: Boolean = false,
        minRow: Float = 50f,
        maxRow: Float = 170f,
        measure: ReadoutLayout.Measure = pill()
    ) = ReadoutLayout.plan(
        count = count,
        areaLeft = 100f,
        areaTop = 400f,
        areaRight = 100f + width,
        areaBottom = 400f + height,
        onLeft = onLeft,
        margin = 16f,
        gap = 8f,
        maxRowHeight = maxRow,
        minRowHeight = minRow,
        measure = measure
    )

    private fun assertInside(plan: ReadoutLayout.Plan, count: Int, width: Float = 1700f, height: Float = 260f) {
        val slop = 0.5f
        repeat(count) { slot ->
            val (x, y) = plan.corner(slot)
            assertTrue("place $slot starts left of the area", x >= 100f - slop)
            assertTrue("place $slot starts above the area", y >= 400f - slop)
            assertTrue("place $slot runs past the right", x + plan.pillWidth <= 100f + width + slop)
            assertTrue("place $slot runs past the bottom", y + plan.rowHeight <= 400f + height + slop)
        }
    }

    @Test
    fun `every cast from one to eight fits, with a place for everyone`() {
        for (count in 1..8) {
            val plan = plan(count)
            assertTrue("$count people get ${plan.columns * plan.rows} places", plan.columns * plan.rows >= count)
            assertInside(plan, count)
        }
    }

    @Test
    fun `a short lower-third panel still fits five`() {
        // The Granada case on "Landscape · lower third": five people, a plot about 120 px tall.
        val plan = plan(5, height = 120f)
        assertInside(plan, 5, height = 120f)
    }

    @Test
    fun `two people with room get one column`() {
        val plan = plan(2)
        assertEquals(1, plan.columns)
    }

    @Test
    fun `when one column's rows would be unreadable, a second column takes over`() {
        // Five rows in 228 px are 39 px each, under the 50 px floor. Three rows in two columns are 70.
        val plan = plan(5)
        assertEquals(2, plan.columns)
        assertTrue(plan.rowHeight >= 50f)
    }

    @Test
    fun `when nothing is readable, the pills shrink rather than anyone being dropped`() {
        val plan = plan(6, width = 300f, height = 100f)
        assertTrue(plan.columns * plan.rows >= 6)
        assertInside(plan, 6, width = 300f, height = 100f)
    }

    @Test
    fun `on a narrow story panel the pills get narrower rather than spilling over the curve`() {
        // About a 9:16 plot: narrow and tall. One column, held to its share of the width.
        val narrow = plan(2, width = 820f, height = 480f)
        assertEquals(1, narrow.columns)
        assertTrue(narrow.blockWidth <= (820f - 32f) * 0.32f + 0.5f)
        assertInside(narrow, 2, width = 820f, height = 480f)
    }

    @Test
    fun `the block hugs the side the preset chose`() {
        val right = plan(2)
        assertEquals(100f + 1700f - 16f, right.left + right.blockWidth, 0.5f)

        val left = plan(2, onLeft = true)
        assertEquals(100f + 16f, left.left, 0.5f)
    }

    @Test
    fun `places fill the first column top to bottom, then the next`() {
        val plan = plan(5)
        val (x0, y0) = plan.corner(0)
        val (x1, y1) = plan.corner(1)
        val (x3, _) = plan.corner(plan.rows)
        assertEquals(x0, x1, 0.01f)
        assertTrue(y1 > y0)
        assertTrue("the next column is to the right", x3 > x0)
    }

    @Test
    fun `a pill changing places is drawn between them`() {
        val plan = plan(5)
        val (xa, ya) = plan.corner(0)
        val (xb, yb) = plan.corner(1)
        val (xm, ym) = plan.corner(0.5f)
        assertEquals((xa + xb) / 2f, xm, 0.01f)
        assertEquals((ya + yb) / 2f, ym, 0.01f)
    }

    @Test
    fun `a size setting above one can't push pills out of the area`() {
        val big = ReadoutLayout.plan(
            count = 3, areaLeft = 0f, areaTop = 0f, areaRight = 1700f, areaBottom = 260f,
            onLeft = false, margin = 16f, gap = 8f, maxRowHeight = 170f, minRowHeight = 50f,
            sizeScale = 2f, measure = pill()
        )
        assertTrue(big.blockHeight <= 260f - 32f + 0.5f)
    }
}
