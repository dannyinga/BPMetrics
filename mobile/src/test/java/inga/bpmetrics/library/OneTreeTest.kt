package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That there is one tree, and one walk over it.
 *
 * Before the fold a festival was a collection, a day was a collection, a set was an event and a
 * recording hung off the set — four levels described by two different parent links, walked by two
 * different pieces of code. Every count, span, tag inheritance and cover inheritance existed twice.
 * That is not an abstract tidiness problem: it produced four separate "0 recordings" defects, each
 * one of the two walks disagreeing with the other about what a container held.
 *
 * These tests are written against the arrangement that used to break — a three-level structure
 * mixing collections and events — and assert that walking it gives the same answer whichever level
 * you ask from, because there is now only one thing to ask.
 */
class OneTreeTest {

    private val day = 1_700_000_000_000L
    private fun at(hours: Int, minutes: Int = 0) = day + hours * 3_600_000L + minutes * 60_000L

    /**
     * Griztronics (collection) › Day 1 (collection) › Subtronics (event) › recordings.
     *
     * The shape that used to need both mechanisms at once, and the one every count got wrong.
     */
    private fun festival() = listOf(
        EventEntity(eventId = 1, name = "Griztronics", type = COLLECTION_TYPE),
        EventEntity(eventId = 2, name = "Day 1", parentId = 1, type = COLLECTION_TYPE),
        EventEntity(eventId = 3, name = "Subtronics", parentId = 2, windowStart = at(21), windowEnd = at(22)),
        EventEntity(eventId = 4, name = "Excision", parentId = 2, windowStart = at(23), windowEnd = at(24))
    )

    private fun recording(id: Long, startsAt: Long) = BpmRecordEntity(
        recordId = id,
        title = "Record $id",
        date = startsAt,
        startTime = startsAt,
        endTime = startsAt + 600_000L,
        durationMs = 600_000L
    )

    private fun recordings() = listOf(recording(10, at(21, 15)), recording(11, at(23, 15)))

    @Test
    fun `a collection contains what is nested three levels below it`() {
        // The defect, exactly: counting one level down found no recordings under the festival,
        // because everything it held was held by something else.
        val within = EventTree.descendantsOf(festival(), 1)
        val membership = EventMembership.resolve(festival(), emptyMap(), recordings())

        assertEquals(2, recordings().count { membership[it.recordId] in within })
    }

    @Test
    fun `a collection is an event as far as every walk is concerned`() {
        // No branch anywhere on `isCollection`. The label is for the screens; the tree does not
        // care, which is the entire point of the fold.
        assertEquals(setOf(1L, 2L, 3L, 4L), EventTree.descendantsOf(festival(), 1))
        assertEquals(listOf(3L, 2L, 1L), EventTree.ancestryOf(festival(), 3).map { it.eventId })
    }

    @Test
    fun `a tag on the festival reaches a recording two levels down`() {
        val loud = TagEntity(tagId = 1, name = "Loud", parentCategoryId = 1)
        val ancestry = EventTree.ancestryOf(festival(), 3).map { it.eventId }

        val effective = EffectiveTagsResolver.resolve(
            directTags = emptyList(),
            ancestry = ancestry,
            eventTags = mapOf(1L to listOf(loud))
        )

        assertEquals(listOf(loud), effective.map { it.tag })
        assertEquals(TagSource.ANCESTOR, effective.single().source)
    }

    @Test
    fun `a cover on the festival reaches a recording two levels down`() {
        val flyer = Cover("griztronics.jpg")

        val effective = CoverResolver.forRecording(
            directCover = null,
            eventId = 3L,
            eventCovers = mapOf(1L to flyer),
            events = festival()
        )!!

        assertEquals(flyer, effective.cover)
        assertEquals(CoverSource.ANCESTOR, effective.source)
    }

    @Test
    fun `tags and covers agree about which level is nearest`() {
        // Two resolvers, one tree. They used to take different maps and assemble their own chains,
        // so "nearest" could mean the day to one and the festival to the other.
        val dayTag = TagEntity(tagId = 1, name = "Saturday", parentCategoryId = 1)
        val festivalTag = TagEntity(tagId = 2, name = "Griztronics", parentCategoryId = 1)
        val dayCover = Cover("day-one.jpg")
        val festivalCover = Cover("griztronics.jpg")

        val tags = EffectiveTagsResolver.resolve(
            directTags = emptyList(),
            ancestry = EventTree.ancestryOf(festival(), 3).map { it.eventId },
            eventTags = mapOf(2L to listOf(dayTag), 1L to listOf(festivalTag))
        )
        val cover = CoverResolver.forRecording(
            directCover = null,
            eventId = 3L,
            eventCovers = mapOf(2L to dayCover, 1L to festivalCover),
            events = festival()
        )!!

        // Both reach for Day 1 first.
        assertEquals(dayTag, tags.first().tag)
        assertEquals(dayCover, cover.cover)
    }

    @Test
    fun `an event moved under a collection takes its inheritance with it`() {
        // Filing is one write to one column, and everything downstream follows from the walk. This
        // is what "nothing is copied down" buys: no cleanup step to forget.
        val loud = TagEntity(tagId = 1, name = "Loud", parentCategoryId = 1)
        val orphan = EventEntity(eventId = 9, name = "Unfiled set")
        val before = festival() + orphan

        assertTrue(
            EffectiveTagsResolver.resolve(
                emptyList(),
                EventTree.ancestryOf(before, 9).map { it.eventId },
                mapOf(1L to listOf(loud))
            ).isEmpty()
        )

        val after = festival() + orphan.copy(parentId = 2)

        assertEquals(
            listOf(loud),
            EffectiveTagsResolver.resolve(
                emptyList(),
                EventTree.ancestryOf(after, 9).map { it.eventId },
                mapOf(1L to listOf(loud))
            ).map { it.tag }
        )
    }

    @Test
    fun `a collection with a window claims recordings like any other event`() {
        // Nothing stops a collection holding recordings directly. It is an event; the only thing
        // that made it different was living in another table.
        val withWindow = listOf(
            EventEntity(
                eventId = 1, name = "Griztronics", type = COLLECTION_TYPE,
                windowStart = at(0), windowEnd = at(48)
            )
        )

        assertEquals(
            1L,
            EventMembership.resolve(withWindow, emptyMap(), listOf(recording(10, at(21))))[10L]
        )
    }

    // --- Scope: what a picker promises and what an export delivers ---

    /** What "export this container" resolves to. One line, because there is one rule. */
    private fun scopeOf(events: List<EventEntity>, records: List<BpmRecordEntity>, rootId: Long) =
        EventTree.descendantsOf(events, rootId).let { within ->
            records.filter { it.eventId in within }
        }

    @Test
    fun `exporting a container reaches every level beneath it`() {
        val membership = EventMembership.resolve(festival(), emptyMap(), recordings())
        val filed = recordings().map { it.copy(eventId = membership[it.recordId]) }

        // Both recordings live on sets, two levels under the festival.
        assertEquals(2, scopeOf(festival(), filed, 1).size)
    }

    @Test
    fun `exporting a container includes what is filed directly onto it`() {
        // Possible since recordings can be filed into any event, containers included — the walk
        // between stages, the queue. Taking only the events *beneath* the container dropped these
        // silently, and a short export looks like a small night rather than like a bug.
        val onTheFestival = recording(12, at(19)).copy(eventId = 1)
        val filed = recordings().map {
            it.copy(eventId = EventMembership.resolve(festival(), emptyMap(), recordings())[it.recordId])
        } + onTheFestival

        assertEquals(3, scopeOf(festival(), filed, 1).size)
    }

    @Test
    fun `the count a picker shows is the count an export delivers`() {
        // The sprint's verify step, as an assertion. These were two walks over two different lists
        // — one counting events under a collection, one collecting recordings — so the header could
        // say one thing while the export contained another.
        val within = EventTree.descendantsOf(festival(), 1)

        assertEquals(within.size - 1, festival().count { it.eventId != 1L })
        assertEquals(
            within.filter { it != 1L }.toSet(),
            festival().filter { it.eventId != 1L }.map { it.eventId }.toSet()
        )
    }

    @Test
    fun `a container holding only containers still reaches its recordings`() {
        // Griztronics holds days, days hold sets, sets hold recordings. The festival owns nothing
        // directly, and one level of walking reported it empty — the original defect.
        val membership = EventMembership.resolve(festival(), emptyMap(), recordings())
        val filed = recordings().map { it.copy(eventId = membership[it.recordId]) }

        assertTrue(festival().none { it.parentId == 1L && filed.any { r -> r.eventId == it.eventId } })
        assertEquals(2, scopeOf(festival(), filed, 1).size)
    }
}
