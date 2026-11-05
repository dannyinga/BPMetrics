package inga.bpmetrics.export

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A video with no footage renders over a still image, and Media3 refuses an image without a frame
 * rate. The call setting it was dropped once already (Audit #35).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoExporterItemTest {

    @Test
    fun `an image backdrop carries the frame rate`() {
        val image = MediaItem.Builder().setImageDurationMs(90_000).build()

        val item = VideoExporter.editedItem(image, emptyList(), isInputImage = true, frameRate = 30)

        assertEquals(30, item.frameRate)
        assertEquals(true, item.removeAudio)
    }

    @Test
    fun `footage keeps its own frame rate`() {
        val footage = MediaItem.Builder().build()

        val item = VideoExporter.editedItem(footage, emptyList(), isInputImage = false, frameRate = 30)

        assertEquals(C.RATE_UNSET_INT, item.frameRate)
        assertEquals(false, item.removeAudio)
    }
}
