package inga.bpmetrics.ui.export

import inga.bpmetrics.export.ExportPreset
import inga.bpmetrics.library.ExportPresetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where an export's look starts, whether it has unsaved changes, and the aspect lock. */
class PresetStateTest {

    private val story = ExportPreset(name = "Story", width = 1080, height = 1920)
    private val storyEntity = ExportPresetEntity(presetId = 4, name = "Story", configJson = story.toJson())
    private val wide = ExportPreset(name = "Wide", width = 1920, height = 1080)
    private val wideDefault = ExportPresetEntity(presetId = 9, name = "Wide", configJson = wide.toJson(), isDefault = true)

    @Test
    fun `the last look used wins over the default preset`() {
        val tweaked = story.copy(showGrid = !story.showGrid)
        val start = startingLook(tweaked.toJson(), lastUsedFrom = storyEntity, default = wideDefault)
        assertEquals(tweaked, start?.preset)
        assertEquals(4L, start?.presetId)
    }

    @Test
    fun `a last look whose preset was deleted is kept, with no preset selected`() {
        val start = startingLook(story.toJson(), lastUsedFrom = null, default = wideDefault)
        assertEquals(story, start?.preset)
        assertNull(start?.presetId)
    }

    @Test
    fun `the default is the first export's look`() {
        val start = startingLook(null, lastUsedFrom = null, default = wideDefault)
        assertEquals(wide, start?.preset)
        assertEquals(9L, start?.presetId)
        assertNull(startingLook(null, null, null))
    }

    @Test
    fun `a look differing from its preset is edited, and one matching it isn't`() {
        assertFalse(isPresetEdited(storyEntity, story, touched = true))
        assertTrue(isPresetEdited(storyEntity, story.copy(height = 1350), touched = false))
    }

    @Test
    fun `with no preset, it's edited once it's been changed`() {
        assertFalse(isPresetEdited(null, story, touched = false))
        assertTrue(isPresetEdited(null, story, touched = true))
    }

    @Test
    fun `a locked width brings the height with it`() {
        // 1080×1920 to 720 wide keeps 9:16.
        assertEquals(1280, CanvasSize.follow(other = 1920, oldSide = 1080, newSide = 720, range = 16..4096))
        // Rounded to an even number, which encoders need.
        assertEquals(1068, CanvasSize.follow(other = 1080, oldSide = 1920, newSide = 1900, range = 16..4096))
        // Kept inside the range.
        assertEquals(4096, CanvasSize.follow(other = 1920, oldSide = 1080, newSide = 4000, range = 16..4096))
    }

    @Test
    fun `a resolution sets the short side and keeps the shape`() {
        assertEquals(1280 to 720, CanvasSize.at(1920, 1080, Resolution.HD, 16..4096))
        assertEquals(3840 to 2160, CanvasSize.at(1920, 1080, Resolution.UHD, 16..4096))
        // Portrait: the short side is the width.
        assertEquals(1440 to 2560, CanvasSize.at(1080, 1920, Resolution.QHD, 16..4096))
        assertEquals(2160 to 2700, CanvasSize.at(1080, 1350, Resolution.UHD, 16..4096))
    }

    @Test
    fun `an aspect ratio sets the shape and keeps the resolution`() {
        assertEquals(1440 to 2560, CanvasSize.shaped(2560, 1440, AspectRatio.TALL, 16..4096))
        assertEquals(720 to 720, CanvasSize.shaped(1280, 720, AspectRatio.SQUARE, 16..4096))
        assertEquals(1080 to 1350, CanvasSize.shaped(1920, 1080, AspectRatio.PORTRAIT, 16..4096))
    }

    @Test
    fun `a canvas too long for the encoders comes down whole, keeping its shape`() {
        // 21:9 at 4K would be 5040 wide.
        val (w, h) = CanvasSize.at(2520, 1080, Resolution.UHD, 16..4096)
        assertEquals(4096, w)
        assertEquals(1756, h)
    }

    @Test
    fun `the named ratio and resolution are read back from the sides`() {
        assertEquals(AspectRatio.WIDE, CanvasSize.ratioOf(3840, 2160))
        assertEquals(AspectRatio.PORTRAIT, CanvasSize.ratioOf(2160, 2700))
        assertEquals(null, CanvasSize.ratioOf(1000, 700))
        assertEquals(Resolution.QHD, CanvasSize.resolutionOf(1440, 2560))
        assertEquals(null, CanvasSize.resolutionOf(1000, 700))
    }
}
