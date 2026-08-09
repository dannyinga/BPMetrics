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
        assertEquals(CoverSource.GROUP, effective.source)
    }

    @Test
    fun `an empty collection falls through to its parent`() {
        // Coachella › Day 1 › Subtronics, with a picture only at the top. Day 1 is skipped rather
        // than resolving to nothing, which is what "nearest wins" has to mean to be useful.
        val effective = CoverResolver.resolve(null, null, listOf(null, coachella))!!

        assertEquals(coachella, effective.cover)
        assertEquals(CoverSource.GROUP, effective.source)
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

    @Test
    fun `ancestry runs from the nearest collection to the top`() {
        // Coachella (3) ← Day 1 (2) ← the event's collection is 2.
        val parents = mapOf(2L to 3L, 3L to null)

        assertEquals(listOf(2L, 3L), CoverResolver.ancestryOf(2L, parents))
    }

    @Test
    fun `an unfiled event has no ancestry`() {
        assertEquals(emptyList<Long>(), CoverResolver.ancestryOf(null, mapOf(1L to null)))
    }

    @Test
    fun `a cycle in the collections is walked once rather than forever`() {
        // Should be impossible — CollectionTree refuses to create one — but this walk is the code
        // that hangs rather than throws if one ever exists, and it runs while a list is drawn.
        val cyclic = mapOf(1L to 2L, 2L to 3L, 3L to 1L)

        val chain = CoverResolver.ancestryOf(1L, cyclic)

        assertEquals(listOf(1L, 2L, 3L), chain)
    }

    @Test
    fun `a collection that is its own parent does not hang`() {
        assertEquals(listOf(7L), CoverResolver.ancestryOf(7L, mapOf(7L to 7L)))
    }

    @Test
    fun `resolving from whole maps matches walking the chain by hand`() {
        // The convenience the UI uses. If it disagreed with resolve(), two screens would show
        // different covers for the same recording — which is the failure mode this whole resolver
        // exists to prevent.
        val effective = CoverResolver.forRecording(
            directCover = null,
            eventId = 10L,
            eventCovers = mapOf(10L to null),
            eventGroups = mapOf(10L to 2L),
            groupCovers = mapOf(2L to null, 3L to coachella),
            groupParents = mapOf(2L to 3L, 3L to null)
        )!!

        assertEquals(coachella, effective.cover)
        assertEquals(CoverSource.GROUP, effective.source)
    }

    @Test
    fun `a recording filed nowhere resolves to nothing rather than to the first cover it finds`() {
        assertNull(
            CoverResolver.forRecording(
                directCover = null,
                eventId = null,
                eventCovers = mapOf(10L to subtronics),
                eventGroups = mapOf(10L to 2L),
                groupCovers = mapOf(2L to dayOne),
                groupParents = mapOf(2L to null)
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
