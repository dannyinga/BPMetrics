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
        assertEquals(listOf(7L, 8L), applied.records.map { it.metadata.recordId })

        // Sync offset is not content. It corrects a constant error in how one *phone* stamps its
        // videos, so it belongs to the look that gets reused across every clip that phone filmed —
        // which means a preset overwrites it rather than preserving whatever the config carried.
        assertEquals(0L, applied.syncOffsetMs)
    }

    @Test
    fun `a preset carries its sync offset onto whatever it is applied to`() {
        val corrected = ExportPreset(name = "That old phone", syncOffsetMs = -2_500L)

        assertEquals(-2_500L, corrected.applyTo(contentHeavyConfig()).syncOffsetMs)
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
    fun `every setting survives being saved and read back`() {
        // Field by field, against a preset where nothing is left at its default — so a new field
        // added without a line in `from` or `applyTo` shows up here rather than as a setting that
        // quietly resets itself the next time the preset is applied.
        val edited = ExportPreset(
            name = "Everything changed",
            width = 1440, height = 2560, lockAspectRatio = false,
            showLabels = false, labelsColor = 0x11223344,
            showGrid = false, gridColor = 0x55667788,
            lowBpmColor = 0x1A2B3C4D, highBpmColor = 0x4D3C2B1A,
            showTitle = false, showCurrentStats = false,
            headerXPercent = 0.42f, futureOpacity = 0.17f,
            backgroundOpacity = 37,
            graphLeft = 0.11f, graphTop = 0.22f, graphRight = 0.83f, graphBottom = 0.94f,
            windowSizeMs = 12_345L, frameRate = 50, matchSourceFrameRate = true,
            overlayBitRate = 9_100_000, regularBitRate = 3_200_000,
            syncOffsetMs = -1_750L, timeZoneId = "Europe/Lisbon"
        )

        val restored = ExportPreset.fromJson(edited.toJson())

        assertEquals(edited, restored)
    }

    @Test
    fun `a payload missing newer fields comes back usable`() {
        // What Gson does to a preset written before a field existed: absent means the JVM's zero,
        // not the Kotlin default. A framing of 0,0,0,0 has no area and would draw nothing at all.
        val ancient = """
            {"version":1,"name":"From an old build","width":1920,"height":1080,
             "showLabels":true,"showGrid":true,"showTitle":true,"showCurrentStats":true,
             "backgroundOpacity":100,"windowSizeMs":30000,"frameRate":30}
        """.trimIndent()

        val restored = ExportPreset.fromJson(ancient)!!
        val shipped = ExportPreset()

        assertEquals("From an old build", restored.name)
        // Framing repaired rather than left with no area.
        assertEquals(shipped.graphLeft, restored.graphLeft, 0.0001f)
        assertEquals(shipped.graphBottom, restored.graphBottom, 0.0001f)
        // A non-null String that Gson would have left null.
        assertNotNull(restored.timeZoneId)
        assertTrue(restored.timeZoneId.isNotBlank())
        // Absent booleans and longs are legitimately false and zero, and stay that way.
        assertEquals(false, restored.matchSourceFrameRate)
        assertEquals(0L, restored.syncOffsetMs)
    }

    @Test
    fun `nonsense values are repaired rather than carried into a render`() {
        val broken = ExportPreset(
            width = 0, height = -4, frameRate = 0, windowSizeMs = 0L,
            overlayBitRate = 0, regularBitRate = -1,
            backgroundOpacity = 900, futureOpacity = 4f, headerXPercent = -2f
        )
        val shipped = ExportPreset()

        val fixed = broken.sanitised()

        assertEquals(shipped.width, fixed.width)
        assertEquals(shipped.height, fixed.height)
        assertEquals(shipped.frameRate, fixed.frameRate)
        assertEquals(shipped.windowSizeMs, fixed.windowSizeMs)
        assertEquals(shipped.overlayBitRate, fixed.overlayBitRate)
        assertEquals(shipped.regularBitRate, fixed.regularBitRate)
        assertEquals(100, fixed.backgroundOpacity)
        assertEquals(1f, fixed.futureOpacity, 0.0001f)
        assertEquals(0f, fixed.headerXPercent, 0.0001f)
    }

    @Test
    fun `a preset still carrying an old shipped framing is recognised`() {
        // The bug this exists for: presets are stored as JSON, so a row seeded under an older
        // default keeps that framing forever and reapplies it to every export.
        ExportPreset.SUPERSEDED_FRAMINGS.forEach { (l, t, r, b) ->
            val stale = ExportPreset(name = "Seeded long ago")
                .withFraming(l, t, r, b)

            assertTrue("$l,$t,$r,$b should be recognised as superseded", stale.hasSupersededFraming())
        }
    }

    @Test
    fun `a framing someone actually dragged is left alone`() {
        val chosen = ExportPreset(name = "Mine").withFraming(0.2f, 0.3f, 0.7f, 0.8f)

        assertTrue(!chosen.hasSupersededFraming())
    }

    @Test
    fun `repairing a stale framing lands on what this build ships`() {
        val shipped = ExportPreset()
        val stale = ExportPreset(name = "Seeded long ago").withFraming(0.04f, 0.62f, 0.96f, 0.97f)

        val repaired = stale.withDefaultFraming()

        assertEquals(shipped.graphLeft, repaired.graphLeft, 0.0001f)
        assertEquals(shipped.graphTop, repaired.graphTop, 0.0001f)
        assertEquals(shipped.graphRight, repaired.graphRight, 0.0001f)
        assertEquals(shipped.graphBottom, repaired.graphBottom, 0.0001f)
        // Everything else about the preset survives the repair.
        assertEquals("Seeded long ago", repaired.name)
        assertTrue(!repaired.hasSupersededFraming())
    }

    @Test
    fun `the shipped framing is a portion of the frame, not the whole of it`() {
        // A graph over the entire video hides what it is annotating, and a frame flush to the edges
        // puts its own resize handles on the boundary where they cannot be grabbed.
        val shipped = ExportPreset()

        assertTrue("must not span the full width", shipped.graphRight - shipped.graphLeft <= 0.6f)
        assertTrue("must not span the full height", shipped.graphBottom - shipped.graphTop <= 0.45f)
        assertTrue("must sit in the lower half", shipped.graphTop >= 0.5f)
        // Centred across, so it reads as deliberate rather than nudged.
        assertEquals(shipped.graphLeft, 1f - shipped.graphRight, 0.0001f)
        assertTrue("must be inset from every edge", shipped.graphLeft > 0f && shipped.graphBottom < 1f)
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
