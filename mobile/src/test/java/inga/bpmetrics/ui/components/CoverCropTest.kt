package inga.bpmetrics.ui.components

import inga.bpmetrics.library.Cover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop window's arithmetic.
 *
 * Written after the crop UI shipped unable to move at all. The cause was not the gesture plumbing:
 * an imported photograph starts with a window covering the whole image, and a window covering the
 * whole image is already at every one of its own limits, so every pan clamped straight back to
 * where it began. It looked exactly like the drag not being received.
 *
 * Everything here is pure — no Compose, no Android — so the geometry can be checked without a
 * device, which is the only way this class of bug gets caught before someone tries to use it.
 */
class CoverCropTest {

    private val whole = Cover("photo.jpg")

    /** A 3:2 landscape photograph, the ordinary case out of a phone camera. */
    private val landscape = 1.5f

    /** A 2:3 portrait, the other ordinary case. */
    private val portrait = 1f / 1.5f

    private val tile = 2.9f
    private val circle = 1f

    @Test
    fun `an imported photo starts covering everything, which is why it could not move`() {
        assertTrue(whole.isWholeImage)
        assertEquals(1f, whole.cropWidth, 0.0001f)
        assertEquals(1f, whole.cropHeight, 0.0001f)
    }

    @Test
    fun `a fitted window is not the whole image`() {
        // The fix: open on a window that has somewhere to go.
        val fitted = whole.fittedTo(tile, landscape)

        assertFalse(fitted.isWholeImage)
        assertTrue("nothing to pan to vertically", fitted.cropHeight < 1f)
    }

    @Test
    fun `fitting a wide target to a landscape photo keeps the full width`() {
        val fitted = whole.fittedTo(tile, landscape)

        // The tile is wider than the photo, so width is the binding constraint and height gives.
        assertEquals(1f, fitted.cropWidth, 0.0001f)
        assertEquals(landscape / tile, fitted.cropHeight, 0.0001f)
    }

    @Test
    fun `fitting a square target to a landscape photo narrows the width`() {
        val fitted = whole.fittedTo(circle, landscape)

        assertEquals(1f, fitted.cropHeight, 0.0001f)
        assertEquals(circle / landscape, fitted.cropWidth, 0.0001f)
    }

    @Test
    fun `fitting a square target to a portrait photo keeps the full width`() {
        val fitted = whole.fittedTo(circle, portrait)

        assertEquals(1f, fitted.cropWidth, 0.0001f)
        assertEquals(portrait / circle, fitted.cropHeight, 0.0001f)
    }

    @Test
    fun `a fitted window is centred`() {
        val fitted = whole.fittedTo(circle, landscape)

        assertEquals(
            "left and right margins must match",
            fitted.cropLeft,
            1f - fitted.cropRight,
            0.0001f
        )
    }

    @Test
    fun `a fitted window stays inside the image`() {
        listOf(landscape, portrait, 1f, 3.2f, 0.4f).forEach { aspect ->
            listOf(tile, circle).forEach { target ->
                val fitted = whole.fittedTo(target, aspect)
                assertTrue("left", fitted.cropLeft >= -0.0001f)
                assertTrue("top", fitted.cropTop >= -0.0001f)
                assertTrue("right", fitted.cropRight <= 1.0001f)
                assertTrue("bottom", fitted.cropBottom <= 1.0001f)
            }
        }
    }

    @Test
    fun `panning a fitted window actually moves it`() {
        // The whole point. This is the assertion that would have failed before the fix.
        val fitted = whole.fittedTo(tile, landscape)

        val moved = fitted.transformed(zoom = 1f, panX = 0f, panY = 0.2f, aspect = tile, imageAspect = landscape)

        assertTrue("the window did not move", moved.cropTop > fitted.cropTop + 0.001f)
    }

    @Test
    fun `panning does not change how much is shown`() {
        val fitted = whole.fittedTo(tile, landscape)

        val moved = fitted.transformed(zoom = 1f, panX = 0f, panY = 0.15f, aspect = tile, imageAspect = landscape)

        assertEquals(fitted.cropWidth, moved.cropWidth, 0.0001f)
        assertEquals(fitted.cropHeight, moved.cropHeight, 0.0001f)
    }

    @Test
    fun `panning stops at the edge rather than running off it`() {
        val fitted = whole.fittedTo(tile, landscape)

        var moved = fitted
        repeat(20) {
            moved = moved.transformed(zoom = 1f, panX = 0f, panY = 1f, aspect = tile, imageAspect = landscape)
        }

        assertEquals(1f, moved.cropBottom, 0.0001f)
        assertTrue(moved.cropTop >= -0.0001f)
    }

    @Test
    fun `pinching apart shows less of the picture`() {
        val fitted = whole.fittedTo(tile, landscape)

        val zoomed = fitted.transformed(zoom = 2f, panX = 0f, panY = 0f, aspect = tile, imageAspect = landscape)

        assertTrue("zooming in must show less", zoomed.cropWidth < fitted.cropWidth)
        assertEquals(fitted.cropWidth / 2f, zoomed.cropWidth, 0.0001f)
    }

    @Test
    fun `pinching together shows more, up to the whole fitted window`() {
        val fitted = whole.fittedTo(tile, landscape)
        val zoomed = fitted.transformed(zoom = 4f, panX = 0f, panY = 0f, aspect = tile, imageAspect = landscape)

        var out = zoomed
        repeat(10) {
            out = out.transformed(zoom = 0.5f, panX = 0f, panY = 0f, aspect = tile, imageAspect = landscape)
        }

        // Back to the fitted window and no further — a window wider than this would be cropped
        // again by the renderer, so it would show one thing here and another in the library.
        assertEquals(fitted.cropWidth, out.cropWidth, 0.0001f)
        assertEquals(fitted.cropHeight, out.cropHeight, 0.0001f)
    }

    @Test
    fun `zoom keeps the target's shape`() {
        val fitted = whole.fittedTo(tile, landscape)

        val zoomed = fitted.transformed(zoom = 1.7f, panX = 0f, panY = 0f, aspect = tile, imageAspect = landscape)

        // Width and height are fractions of a landscape image, so the drawn aspect is
        // (width / height) × imageAspect.
        val drawnAspect = zoomed.cropWidth / zoomed.cropHeight * landscape
        assertEquals(tile, drawnAspect, 0.01f)
    }

    @Test
    fun `zoom keeps a circle's shape too`() {
        val fitted = whole.fittedTo(circle, portrait)

        val zoomed = fitted.transformed(zoom = 2.5f, panX = 0f, panY = 0f, aspect = circle, imageAspect = portrait)

        val drawnAspect = zoomed.cropWidth / zoomed.cropHeight * portrait
        assertEquals(circle, drawnAspect, 0.01f)
    }

    @Test
    fun `zooming in far enough stops rather than collapsing to nothing`() {
        val fitted = whole.fittedTo(tile, landscape)

        var zoomed = fitted
        repeat(30) {
            zoomed = zoomed.transformed(zoom = 2f, panX = 0f, panY = 0f, aspect = tile, imageAspect = landscape)
        }

        assertEquals(MIN_CROP * fitted.cropWidth, zoomed.cropWidth, 0.0001f)
        assertTrue(zoomed.cropWidth > 0f)
        assertTrue(zoomed.cropHeight > 0f)
    }

    @Test
    fun `zooming keeps the window inside the image`() {
        var window = whole.fittedTo(tile, landscape)

        // A wander: zoom in, pan to a corner, zoom back out. The last step is the one that can push
        // an edge out of the image if the clamp is applied before the resize rather than after.
        window = window.transformed(zoom = 3f, panX = 0f, panY = 0f, aspect = tile, imageAspect = landscape)
        window = window.transformed(zoom = 1f, panX = 1f, panY = 1f, aspect = tile, imageAspect = landscape)
        window = window.transformed(zoom = 0.2f, panX = 0f, panY = 0f, aspect = tile, imageAspect = landscape)

        assertTrue("left", window.cropLeft >= -0.0001f)
        assertTrue("top", window.cropTop >= -0.0001f)
        assertTrue("right", window.cropRight <= 1.0001f)
        assertTrue("bottom", window.cropBottom <= 1.0001f)
    }

    @Test
    fun `an image whose size never loaded is left alone rather than mangled`() {
        // rememberCoverSize is null until the file has been read, and a gesture can land first.
        val fitted = whole.fittedTo(tile, landscape)

        val moved = fitted.transformed(zoom = 1.5f, panX = 0.1f, panY = 0f, aspect = tile, imageAspect = null)

        assertTrue(moved.cropWidth > 0f)
        assertTrue(moved.cropHeight > 0f)
        assertTrue(moved.cropRight <= 1.0001f)
        assertTrue(moved.cropBottom <= 1.0001f)
    }

    @Test
    fun `a zero zoom does not divide the window away`() {
        // detectTransformGestures reports zoom as a ratio, and a degenerate frame can report zero.
        val fitted = whole.fittedTo(tile, landscape)

        val moved = fitted.transformed(zoom = 0f, panX = 0f, panY = 0f, aspect = tile, imageAspect = landscape)

        assertEquals(fitted.cropWidth, moved.cropWidth, 0.0001f)
    }

    @Test
    fun `a square photo in a square target uses all of it`() {
        val fitted = whole.fittedTo(circle, 1f)

        assertEquals(1f, fitted.cropWidth, 0.0001f)
        assertEquals(1f, fitted.cropHeight, 0.0001f)
    }
}
