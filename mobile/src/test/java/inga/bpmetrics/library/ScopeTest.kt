package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is in a selection.
 *
 * There used to be two answers. [CollectionScope] walked the tree when a collection was opened, and
 * the library filter compared a *collection* id against an event's *parent event* id — two id
 * spaces, so the filter narrowed the library by an unrelated rule and drew no chip to undo it.
 *
 * One resolver now answers both, and these are the rules it answers by.
 */
class ScopeTest {

    private fun event(id: Long, parent: Long? = null) =
        EventEntity(eventId = id, name = "Event $id", parentId = parent, createdAt = id)

    private fun record(id: Long, filedAs: Long? = null, personId: Long? = null, avg: Double? = null) =
        BpmRecord(
            metadata = BpmRecordEntity(
                recordId = id,
                title = "Record $id",
                date = id * 1000,
                startTime = id * 1000,
                endTime = id * 1000 + 100,
                durationMs = 100,
                eventId = filedAs,
                personId = personId,
                avg = avg
            ),
            minDataPoint = null,
            maxDataPoint = null
        )

    private fun collection(id: Long, rule: FilterState? = null, excluded: Set<Long> = emptySet()) =
        CollectionEntity(
            collectionId = id,
            name = "Set $id",
            filterJson = rule?.let(FilterCodec::toJson),
            excludedRecordJson = excluded.asExclusionJson()
        )

    /** Griztronics › Day 1 › Subtronics, with a recording at each level. */
    private fun festival() = Library(
        records = listOf(
            record(10, filedAs = 3, personId = 1),
            record(11, filedAs = 2, personId = 2),
            record(12, filedAs = 1, personId = 1),
            record(13, personId = 2)
        ),
        events = listOf(event(1), event(2, parent = 1), event(3, parent = 2))
    )

    private fun ids(records: List<BpmRecord>) = records.map { it.metadata.recordId }.sorted()

    // --- The shapes a scope can take ---

    @Test
    fun `a recording is a scope of one`() {
        val found = Scope.recordsIn(ScopeRef.Recording(11), festival())

        assertEquals(listOf(11L), ids(found))
    }

    @Test
    fun `an event is its whole subtree`() {
        // Not just what is filed directly on it. Analysing Griztronics has to reach the recordings
        // inside its days and its sets, or a festival reports on the one walk between stages.
        assertEquals(listOf(10L, 11L, 12L), ids(Scope.recordsIn(ScopeRef.Event(1), festival())))
        assertEquals(listOf(10L, 11L), ids(Scope.recordsIn(ScopeRef.Event(2), festival())))
        assertEquals(listOf(10L), ids(Scope.recordsIn(ScopeRef.Event(3), festival())))
    }

    @Test
    fun `a query is the filter`() {
        val found = Scope.recordsIn(
            ScopeRef.Query(FilterState(selectedPersonIds = setOf(2))),
            festival()
        )

        assertEquals(listOf(11L, 13L), ids(found))
    }

    // --- Collections: members, rule, and both ---

    @Test
    fun `a named event brings everything beneath it`() {
        val library = festival().copy(
            collections = listOf(collection(1)),
            collectionEvents = listOf(CollectionEventCrossRef(1, 2))
        )

        assertEquals(listOf(10L, 11L), ids(Scope.recordsIn(ScopeRef.Collection(1), library)))
    }

    @Test
    fun `a named recording is in on its own`() {
        val library = festival().copy(
            collections = listOf(collection(1)),
            collectionRecords = listOf(CollectionRecordCrossRef(1, 13))
        )

        assertEquals(listOf(13L), ids(Scope.recordsIn(ScopeRef.Collection(1), library)))
    }

    @Test
    fun `a recording reachable twice appears once`() {
        // An event can be in a set alongside one of its own recordings. Counting it twice would
        // make a set report more than the library holds.
        val library = festival().copy(
            collections = listOf(collection(1)),
            collectionEvents = listOf(CollectionEventCrossRef(1, 3)),
            collectionRecords = listOf(CollectionRecordCrossRef(1, 10))
        )

        assertEquals(listOf(10L), ids(Scope.recordsIn(ScopeRef.Collection(1), library)))
    }

    @Test
    fun `a smart collection is its rule`() {
        val library = festival().copy(
            collections = listOf(collection(1, rule = FilterState(selectedPersonIds = setOf(1))))
        )

        assertEquals(listOf(10L, 12L), ids(Scope.recordsIn(ScopeRef.Collection(1), library)))
    }

    @Test
    fun `a collection can be both a rule and a list`() {
        // "Every Subtronics recording, plus these three that belong with them anyway."
        val library = festival().copy(
            collections = listOf(collection(1, rule = FilterState(selectedPersonIds = setOf(1)))),
            collectionRecords = listOf(CollectionRecordCrossRef(1, 13))
        )

        assertEquals(listOf(10L, 12L, 13L), ids(Scope.recordsIn(ScopeRef.Collection(1), library)))
    }

    @Test
    fun `an exclusion wins over both`() {
        // Otherwise one bad recording forces you to abandon a rule that is otherwise right.
        val library = festival().copy(
            collections = listOf(
                collection(1, rule = FilterState(selectedPersonIds = setOf(1)), excluded = setOf(10))
            ),
            collectionRecords = listOf(CollectionRecordCrossRef(1, 13))
        )

        assertEquals(listOf(12L, 13L), ids(Scope.recordsIn(ScopeRef.Collection(1), library)))
    }

    @Test
    fun `an unreadable rule selects nothing rather than everything`() {
        // An empty FilterState matches the whole library, so falling back to it would silently turn
        // "every Subtronics recording" into "everything" — far harder to notice than the reverse.
        val library = festival().copy(
            collections = listOf(CollectionEntity(collectionId = 1, name = "Broken", filterJson = "{{{"))
        )

        assertTrue(Scope.recordsIn(ScopeRef.Collection(1), library).isEmpty())
    }

    @Test
    fun `a collection nobody has heard of is empty`() {
        assertTrue(Scope.recordsIn(ScopeRef.Collection(99), festival()).isEmpty())
    }

    // --- Composition, and the loop it makes possible ---

    @Test
    fun `a rule can name another collection`() {
        val library = festival().copy(
            collections = listOf(
                collection(1, rule = FilterState(selectedPersonIds = setOf(1))),
                collection(2, rule = FilterState(selectedGroupIds = setOf(1)))
            )
        )

        assertEquals(listOf(10L, 12L), ids(Scope.recordsIn(ScopeRef.Collection(2), library)))
    }

    @Test
    fun `a cycle resolves to nothing rather than hanging`() {
        // The editor refuses to create one; this is the belt to that's braces, because a cycle
        // arriving through a restored backup would otherwise lock up the library.
        val library = festival().copy(
            collections = listOf(
                collection(1, rule = FilterState(selectedGroupIds = setOf(2))),
                collection(2, rule = FilterState(selectedGroupIds = setOf(1)))
            )
        )

        assertTrue(Scope.recordsIn(ScopeRef.Collection(1), library).isEmpty())
    }

    @Test
    fun `a rule that would close a loop is refused`() {
        val sets = listOf(
            collection(1, rule = FilterState(selectedGroupIds = setOf(2))),
            collection(2)
        )

        assertTrue("Pointing 2 at 1 closes the loop", Scope.ruleWouldCycle(2, 1, sets))
        assertTrue("A set cannot contain itself", Scope.ruleWouldCycle(1, 1, sets))
        assertFalse("An unrelated set is fine", Scope.ruleWouldCycle(3, 2, sets))
    }

    // --- What the filter reads ---

    @Test
    fun `filtering by a collection and opening it give the same recordings`() {
        // The defect this whole resolver exists to make impossible. The filter used to compare a
        // collection id against an event's parent id, so these two disagreed completely.
        val library = festival().copy(
            collections = listOf(collection(7)),
            collectionEvents = listOf(CollectionEventCrossRef(7, 2))
        )

        val opened = Scope.recordsIn(ScopeRef.Collection(7), library)
        val filtered = LibraryFilter.apply(
            library.records,
            FilterState(selectedGroupIds = setOf(7)),
            FilterContext(recordIdsByCollection = Scope.recordIdsByCollection(library))
        )

        assertEquals(ids(opened), ids(filtered))
        assertEquals(listOf(10L, 11L), ids(filtered))
    }

    @Test
    fun `two collection terms are a union`() {
        val library = festival().copy(
            collections = listOf(collection(7), collection(8)),
            collectionRecords = listOf(
                CollectionRecordCrossRef(7, 10),
                CollectionRecordCrossRef(8, 13)
            )
        )

        val filtered = LibraryFilter.apply(
            library.records,
            FilterState(selectedGroupIds = setOf(7, 8)),
            FilterContext(recordIdsByCollection = Scope.recordIdsByCollection(library))
        )

        assertEquals(listOf(10L, 13L), ids(filtered))
    }

    @Test
    fun `a collection term narrows alongside the other terms`() {
        // AND between dimensions: in this set *and* by this person, not either.
        val library = festival().copy(
            collections = listOf(collection(7)),
            collectionEvents = listOf(CollectionEventCrossRef(7, 2))
        )

        val filtered = LibraryFilter.apply(
            library.records,
            FilterState(selectedGroupIds = setOf(7), selectedPersonIds = setOf(1)),
            FilterContext(recordIdsByCollection = Scope.recordIdsByCollection(library))
        )

        assertEquals(listOf(10L), ids(filtered))
    }

    @Test
    fun `no collection term leaves the library alone`() {
        val filtered = LibraryFilter.apply(festival().records, FilterState())

        assertEquals(listOf(10L, 11L, 12L, 13L), ids(filtered))
    }
}
