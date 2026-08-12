package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A collection that keeps answering: its rule, stored and re-asked.
 *
 * The whole path, because the parts were each fine and the thing did not work. A rule is written by
 * the editor as JSON, kept in a column, parsed back by [CollectionEntity.rule] and handed to
 * [Scope] — and a break anywhere along that chain looks identical from the outside: a collection
 * that says it collects Colin's recordings and holds nothing.
 *
 * Tested end to end for exactly that reason. Asserting that `LibraryFilter` matches a person proves
 * nothing about whether the rule survived being written down.
 */
class LivingCollectionTest {

    private val colin = 7L
    private val kyle = 8L

    private fun recording(id: Long, person: Long?) = BpmRecordEntity(
        recordId = id,
        title = "Record $id",
        date = id * 1000,
        startTime = id * 1000,
        endTime = id * 1000 + 600_000L,
        durationMs = 600_000L,
        personId = person
    )

    private val library = listOf(
        recording(1, colin),
        recording(2, kyle),
        recording(3, colin),
        recording(4, null)
    )

    /**
     * A collection carrying [rule], resolved the way the app resolves it.
     *
     * Through the JSON, not around it: the rule is serialised and parsed exactly as saving and
     * reopening would do, because "does a `FilterState` survive the round trip" is half of what
     * this is checking.
     */
    private fun resolve(
        rule: FilterState?,
        members: Set<Long> = emptySet(),
        excluded: Set<Long> = emptySet()
    ): Set<Long> {
        val set = CollectionEntity(
            collectionId = 1,
            name = "Colin",
            filterJson = rule?.let { FilterCodec.toJson(it) },
            excludedRecordJson = excluded.joinToString(",")
        )
        return Scope.recordsIn(
            ScopeRef.Collection(1),
            Library(
                records = library.map {
                    BpmRecord(metadata = it, minDataPoint = null, maxDataPoint = null)
                },
                events = emptyList(),
                collections = listOf(set),
                collectionEvents = emptyList(),
                collectionRecords = members.map { CollectionRecordCrossRef(1, it) }
            )
        ).mapTo(mutableSetOf()) { it.metadata.recordId }
    }

    @Test
    fun `a rule naming a person collects that person's recordings`() {
        assertEquals(setOf(1L, 3L), resolve(FilterState(selectedPersonIds = setOf(colin))))
    }

    @Test
    fun `a rule survives being written down and read back`() {
        val rule = FilterState(selectedPersonIds = setOf(colin), minBpm = 90.0)
        val json = FilterCodec.toJson(rule)

        assertEquals(rule, FilterCodec.parseOrNull(json))
    }

    /**
     * The field added most recently, which is the one a stored rule is most likely to predate.
     *
     * Every field of [FilterState] defaults, so Kotlin emits the no-arg constructor Gson needs;
     * without it Gson allocates through `Unsafe` and leaves absent keys null, on properties
     * declared non-null. A rule written before a dimension existed has to read as not narrowing on
     * it, not as a crash and not as a rule that matches nothing.
     */
    @Test
    fun `a rule written before a dimension existed still parses`() {
        val old = """{"selectedPersonIds":[$colin]}"""

        val parsed = FilterCodec.parseOrNull(old)

        assertEquals(setOf(colin), parsed?.selectedPersonIds)
        assertTrue(parsed?.selectedEventTypes.orEmpty().isEmpty())
    }

    @Test
    fun `a rule and hand-picked members combine`() {
        assertEquals(
            setOf(1L, 2L, 3L),
            resolve(FilterState(selectedPersonIds = setOf(colin)), members = setOf(2L))
        )
    }

    @Test
    fun `a struck-out recording stays out of what the rule finds`() {
        assertEquals(
            setOf(3L),
            resolve(FilterState(selectedPersonIds = setOf(colin)), excluded = setOf(1L))
        )
    }

    /**
     * An empty rule is not a rule.
     *
     * `FilterState()` narrows on nothing, so a collection carrying one would quietly become the
     * whole library. The editors refuse to save one; this pins what happens if one ever arrives.
     */
    @Test
    fun `a collection with no rule and no members holds nothing`() {
        assertTrue(resolve(rule = null).isEmpty())
    }

    // --- What a living collection holds, as events ---

    private val concert = EventEntity(eventId = 50, name = "Subtronics", type = "Concert")
    private val raid = EventEntity(eventId = 51, name = "Tuesday", type = "Raid")

    private fun filed(id: Long, event: Long) = BpmRecordEntity(
        recordId = id,
        title = "Record $id",
        date = id * 1000,
        startTime = id * 1000,
        endTime = id * 1000 + 600_000L,
        durationMs = 600_000L,
        eventId = event
    )

    /**
     * The complaint: a rule for every concert found the recordings and listed no events.
     *
     * A collection's events came from its *links* alone, which is right for a hand-made set and
     * wrong for a living one — so a collection of every concert showed an empty trail and a header
     * reading "0 events" over a page holding all of them. An event a rule reached is an event the
     * collection holds, and it is reached exactly when something inside it matched.
     */
    @Test
    fun `a rule contributes the events it reached, not only recordings`() {
        val events = listOf(concert, raid)
        val records = listOf(filed(1, concert.eventId), filed(2, raid.eventId))

        val held = Scope.eventsIn(
            collectionId = 1,
            events = events,
            links = emptyList(),
            resolved = listOf(records[0])
        )

        assertEquals(listOf("Subtronics"), held.map { it.name })
    }

    @Test
    fun `hand-picked events and rule-reached events combine`() {
        val events = listOf(concert, raid)

        val held = Scope.eventsIn(
            collectionId = 1,
            events = events,
            links = listOf(CollectionEventCrossRef(1, raid.eventId)),
            resolved = listOf(filed(1, concert.eventId))
        )

        assertEquals(setOf("Subtronics", "Tuesday"), held.map { it.name }.toSet())
    }

    /**
     * The failure that made a rule work on one screen and nowhere else.
     *
     * Event type, venue and tag are all matched through the [FilterContext] rather than off the
     * recording, because they belong to the *event* and a recording knows only which event it is
     * filed under. Three places built a `Library` and each built a different context: the library
     * screen assembled the whole thing, the collections list passed none at all, the repository's
     * snapshot passed only the tags. An absent lookup is a null, not a failure — so "every concert"
     * matched every concert on the library screen and nothing in the collection, its export or its
     * analysis. One [filterContextOf] now, and this is what proves it.
     */
    @Test
    fun `a rule naming an event type resolves through the context`() {
        val events = listOf(concert, raid)
        val records = listOf(filed(1, concert.eventId), filed(2, raid.eventId))

        val set = CollectionEntity(
            collectionId = 1,
            name = "Concerts",
            filterJson = FilterCodec.toJson(FilterState(selectedEventTypes = setOf("Concert")))
        )

        val held = Scope.recordsIn(
            ScopeRef.Collection(1),
            Library(
                records = records.map {
                    BpmRecord(metadata = it, minDataPoint = null, maxDataPoint = null)
                },
                events = events,
                collections = listOf(set),
                collectionEvents = emptyList(),
                collectionRecords = emptyList(),
                filterContext = filterContextOf(events = events, places = emptyList())
            )
        ).map { it.metadata.recordId }

        assertEquals(listOf(1L), held)
    }

    /** And the same rule against a context nobody filled in, which is what was happening. */
    @Test
    fun `without the context an event-type rule silently matches nothing`() {
        val events = listOf(concert, raid)
        val records = listOf(filed(1, concert.eventId), filed(2, raid.eventId))

        val matched = LibraryFilter.apply(
            records.map { BpmRecord(metadata = it, minDataPoint = null, maxDataPoint = null) },
            FilterState(selectedEventTypes = setOf("Concert")),
            FilterContext()
        )

        // Not a crash and not everything — nothing, which is why it went unnoticed.
        assertEquals(emptyList<Long>(), matched.map { it.metadata.recordId })
    }

    /** A hand-made set still lists exactly what it names, with nothing resolved to add. */
    @Test
    fun `a set with no rule lists what it names`() {
        val held = Scope.eventsIn(
            collectionId = 1,
            events = listOf(concert, raid),
            links = listOf(CollectionEventCrossRef(1, concert.eventId))
        )

        assertEquals(listOf("Subtronics"), held.map { it.name })
    }
}
