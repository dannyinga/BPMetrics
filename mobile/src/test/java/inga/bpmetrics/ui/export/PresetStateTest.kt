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
}
