package inga.bpmetrics.ui.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Position's dot pad and size sliders: where a frame goes when a dot is tapped, and where it stays
 * when it is resized.
 */
class GraphPlacementTest {

    private val delta = 0.0001f

    @Test
    fun `a corner dot puts the frame in the corner with a margin and keeps its size`() {
        val placed = GraphPlacement(0.3f, 0.3f, 0.7f, 0.5f).anchoredAt(column = 0, row = 2)

        assertEquals(Anchor.MARGIN, placed.left, delta)
        assertEquals(1f - Anchor.MARGIN, placed.bottom, delta)
        assertEquals(0.4f, placed.width, delta)
        assertEquals(0.2f, placed.height, delta)
        assertEquals(0 to 2, placed.anchor())
    }

    @Test
    fun `the middle dot centers both ways`() {
        val placed = GraphPlacement(0f, 0f, 0.5f, 0.2f).anchoredAt(column = 1, row = 1)

        assertEquals(0.25f, placed.left, delta)
        assertEquals(0.4f, placed.top, delta)
    }

    @Test
    fun `a frame between dots is on none of them`() {
        val (column, row) = GraphPlacement(0.11f, 0.23f, 0.51f, 0.43f).anchor()

        assertNull(column)
        assertNull(row)
    }

    @Test
    fun `a frame too big for the margin goes as close to the edge as it fits`() {
        val placed = GraphPlacement(0f, 0f, 0.97f, 0.5f).anchoredAt(column = 2, row = 0)

        // 3% of room: the margin can't be 4%, so it is split rather than pushed off the canvas.
        assertEquals(0.015f, placed.left, delta)
        assertEquals(0.985f, placed.right, delta)
    }

    @Test
    fun `resizing a frame in a corner grows it out of that corner`() {
        val corner = GraphPlacement(0f, 0f, 0.4f, 0.3f).anchoredAt(column = 0, row = 2)
        val grown = corner.resizedTo(0.6f, 0.4f)

        assertEquals(Anchor.MARGIN, grown.left, delta)
        assertEquals(1f - Anchor.MARGIN, grown.bottom, delta)
        assertEquals(0.6f, grown.width, delta)
        assertEquals(0.4f, grown.height, delta)
    }

    @Test
    fun `resizing a frame placed by hand keeps its middle where it was`() {
        val free = GraphPlacement(0.2f, 0.2f, 0.5f, 0.4f)
        val grown = free.resizedTo(0.4f, 0.3f)

        assertEquals(0.35f, (grown.left + grown.right) / 2f, delta)
        assertEquals(0.3f, (grown.top + grown.bottom) / 2f, delta)
    }

    @Test
    fun `resizing never pushes the frame off the canvas`() {
        val grown = GraphPlacement(0.6f, 0.6f, 0.9f, 0.9f).resizedTo(0.8f, 0.8f)

        assertEquals(1f, grown.right, delta)
        assertEquals(1f, grown.bottom, delta)
        assertEquals(0.8f, grown.width, delta)
    }
}
