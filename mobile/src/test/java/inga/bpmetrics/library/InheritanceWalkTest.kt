package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one nearest-wins walk behind covers, places and tags (Model and Export Consolidation §3).
 *
 * Written against a realistic tree before the three resolvers were moved onto it, with the answers
 * they gave then. They still have to give them.
 *
 * Bass Canyon (the Gorge, a cover, tagged Festival)
 *   └ Day 2 (tagged Saturday)
 *       └ Subtronics (its own cover, tagged Festival and Dubstep)
 */
class InheritanceWalkTest {

    private val gorge = LocationEntity(locationId = 1, name = "The Gorge", timeZoneId = "America/Los_Angeles")
    private val showbox = LocationEntity(locationId = 2, name = "Showbox SoDo", timeZoneId = "America/Los_Angeles")
    private val locations = mapOf(1L to gorge, 2L to showbox)

    private fun cover(name: String) = CoverColumns(path = name)

    private val festival = EventEntity(eventId = 10, name = "Bass Canyon", locationId = 1, cover = cover("bc.jpg"))
    private val day = EventEntity(eventId = 11, name = "Day 2", parentId = 10)
    private val set = EventEntity(eventId = 12, name = "Subtronics", parentId = 11, cover = cover("subtronics.jpg"))
    private val events = listOf(festival, day, set)

    private val festivalTag = TagEntity(tagId = 1, name = "Festival", parentCategoryId = 1)
    private val saturday = TagEntity(tagId = 2, name = "Saturday", parentCategoryId = 1)
    private val dubstep = TagEntity(tagId = 3, name = "Dubstep", parentCategoryId = 2)
    private val loud = TagEntity(tagId = 4, name = "Loud", parentCategoryId = 3)
    private val eventTags = mapOf(10L to listOf(festivalTag), 11L to listOf(saturday), 12L to listOf(festivalTag, dubstep))

    private fun recording(id: Long, eventId: Long?, locationId: Long? = null, cover: CoverColumns = CoverColumns()) =
        BpmRecordEntity(
            recordId = id, title = "Untitled", date = 0, startTime = 0, endTime = 1, durationMs = 1,
            eventId = eventId, locationId = locationId, cover = cover
        )

    // In the set, nothing of its own.
    private val inSet = recording(1, eventId = 12)
    // On the day, with its own venue and picture.
    private val onDay = recording(2, eventId = 11, locationId = 2, cover = cover("mine.jpg"))
    // Filed nowhere.
    private val unfiled = recording(3, eventId = null)

    private fun coverOf(item: TreeItem) = EventTree.nearest(item, events) { it.ownCover }
    private fun placeOf(item: TreeItem) = EventTree.nearest(item, events) { it.ownLocationId?.let(locations::get) }

    @Test
    fun `a cover is the nearest one, and says where it came from`() {
        assertEquals(Inherited(set.ownCover!!, Source.EVENT), coverOf(inSet))
        assertEquals(Inherited(onDay.ownCover!!, Source.OWN), coverOf(onDay))
        assertNull(coverOf(unfiled))
        // The day has none of its own, so it shows the festival's.
        assertEquals(Inherited(festival.ownCover!!, Source.EVENT), coverOf(day))
    }

    @Test
    fun `the walk agrees with the cover resolver`() {
        val eventCovers = events.associate { it.eventId to it.ownCover }
        listOf(inSet, onDay, unfiled).forEach { r ->
            val old = CoverResolver.forRecording(r.ownCover, r.eventId, eventCovers, events)
            assertEquals(old?.cover, coverOf(r)?.value)
        }
        assertEquals(
            mapOf(10L to festival.ownCover, 11L to festival.ownCover, 12L to set.ownCover),
            CoverResolver.byEvent(events)
        )
    }

    @Test
    fun `a place is the nearest one`() {
        assertEquals(Inherited(gorge, Source.ANCESTOR), placeOf(inSet))
        assertEquals(Inherited(showbox, Source.OWN), placeOf(onDay))
        assertNull(placeOf(unfiled))
    }

    @Test
    fun `the walk agrees with the location resolver`() {
        listOf(inSet, onDay, unfiled).forEach { r ->
            val old = LocationResolver.forRecording(r.locationId, r.eventId, events, locations)
            assertEquals(old?.location, placeOf(r)?.value)
        }
        assertEquals(gorge, LocationResolver.forEvent(12, events, locations)?.location)
    }

    @Test
    fun `tags gather up the tree, each from the nearest place it appears`() {
        val gathered = EventTree.gather(inSet, events, key = { it.tagId }) { item ->
            when (item) {
                is EventEntity -> eventTags[item.eventId].orEmpty()
                else -> listOf(loud)
            }
        }
        // Festival is on the set and on the festival: it's the set's.
        assertEquals(
            listOf(
                Inherited(loud, Source.OWN),
                Inherited(festivalTag, Source.EVENT),
                Inherited(dubstep, Source.EVENT),
                Inherited(saturday, Source.ANCESTOR)
            ),
            gathered
        )
    }

    @Test
    fun `the walk agrees with the tag resolver`() {
        val old = EffectiveTagsResolver.resolve(
            directTags = listOf(loud),
            ancestry = EventTree.ancestryOf(events, 12).map { it.eventId },
            eventTags = eventTags
        )
        val new = gather(listOf(loud), EventTree.ancestryOf(events, 12), { eventTags[it.eventId].orEmpty() }) { it.tagId }
        assertEquals(old, new)
    }
}
