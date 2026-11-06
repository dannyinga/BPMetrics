package inga.bpmetrics.export

import android.graphics.RectF
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.BpmRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun record(id: Long) = BpmRecordWithPoints(
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

    @Test
    fun `every setting survives being saved and read back`() {
        // Field by field, against a preset where nothing is left at its default — so a new field
        // added to the preset without being serialized shows up here rather than as a setting that
        // quietly resets itself the next time the preset is applied.
        val edited = ExportPreset(
            name = "Everything changed",
            width = 1440, height = 2560, lockAspectRatio = false,
            showLabels = false, labelsColor = 0x11223344,
            showGrid = false, gridColor = 0x55667788,
            lowBpmColor = 0x1A2B3C4D, highBpmColor = 0x4D3C2B1A,
            showTitle = false, showCurrentStats = false,
            futureOpacity = 0.17f,
            backgroundOpacity = 37,
            graphLeft = 0.11f, graphTop = 0.22f, graphRight = 0.83f, graphBottom = 0.94f,
            windowSizeMs = 12_345L, frameRate = 50, matchSourceFrameRate = true,
            syncOffsetMs = -1_750L
        )

        val restored = ExportPreset.fromJson(edited.toJson())

        assertEquals(edited, restored)
    }

    @Test
    fun `a preset saved with a time zone still opens, and doesn't carry it on`() {
        // Presets froze the phone's zone until S7; a zone belongs to a location now.
        val old = """
            {"version":1,"name":"Pacific era","width":1920,"height":1080,"frameRate":30,
             "windowSizeMs":30000,"timeZoneId":"America/Los_Angeles"}
        """.trimIndent()

        val restored = ExportPreset.fromJson(old)!!

        assertEquals("Pacific era", restored.name)
        assertTrue("timeZoneId" !in restored.toJson())
    }

    @Test
    fun `a preset saved with the retired fields still opens, and doesn't carry them on`() {
        // S13 removed fields nothing read: the header's x position, and two bit rates the encoder
        // never used (it sizes each render itself). Gson skips keys it doesn't know.
        val old = """
            {"version":4,"name":"Before S13","width":1080,"height":1920,"frameRate":30,
             "windowSizeMs":30000,"headerXPercent":0.85,"overlayBitRate":8000000,
             "regularBitRate":2500000,"showGrid":false}
        """.trimIndent()

        val restored = ExportPreset.fromJson(old)!!

        assertEquals("Before S13", restored.name)
        assertEquals(1080, restored.width)
        assertEquals(false, restored.showGrid)
        val json = restored.toJson()
        listOf("headerXPercent", "overlayBitRate", "regularBitRate").forEach {
            assertTrue("$it should be gone, but the payload was: $json", it !in json)
        }
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
        assertNotNull(restored.name)
        // Absent booleans and longs are legitimately false and zero, and stay that way.
        assertEquals(false, restored.matchSourceFrameRate)
        assertEquals(0L, restored.syncOffsetMs)
    }

    @Test
    fun `nonsense values are repaired rather than carried into a render`() {
        val broken = ExportPreset(
            width = 0, height = -4, frameRate = 0, windowSizeMs = 0L,
            backgroundOpacity = 900, futureOpacity = 4f
        )
        val shipped = ExportPreset()

        val fixed = broken.sanitised()

        assertEquals(shipped.width, fixed.width)
        assertEquals(shipped.height, fixed.height)
        assertEquals(shipped.frameRate, fixed.frameRate)
        assertEquals(shipped.windowSizeMs, fixed.windowSizeMs)
        assertEquals(100, fixed.backgroundOpacity)
        assertEquals(1f, fixed.futureOpacity, 0.0001f)
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

        // Wide since S14A: half the width left the text too small to read (UX Audit O6). Still not
        // edge to edge, for the handles.
        assertTrue("must not span the full width", shipped.graphRight - shipped.graphLeft <= 0.9f)
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
    fun `the built-ins are the clip's own shape, the three worth having, and two looks`() {
        val builtIn = ExportPreset.BUILT_IN

        assertEquals(6, builtIn.size)
        // First, so a fresh install's default takes the clip's shape (UX Audit O7).
        assertTrue(builtIn.first().matchClip)
        assertTrue(builtIn.drop(1).none { it.matchClip })
        assertEquals(
            listOf(1920 to 1080, 1920 to 1080, 1080 to 1920, 1080 to 1080, 1080 to 1920, 1920 to 1080),
            builtIn.map { it.width to it.height }
        )
        // All at the current version, or seeding would write presets the app refuses to read back.
        assertTrue(builtIn.all { it.version == ExportPreset.CURRENT_VERSION })
        // Distinct names: the top-up for existing installs matches on name, so a duplicate would
        // make one of the pair permanently unreachable.
        assertEquals(builtIn.size, builtIn.map { it.name }.distinct().size)
    }

    @Test
    fun `the two look presets sit low and quiet rather than over the footage`() {
        // The reason they exist. A shape preset draws the same full panel in the same place; these
        // are for putting a clip out without the whole apparatus on top of the subject.
        //
        // Not asserted on area: the lower third is a wide band and covers more of the frame than
        // the default panel does. What makes it quieter is that it is dim, gridless and out of the
        // way, which is what this checks.
        val shapes = ExportPreset.BUILT_IN.take(4)
        val looks = ExportPreset.BUILT_IN.drop(4)

        looks.forEach { look ->
            assertFalse("${look.name} still draws a grid", look.showGrid)
            assertTrue(
                "${look.name} is not dimmer than a shape preset",
                look.backgroundOpacity < shapes.minOf { it.backgroundOpacity }
            )
            // Below the middle, so the curve annotates the footage instead of covering it.
            assertTrue("${look.name} reaches into the top half", look.graphTop > 0.5f)
            // And inside the frame, so its handles can still be grabbed in the preview.
            assertTrue("${look.name} runs off the bottom", look.graphBottom < 1f)
        }
    }

    @Test
    fun `the built-ins survive the round trip they are seeded through`() {
        // Seeding writes toJson and every read goes through fromJson, so a built-in that does not
        // survive that trip would ship subtly different from how it is written here.
        ExportPreset.BUILT_IN.forEach { preset ->
            assertEquals(preset, ExportPreset.fromJson(preset.toJson()))
        }
    }

    @Test
    fun `the built-in revision is bumped whenever the list grows`() {
        // The guard on the top-up for existing installs: without a bump, presets added to the list
        // reach a fresh install and nothing else.
        assertTrue(ExportPreset.BUILT_IN_REVISION >= 3)
    }

    // --- Fitting the footage (S14A) ---

    @Test
    fun `a canvas matching the clip takes an upright clip's shape`() {
        val matched = ExportPreset(width = 1920, height = 1080, matchClip = true)

        // A 4K phone clip filmed upright: 1080 on the short side, the clip's own shape.
        val upright = matched.forClip(2160, 3840)
        assertEquals(1080 to 1920, upright.width to upright.height)

        // Sideways, the same.
        val sideways = matched.forClip(3840, 2160)
        assertEquals(1920 to 1080, sideways.width to sideways.height)
    }

    @Test
    fun `a matched canvas is never bigger than the clip`() {
        val matched = ExportPreset(width = 1920, height = 1080, matchClip = true)
        val small = matched.forClip(640, 360)
        assertEquals(640 to 360, small.width to small.height)
    }

    @Test
    fun `a matched canvas has even sides, which the encoder needs`() {
        val matched = ExportPreset(width = 1920, height = 1080, matchClip = true)
        val odd = matched.forClip(1081, 1921)
        assertEquals(0, odd.width % 2)
        assertEquals(0, odd.height % 2)
    }

    @Test
    fun `a fixed canvas, or a clip of unknown size, keeps the preset's size`() {
        val fixed = ExportPreset(width = 1920, height = 1080)
        assertEquals(fixed, fixed.forClip(2160, 3840))

        val matched = ExportPreset(width = 1920, height = 1080, matchClip = true)
        assertEquals(matched, matched.forClip(0, 0))
    }

    @Test
    fun `a preset saved before S14A follows the screen and gets contrast where it needs it`() {
        // A version 4 preset, written before these fields existed. Gson leaves the enums null.
        val old = """{"version":4,"name":"Mine","width":1920,"height":1080,"backgroundOpacity":0}"""
        val read = ExportPreset.fromJson(old)

        assertNotNull(read)
        assertEquals(BpmScale.ON_SCREEN, read!!.bpmScale)
        assertEquals(Contrast.AUTO, read.contrast)
        assertFalse(read.matchClip)
        assertFalse(read.fillCanvas)
    }

    @Test
    fun `text size comes back at 100 percent for an older preset, and stays in bounds`() {
        val old = ExportPreset.fromJson("""{"version":5,"name":"Mine"}""")
        assertEquals(1f, old!!.textScale, 0.0001f)

        val huge = ExportPreset(textScale = 9f).sanitised()
        assertEquals(2f, huge.textScale, 0.0001f)
        val tiny = ExportPreset(textScale = 0.3f).sanitised()
        assertEquals(0.75f, tiny.textScale, 0.0001f)
    }

    @Test
    fun `automatic contrast adds a shadow only where the panel is too faint`() {
        assertEquals(Contrast.SHADOW, Contrast.AUTO.resolvedFor(0))
        assertEquals(Contrast.SHADOW, Contrast.AUTO.resolvedFor(29))
        assertEquals(Contrast.OFF, Contrast.AUTO.resolvedFor(30))
        assertEquals(Contrast.OFF, Contrast.AUTO.resolvedFor(100))
        // A choice made by hand is kept whatever the panel.
        assertEquals(Contrast.OUTLINE, Contrast.OUTLINE.resolvedFor(100))
        assertEquals(Contrast.OFF, Contrast.OFF.resolvedFor(0))
    }

    @Test
    fun `a pill shows everything by default`() {
        val shipped = ExportPreset()

        assertTrue(shipped.pillShowPhoto)
        assertTrue(shipped.pillShowName)
        assertTrue(shipped.pillBpmInPersonColor)
        assertEquals(1f, shipped.pillScale, 0.0001f)
        assertEquals(PillCorner.TOP_RIGHT, shipped.pillCorner)
    }

    @Test
    fun `a pill scale of zero is repaired rather than rendered`() {
        // Zero is not a small pill, it is an absent one — and zero is exactly what a payload
        // written before these fields existed would carry if Gson ever stopped finding the no-arg
        // constructor. See the note on the Gson trap above.
        val shipped = ExportPreset()

        assertEquals(shipped.pillScale, ExportPreset(pillScale = 0f).sanitised().pillScale, 0.0001f)
        assertEquals(
            shipped.pillPhotoScale,
            ExportPreset(pillPhotoScale = 0f).sanitised().pillPhotoScale,
            0.0001f
        )
        assertEquals(
            shipped.pillBpmScale,
            ExportPreset(pillBpmScale = 0f).sanitised().pillBpmScale,
            0.0001f
        )
    }

    @Test
    fun `a deliberate pill scale is kept, and an absurd one is bounded`() {
        assertEquals(0.7f, ExportPreset(pillScale = 0.7f).sanitised().pillScale, 0.0001f)
        assertEquals(2f, ExportPreset(pillScale = 40f).sanitised().pillScale, 0.0001f)
        assertEquals(0.5f, ExportPreset(pillScale = 0.2f).sanitised().pillScale, 0.0001f)
    }

    @Test
    fun `readout settings survive being saved and read back`() {
        val tuned = ExportPreset(
            name = "Six wearers",
            pillShowPhoto = false,
            pillShowName = true,
            pillBpmInPersonColor = false,
            pillScale = 0.8f,
            pillPhotoScale = 1.4f,
            pillBpmScale = 1.2f,
            pillCorner = PillCorner.TOP_RIGHT
        )

        assertEquals(tuned, ExportPreset.fromJson(tuned.toJson()))
    }

    @Test
    fun `a preset written before the readouts existed comes back with a real corner`() {
        // The enum-arrives-null trap again, on a second field.
        val older = """
            {"version":3,"name":"Older","width":1920,"height":1080,
             "graphLeft":0.25,"graphTop":0.58,"graphRight":0.75,"graphBottom":0.91,
             "showLabels":true,"showGrid":true,"showTitle":true,"showCurrentStats":true,
             "backgroundOpacity":100,"windowSizeMs":30000,"frameRate":30,"timeZoneId":"UTC"}
        """.trimIndent()

        val restored = ExportPreset.fromJson(older)!!

        assertNotNull(restored.pillCorner)
        assertEquals(ExportPreset().pillCorner, restored.pillCorner)
        // And a usable size rather than a zero that would render nothing.
        assertTrue(restored.pillScale > 0f)
    }

    @Test
    fun `the wordmark ships off`() {
        // Deliberate, and stated in the product doc: an unfinished app putting its name on
        // someone's footage asks them to publish a mark that is about to change.
        assertFalse(ExportPreset().showWordmark)
        assertTrue(ExportPreset.BUILT_IN.none { it.showWordmark })
    }

    @Test
    fun `a preset written before the wordmark existed comes back with a real corner`() {
        // The trap this guards is an absent enum arriving as null in a field the type system swears
        // cannot be null, so the first thing to touch it throws.
        //
        // It does not currently bite, and the reason is worth writing down: every parameter of
        // ExportPreset has a default, so Kotlin emits a synthetic no-arg constructor, Gson finds it
        // and the Kotlin defaults apply. That is a property of the class, not a guarantee from
        // Gson — add one parameter without a default and the whole class silently reverts to
        // zero-filling. sanitised() is what makes it true either way, and this asserts the outcome
        // rather than the mechanism.
        val beforeWordmark = """
            {"version":2,"name":"Older","width":1920,"height":1080,
             "graphLeft":0.25,"graphTop":0.58,"graphRight":0.75,"graphBottom":0.91,
             "showLabels":true,"showGrid":true,"showTitle":true,"showCurrentStats":true,
             "backgroundOpacity":100,"windowSizeMs":30000,"frameRate":30,
             "timeZoneId":"UTC"}
        """.trimIndent()

        val restored = ExportPreset.fromJson(beforeWordmark)!!

        assertNotNull(restored.wordmarkCorner)
        assertEquals(ExportPreset().wordmarkCorner, restored.wordmarkCorner)
        assertFalse(restored.showWordmark)
        // A usable opacity rather than a zero that would render an invisible mark the moment
        // someone switched the wordmark on.
        assertEquals(ExportPreset().wordmarkOpacity, restored.wordmarkOpacity)
        assertTrue(restored.wordmarkOpacity in 1..100)
    }

    @Test
    fun `a wordmark opacity outside the range is brought back into it`() {
        assertEquals(100, ExportPreset(wordmarkOpacity = 4000).sanitised().wordmarkOpacity)
        assertEquals(0, ExportPreset(wordmarkOpacity = -7).sanitised().wordmarkOpacity)
    }

    @Test
    fun `the wordmark travels with the look it was saved in`() {
        val signed = ExportPreset(
            name = "Signed",
            showWordmark = true,
            wordmarkCorner = WordmarkCorner.TOP_LEFT,
            wordmarkOpacity = 40
        )

        val restored = ExportPreset.fromJson(signed.toJson())!!

        assertTrue(restored.showWordmark)
        assertEquals(WordmarkCorner.TOP_LEFT, restored.wordmarkCorner)
        assertEquals(40, restored.wordmarkOpacity)
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

    @Test
    fun `readouts moved off the graph come back where they were put`() {
        val spot = ReadoutSpot(x = 0.1f, y = 0.9f, pin = ReadoutPin.BOTTOM_LEFT)
        val restored = ExportPreset.fromJson(ExportPreset(readouts = spot).toJson())!!
        assertEquals(spot, restored.readouts)
        assertEquals(spot, restored.frameFor(3).readouts)
    }

    @Test
    fun `readouts on the graph stay on it`() {
        assertNull(ExportPreset.fromJson(ExportPreset().toJson())!!.readouts)
        assertNull(ExportPreset.BUILT_IN.first().frameFor(5).readouts)
    }

    @Test
    fun `a spot off the canvas is brought back onto it`() {
        val restored = ExportPreset(readouts = ReadoutSpot(x = 1.4f, y = -0.2f)).sanitised()
        assertEquals(1f, restored.readouts!!.x, 0.0001f)
        assertEquals(0f, restored.readouts!!.y, 0.0001f)
    }

    @Test
    fun `between two pinned casts the readouts move part of the way too`() {
        val two = GraphFrame(readouts = ReadoutSpot(x = 0.2f, y = 0.2f, pin = ReadoutPin.TOP_LEFT))
        val four = GraphFrame(readouts = ReadoutSpot(x = 0.6f, y = 0.6f, pin = ReadoutPin.BOTTOM_RIGHT))
        val preset = ExportPreset(framesByLaneCount = mapOf(2 to two, 4 to four))
        val three = preset.frameFor(3).readouts!!
        assertEquals(0.4f, three.x, 0.0001f)
        assertEquals(0.4f, three.y, 0.0001f)
    }
}
