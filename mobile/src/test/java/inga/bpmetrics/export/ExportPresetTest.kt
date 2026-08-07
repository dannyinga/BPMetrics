package inga.bpmetrics.export

import android.graphics.RectF
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Before
import org.junit.Test

/**
 * Covers what a preset is allowed to remember.
 *
 * The failure this guards against is a preset that carries content: a record id, a time range, an
 * overlay video. One of those would make the preset stop working the moment those recordings were
 * gone, which is the opposite of what saving one is for — and it would silently retarget an export
 * at recordings the user did not choose.
 */
class ExportPresetTest {

    @Before
    fun setUp() {
        // Uri is a stub in JVM tests. Only its identity matters here — the point is that a preset
        // cannot carry one, not what it parses to.
        mockkStatic(android.net.Uri::class)
        every { android.net.Uri.parse(any()) } returns io.mockk.mockk(relaxed = true)
    }

    private fun record(id: Long) = BpmRecord(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Record $id",
            date = 0L,
            startTime = 0L,
            endTime = 1000L,
            durationMs = 1000L
        ),
        dataPoints = emptyList(),
        minDataPoint = null,
        maxDataPoint = null
    )

    /** A config with every content-specific field populated, so stripping has something to fail at. */
    private fun contentHeavyConfig() = VideoExporter.VideoExportConfig(
        imageConfig = ImageExporter.ImageExportConfig(
            width = 1080,
            height = 1920,
            // Deliberately odd values. Round numbers collide with the bitrate defaults — the
            // first version of the stripping test failed because "2500000" contains "500000".
            startTimeMs = 987_654_321L,
            endTimeMs = 876_543_219L,
            customRecordColors = mapOf(7L to 0xFFFF0000.toInt()),
            graphTitle = "Subtronics 2026",
            showGrid = false,
            headerXPercent = 0.4f
        ),
        windowSizeMs = 45_000L,
        frameRate = 60,
        overlayVideoUri = android.net.Uri.parse("content://media/external/video/media/42"),
        graphRect = RectF(0.1f, 0.2f, 0.9f, 0.8f),
        lockAspectRatio = false,
        syncOffsetMs = 1234L,
        records = listOf(record(7), record(8))
    )

    @Test
    fun `appearance survives a round trip`() {
        val preset = ExportPreset.from(contentHeavyConfig(), name = "Story")

        val restored = ExportPreset.fromJson(preset.toJson())!!

        assertEquals("Story", restored.name)
        assertEquals(1080, restored.width)
        assertEquals(1920, restored.height)
        assertEquals(45_000L, restored.windowSizeMs)
        assertEquals(60, restored.frameRate)
        assertEquals(false, restored.showGrid)
        assertEquals(false, restored.lockAspectRatio)
        assertEquals(0.4f, restored.headerXPercent, 0.001f)
    }

    @Test
    fun `applying a preset leaves the content alone`() {
        // The whole point. A preset says how an export looks; the config says what it is of.
        val original = contentHeavyConfig()
        val preset = ExportPreset(name = "Square", width = 1080, height = 1080, showGrid = true)

        val applied = preset.applyTo(original)

        assertEquals(1080, applied.imageConfig.width)
        assertEquals(1080, applied.imageConfig.height)
        assertEquals(true, applied.imageConfig.showGrid)

        // Content is untouched.
        assertEquals(987_654_321L, applied.imageConfig.startTimeMs)
        assertEquals(876_543_219L, applied.imageConfig.endTimeMs)
        assertEquals(mapOf(7L to 0xFFFF0000.toInt()), applied.imageConfig.customRecordColors)
        assertEquals("Subtronics 2026", applied.imageConfig.graphTitle)
        assertEquals(original.overlayVideoUri, applied.overlayVideoUri)
        assertEquals(1234L, applied.syncOffsetMs)
        assertEquals(listOf(7L, 8L), applied.records.map { it.metadata.recordId })
    }

    @Test
    fun `no content-specific value reaches the serialized form`() {
        // Checked against the text rather than the fields, because the risk is someone adding a
        // property to ExportPreset later without noticing what it drags along.
        val json = ExportPreset.from(contentHeavyConfig(), name = "Story").toJson()

        listOf(
            "987654321",       // startTimeMs
            "876543219",       // endTimeMs
            "Subtronics 2026", // graphTitle
            "content://media", // overlayVideoUri
            "syncOffset",
            "customRecordColors",
            "records"
        ).forEach { forbidden ->
            assertTrue(
                "a preset must not carry $forbidden, but the payload was: $json",
                !json.contains(forbidden)
            )
        }
    }

    @Test
    fun `a preset from a newer build is refused rather than half-applied`() {
        val fromTheFuture = ExportPreset(version = ExportPreset.CURRENT_VERSION + 1, name = "Later")

        assertNull(ExportPreset.fromJson(fromTheFuture.toJson()))
    }

    @Test
    fun `a preset from this build is accepted`() {
        val mine = ExportPreset(version = ExportPreset.CURRENT_VERSION, name = "Mine")

        assertNotNull(ExportPreset.fromJson(mine.toJson()))
    }

    @Test
    fun `malformed json is refused rather than throwing`() {
        assertNull(ExportPreset.fromJson("not json at all {{{"))
        assertNull(ExportPreset.fromJson(""))
    }

    @Test
    fun `the built-ins are the three shapes worth having`() {
        val builtIn = ExportPreset.BUILT_IN

        assertEquals(3, builtIn.size)
        assertEquals(
            listOf(1920 to 1080, 1080 to 1920, 1080 to 1080),
            builtIn.map { it.width to it.height }
        )
        // All at the current version, or seeding would write presets the app refuses to read back.
        assertTrue(builtIn.all { it.version == ExportPreset.CURRENT_VERSION })
    }

    /**
     * Graph placement is stored as fractions, not pixels.
     *
     * Asserted on the preset's own fields rather than on a round trip through `RectF`: that class
     * is a stub in JVM tests and reads back as zeroes, so a test going through it would be
     * measuring the stub. The fractions are the part that can silently break — storing pixels here
     * is what would leave a 16:9 graph off the bottom of a 9:16 canvas.
     */
    @Test
    fun `graph placement is stored as fractions so it survives an aspect change`() {
        val preset = ExportPreset(
            width = 1920,
            height = 1080,
            graphLeft = 0.05f,
            graphTop = 0.6f,
            graphRight = 0.95f,
            graphBottom = 0.98f
        )

        val restored = ExportPreset.fromJson(preset.copy(width = 1080, height = 1920).toJson())!!

        assertEquals(0.05f, restored.graphLeft, 0.001f)
        assertEquals(0.6f, restored.graphTop, 0.001f)
        assertEquals(0.95f, restored.graphRight, 0.001f)
        assertEquals(0.98f, restored.graphBottom, 0.001f)
        // The canvas changed and the placement did not, which is the whole point.
        assertEquals(1080, restored.width)
        assertEquals(1920, restored.height)
    }
}
