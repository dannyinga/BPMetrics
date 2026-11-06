package inga.bpmetrics.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the live readout pills go (UX Audit O1, O4; S14C).
 *
 * One column of fixed-size pills, pinned at a corner and growing away from it. It shrinks only when
 * growing would take it out of its area, and nobody is ever left out.
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

    // A 1920 × 1080 canvas with a 24 px margin.
    private val area = floatArrayOf(24f, 24f, 1896f, 1056f)

    private fun column(
        count: Int,
        x: Float = 1896f,
        y: Float = 24f,
        pin: ReadoutPin = ReadoutPin.TOP_RIGHT,
        row: Float = 64f,
        maxWidth: Float = 800f,
        bounds: FloatArray = area,
        measure: ReadoutLayout.Measure = pill()
    ) = ReadoutLayout.column(
        count = count,
        anchorX = x,
        anchorY = y,
        pin = pin,
        areaLeft = bounds[0],
        areaTop = bounds[1],
        areaRight = bounds[2],
        areaBottom = bounds[3],
        rowHeight = row,
        maxWidth = maxWidth,
        measure = measure
    )

    private fun assertInside(plan: ReadoutLayout.Plan, bounds: FloatArray = area) {
        val slop = 0.5f
        repeat(plan.count) { slot ->
            val (x, y) = plan.corner(slot)
            assertTrue("place $slot starts left of the area", x >= bounds[0] - slop)
            assertTrue("place $slot starts above the area", y >= bounds[1] - slop)
            assertTrue("place $slot runs past the right", x + plan.pillWidth <= bounds[2] + slop)
            assertTrue("place $slot runs past the bottom", y + plan.rowHeight <= bounds[3] + slop)
        }
    }

    @Test
    fun `pills keep their size as people are added`() {
        val two = column(2)
        val five = column(5)
        assertEquals(64f, two.rowHeight, 0.01f)
        assertEquals(64f, five.rowHeight, 0.01f)
        assertTrue("the column grows", five.blockHeight > two.blockHeight)
    }

    @Test
    fun `one column, top to bottom by place`() {
        val plan = column(5)
        val corners = (0 until 5).map { plan.corner(it) }
        assertTrue(corners.all { it.first == corners.first().first })
        assertTrue(corners.zipWithNext().all { (a, b) -> b.second > a.second })
    }

    @Test
    fun `pinned top right, it hangs down and to the left of the point`() {
        val plan = column(3, x = 1500f, y = 300f)
        assertEquals(1500f, plan.left + plan.pillWidth, 0.01f)
        assertEquals(300f, plan.top, 0.01f)
    }

    @Test
    fun `pinned bottom left, it grows up and to the right`() {
        val plan = column(4, x = 100f, y = 900f, pin = ReadoutPin.BOTTOM_LEFT)
        assertEquals(100f, plan.left, 0.01f)
        assertEquals(900f, plan.top + plan.blockHeight, 0.01f)
    }

    @Test
    fun `it shrinks only when growing would leave its area, and keeps everyone`() {
        // On a short panel: 150 px of room below the pin.
        val panel = floatArrayOf(100f, 700f, 1800f, 850f)
        val plan = column(6, x = 1800f, y = 700f, bounds = panel)
        assertEquals(6, plan.count)
        assertTrue(plan.rowHeight < 64f)
        assertInside(plan, panel)
    }

    @Test
    fun `every cast from one to eight fits wherever it is pinned`() {
        for (count in 1..8) for (pin in ReadoutPin.entries) {
            val x = if (pin.onLeft) 1700f else 200f
            val y = if (pin.onTop) 900f else 150f
            assertInside(column(count, x = x, y = y, pin = pin))
        }
    }

    @Test
    fun `a long name cuts or shrinks the pill rather than passing the width it's allowed`() {
        val plan = column(3, maxWidth = 150f, measure = pill(nameRows = 6f))
        assertTrue(plan.pillWidth <= 150f + 0.01f)
    }

    @Test
    fun `a pill changing places is drawn between them`() {
        val plan = column(3)
        val (_, top0) = plan.corner(0)
        val (_, top1) = plan.corner(1)
        assertEquals((top0 + top1) / 2f, plan.corner(0.5f).second, 0.01f)
    }

    @Test
    fun `a dropped column keeps its place and grows from the canvas corner it's nearest`() {
        val low = ReadoutSpot.at(0.7f, 0.7f, 0.9f, 0.9f)
        assertEquals(ReadoutPin.BOTTOM_RIGHT, low.pin)
        assertEquals(0.9f, low.x, 0.0001f)
        assertEquals(0.9f, low.y, 0.0001f)

        val high = ReadoutSpot.at(0.1f, 0.1f, 0.3f, 0.4f)
        assertEquals(ReadoutPin.TOP_LEFT, high.pin)
        assertEquals(0.1f, high.x, 0.0001f)
        assertEquals(0.1f, high.y, 0.0001f)
    }
}
