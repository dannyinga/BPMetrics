package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which picture stands for a recording.
 *
 * The design this guards: a cover set on a collection applies to everything under it because it is
 * *found* there when asked, never because it was copied down. That is what makes a recording
 * arriving late from a watch pick up the right picture on its own — the entire reason for putting
 * the cover on the event rather than on the recording.
 */
class CoverResolverTest {

    private val coachella = Cover("coachella.jpg")
    private val dayOne = Cover("day-one.jpg")
    private val subtronics = Cover("subtronics.jpg")
    private val mine = Cover("mine.jpg")

    @Test
    fun `a recording with nothing above it has no cover`() {
        assertNull(CoverResolver.resolve(null, null, emptyList()))
    }

    @Test
    fun `an unfiled recording still shows its own cover`() {
        val effective = CoverResolver.resolve(mine, null, emptyList())!!

        assertEquals(mine, effective.cover)
        assertEquals(CoverSource.DIRECT, effective.source)
        assertTrue(!effective.isInherited)
    }

    @Test
    fun `a recording takes its event's cover`() {
        val effective = CoverResolver.resolve(null, subtronics, listOf(dayOne, coachella))!!

        assertEquals(subtronics, effective.cover)
        assertEquals(CoverSource.EVENT, effective.source)
        assertTrue(effective.isInherited)
    }

    @Test
    fun `an event with no cover falls through to its collection`() {
        val effective = CoverResolver.resolve(null, null, listOf(dayOne, coachella))!!

        assertEquals(dayOne, effective.cover)
        assertEquals(CoverSource.ANCESTOR, effective.source)
    }

    @Test
    fun `an empty collection falls through to its parent`() {
        // Coachella › Day 1 › Subtronics, with a picture only at the top. Day 1 is skipped rather
        // than resolving to nothing, which is what "nearest wins" has to mean to be useful.
        val effective = CoverResolver.resolve(null, null, listOf(null, coachella))!!

        assertEquals(coachella, effective.cover)
        assertEquals(CoverSource.ANCESTOR, effective.source)
    }

    @Test
    fun `the nearest cover wins all the way down`() {
        assertEquals(
            mine,
            CoverResolver.resolve(mine, subtronics, listOf(dayOne, coachella))!!.cover
        )
        assertEquals(
            subtronics,
            CoverResolver.resolve(null, subtronics, listOf(dayOne, coachella))!!.cover
        )
        assertEquals(
            dayOne,
            CoverResolver.resolve(null, null, listOf(dayOne, coachella))!!.cover
        )
    }

    // Ancestry itself is no longer tested here. This resolver had its own copy of that walk, cycle
    // guard and all, until TX-1.4 pointed it at EventTree — where the same properties are asserted
    // once, against the walk that also decides membership, counts and spans. Two walks tested
    // separately is how they came to disagree.

    /** Subtronics (10) inside Day 1 (2) inside Coachella (3). */
    private fun festival() = listOf(
        EventEntity(eventId = 3, name = "Coachella"),
        EventEntity(eventId = 2, name = "Day 1", parentId = 3),
        EventEntity(eventId = 10, name = "Subtronics", parentId = 2)
    )

    @Test
    fun `a cover is found however far up the tree it was set`() {
        // The convenience the UI uses. If it disagreed with resolve(), two screens would show
        // different covers for the same recording — which is the failure mode this whole resolver
        // exists to prevent.
        val effective = CoverResolver.forRecording(
            directCover = null,
            eventId = 10L,
            eventCovers = mapOf(10L to null, 2L to null, 3L to coachella),
            events = festival()
        )!!

        assertEquals(coachella, effective.cover)
        assertEquals(CoverSource.ANCESTOR, effective.source)
    }

    @Test
    fun `the nearest cover in the tree wins`() {
        val effective = CoverResolver.forRecording(
            directCover = null,
            eventId = 10L,
            eventCovers = mapOf(10L to null, 2L to dayOne, 3L to coachella),
            events = festival()
        )!!

        assertEquals(dayOne, effective.cover)
    }

    @Test
    fun `an event's own cover is not reported as inherited`() {
        val effective = CoverResolver.forRecording(
            directCover = null,
            eventId = 10L,
            eventCovers = mapOf(10L to subtronics, 3L to coachella),
            events = festival()
        )!!

        assertEquals(subtronics, effective.cover)
        assertEquals(CoverSource.EVENT, effective.source)
    }

    @Test
    fun `a recording filed nowhere resolves to nothing rather than to the first cover it finds`() {
        assertNull(
            CoverResolver.forRecording(
                directCover = null,
                eventId = null,
                eventCovers = mapOf(10L to subtronics),
                events = festival()
            )
        )
    }

    @Test
    fun `a cover with no path is no cover`() {
        assertNull(Cover.of(null, 0f, 0f, 1f, 1f))
        assertNull(Cover.of("", 0f, 0f, 1f, 1f))
        assertNull(Cover.of("   ", 0f, 0f, 1f, 1f))
    }

    @Test
    fun `absent crop columns mean the whole image`() {
        // What a row written before the crop columns existed looks like.
        val cover = Cover.of("a.jpg", null, null, null, null)!!

        assertEquals(0f, cover.cropLeft, 0.0001f)
        assertEquals(1f, cover.cropRight, 0.0001f)
        assertEquals(1f, cover.cropWidth, 0.0001f)
        assertEquals(1f, cover.cropHeight, 0.0001f)
    }

    @Test
    fun `a crop with no area shows the whole image rather than nothing`() {
        // An invisible cover is indistinguishable from a bug, and zeroes are exactly what a
        // half-written row contains.
        val cover = Cover.of("a.jpg", 0f, 0f, 0f, 0f)!!

        assertEquals(1f, cover.cropWidth, 0.0001f)
        assertEquals(1f, cover.cropHeight, 0.0001f)
    }

    @Test
    fun `a crop outside the image is brought back inside it`() {
        val cover = Cover.of("a.jpg", -0.5f, -2f, 1.4f, 9f)!!

        assertEquals(0f, cover.cropLeft, 0.0001f)
        assertEquals(0f, cover.cropTop, 0.0001f)
        assertEquals(1f, cover.cropRight, 0.0001f)
        assertEquals(1f, cover.cropBottom, 0.0001f)
    }

    @Test
    fun `a real crop is kept as given`() {
        val cover = Cover.of("a.jpg", 0.125f, 0.25f, 0.875f, 0.75f)!!

        assertEquals(0.75f, cover.cropWidth, 0.0001f)
        assertEquals(0.5f, cover.cropHeight, 0.0001f)
    }

    @Test
    fun `an entity with no cover offers none`() {
        assertNull(EventEntity(eventId = 1, name = "Subtronics").ownCover)
        assertNull(EventGroupEntity(groupId = 1, name = "Coachella").ownCover)
    }

    @Test
    fun `an entity carries its own cover through`() {
        val event = EventEntity(
            eventId = 1,
            name = "Subtronics",
            coverPath = "subtronics.jpg",
            coverCropLeft = 0.1f,
            coverCropTop = 0.2f,
            coverCropRight = 0.9f,
            coverCropBottom = 0.8f
        )

        val cover = event.ownCover!!

        assertEquals("subtronics.jpg", cover.path)
        assertEquals(0.8f, cover.cropWidth, 0.0001f)
        assertEquals(0.6f, cover.cropHeight, 0.0001f)
    }
}
