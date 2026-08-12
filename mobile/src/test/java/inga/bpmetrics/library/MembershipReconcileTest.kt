package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * That the stored answer is the derived answer.
 *
 * `bpm_records.eventId` is a cache of [EventMembership.resolve]. A cache is only as good as the
 * guarantee that it agrees with what it caches, and the way this app has failed four times is
 * precisely a stored number disagreeing with the walk that should have produced it — never loudly,
 * always as a plausible total nobody checked.
 *
 * Reconciling is one line of real code (`resolve`, then write what changed), so what is worth
 * asserting is not the writing but the *behaviour over a sequence of edits*: that reconciling twice
 * changes nothing the second time, that an edit which should not move anything doesn't, and that the
 * result never depends on what the column happened to hold beforehand. Those are the properties a
 * cache needs and the ones a single "does it compute the right value" test misses.
 *
 * Modelled here against the pure functions rather than a database, so the rule can be checked
 * exhaustively without an emulator. The database half — that the writer is the only writer — is what
 * the closed trigger list on `LibraryRepository.reconcileMembership` is for.
 */
class MembershipReconcileTest {

    private val day = 1_700_000_000_000L
    private fun at(hours: Int, minutes: Int = 0) = day + hours * 3_600_000L + minutes * 60_000L

    private fun event(id: Long, name: String, parent: Long? = null, from: Long? = null, to: Long? = null) =
        EventEntity(
            eventId = id,
            name = name,
            parentId = parent,
            windowStart = from,
            windowEnd = to,
            createdAt = from ?: id
        )

    private fun recording(id: Long, startsAt: Long, person: Long? = null, filedAs: Long? = null) =
        BpmRecordEntity(
            recordId = id,
            title = "Record $id",
            date = startsAt,
            startTime = startsAt,
            endTime = startsAt + 600_000L,
            durationMs = 600_000L,
            personId = person,
            eventId = filedAs
        )

    /** What a reconcile does: resolve, then write the answer onto the rows. */
    private fun reconcile(
        events: List<EventEntity>,
        windowPeople: Map<Long, Set<Long>>,
        recordings: List<BpmRecordEntity>
    ): List<BpmRecordEntity> {
        val resolved = EventMembership.resolve(events, windowPeople, recordings)
        return recordings.map { it.copy(eventId = resolved[it.recordId]) }
    }

    private fun festival() = listOf(
        event(1, "Griztronics", from = at(0), to = at(48)),
        event(2, "Day 1", parent = 1, from = at(10), to = at(26)),
        event(3, "Subtronics", parent = 2, from = at(21), to = at(22, 30)),
        event(4, "Excision", parent = 2, from = at(23), to = at(24, 30))
    )

    private fun library() = listOf(
        recording(10, at(21, 15)),   // in Subtronics
        recording(11, at(23, 30)),   // in Excision
        recording(12, at(12)),       // Day 1, between sets
        recording(13, at(30)),       // festival, day 2, no day event
        recording(14, at(100))       // outside everything
    )

    @Test
    fun `every recording lands where the rule says`() {
        val after = reconcile(festival(), emptyMap(), library()).associateBy { it.recordId }

        assertEquals(3L, after[10]?.eventId)
        assertEquals(4L, after[11]?.eventId)
        assertEquals(2L, after[12]?.eventId)
        assertEquals(1L, after[13]?.eventId)
        assertNull(after[14]?.eventId)
    }

    @Test
    fun `reconciling twice changes nothing the second time`() {
        // The property that makes the column safe to cache. If a second pass moved anything, the
        // stored value would depend on how many times the app happened to recompute it.
        val once = reconcile(festival(), emptyMap(), library())
        val twice = reconcile(festival(), emptyMap(), once)

        assertEquals(once, twice)
    }

    @Test
    fun `the answer does not depend on what the column already held`() {
        // A stale column is the normal case — it is what a reconcile exists to correct. Starting
        // from values pointing at an event that does not exist must reach the same place as
        // starting from empty, including for the recording no window claims: a dangling id is not
        // a filing decision, and treating it as one strands the recording permanently.
        val wrong = library().map { it.copy(eventId = 99L) }

        assertEquals(
            reconcile(festival(), emptyMap(), library()).map { it.eventId },
            reconcile(festival(), emptyMap(), wrong).map { it.eventId }
        )
    }

    @Test
    fun `an unfiled recording stays unfiled through repeated reconciles`() {
        // Unfiled is a real state, not a gap to be filled by the nearest event.
        var records = listOf(recording(14, at(100)))
        repeat(3) { records = reconcile(festival(), emptyMap(), records) }

        assertNull(records.single().eventId)
    }

    // --- Edits that should move things, and edits that should not ---

    @Test
    fun `widening a window pulls a recording in`() {
        val before = reconcile(festival(), emptyMap(), library()).associateBy { it.recordId }
        assertNull(before[14]?.eventId)

        val widened = festival().map { if (it.eventId == 1L) it.copy(windowEnd = at(120)) else it }
        val after = reconcile(widened, emptyMap(), library()).associateBy { it.recordId }

        assertEquals(1L, after[14]?.eventId)
    }

    @Test
    fun `clearing a window drops its recordings to the event above`() {
        val cleared = festival().map {
            if (it.eventId == 3L) it.copy(windowStart = null, windowEnd = null) else it
        }
        val after = reconcile(cleared, emptyMap(), library()).associateBy { it.recordId }

        assertEquals(2L, after[10]?.eventId)
    }

    @Test
    fun `deleting a leaf event drops its recordings to the event above, not to unfiled`() {
        // The behaviour that makes a delete safe: nothing disappears from the library, it just moves
        // up. The recording was inside Day 1 all along; only the finer answer went away.
        val without = festival().filterNot { it.eventId == 3L }
        val after = reconcile(without, emptyMap(), library()).associateBy { it.recordId }

        assertEquals(2L, after[10]?.eventId)
    }

    @Test
    fun `reparenting an event does not move the recordings inside it`() {
        // Depth changes, membership does not: the recording is still inside Subtronics' window, and
        // Subtronics is still the deepest thing containing it.
        val moved = festival().map { if (it.eventId == 3L) it.copy(parentId = 1L) else it }
        val after = reconcile(moved, emptyMap(), library()).associateBy { it.recordId }

        assertEquals(3L, after[10]?.eventId)
    }

    @Test
    fun `renaming an event moves nothing`() {
        val renamed = festival().map { if (it.eventId == 3L) it.copy(name = "Subtronics B2B") else it }

        assertEquals(
            reconcile(festival(), emptyMap(), library()).map { it.eventId },
            reconcile(renamed, emptyMap(), library()).map { it.eventId }
        )
    }

    @Test
    fun `adding a recording does not disturb the others`() {
        val before = reconcile(festival(), emptyMap(), library()).associateBy { it.recordId }
        val withNew = library() + recording(20, at(21, 20))

        val after = reconcile(festival(), emptyMap(), withNew).associateBy { it.recordId }

        assertEquals(3L, after[20]?.eventId)
        library().forEach { assertEquals(before[it.recordId]?.eventId, after[it.recordId]?.eventId) }
    }

    @Test
    fun `deleting a recording does not disturb the others`() {
        val before = reconcile(festival(), emptyMap(), library()).associateBy { it.recordId }
        val fewer = library().filterNot { it.recordId == 10L }

        val after = reconcile(festival(), emptyMap(), fewer).associateBy { it.recordId }

        fewer.forEach { assertEquals(before[it.recordId]?.eventId, after[it.recordId]?.eventId) }
    }

    @Test
    fun `qualifying a window by person releases everyone else`() {
        val people = mapOf(3L to setOf(100L))
        val after = reconcile(festival(), people, library()).associateBy { it.recordId }

        // Record 10 has no wearer, so a window that is only person 100's cannot claim it. It falls
        // to Day 1, which claims everyone.
        assertEquals(2L, after[10]?.eventId)
    }

    // --- Hand filing ---

    @Test
    fun `hand filing survives a reconcile where no window applies`() {
        val filed = listOf(recording(14, at(100), filedAs = 1L))

        assertEquals(1L, reconcile(festival(), emptyMap(), filed).single().eventId)
    }

    @Test
    fun `a window overrides hand filing, and keeps overriding it`() {
        // Once, then again: a reconcile must not oscillate between the manual value and the derived
        // one, which is what would happen if the writer read back its own output as an input.
        var records = listOf(recording(10, at(21, 15), filedAs = 1L))
        repeat(3) { records = reconcile(festival(), emptyMap(), records) }

        assertEquals(3L, records.single().eventId)
    }

    @Test
    fun `a recording released by a deleted window does not fall back to its old hand filing`() {
        // It was filed under Griztronics by hand, then swallowed by Subtronics' window. Removing
        // that window must give the derived answer for where it is *now* — Day 1 contains it — not
        // resurrect a filing decision made before the windows existed.
        val filed = listOf(recording(10, at(21, 15), filedAs = 1L))
        val reconciled = reconcile(festival(), emptyMap(), filed)

        val without = festival().filterNot { it.eventId == 3L }

        assertEquals(2L, reconcile(without, emptyMap(), reconciled).single().eventId)
    }

    @Test
    fun `an empty library reconciles to an empty library`() {
        assertEquals(emptyList<BpmRecordEntity>(), reconcile(festival(), emptyMap(), emptyList()))
    }

    @Test
    fun `a library with no events unfiles everything that was not filed by hand`() {
        val after = reconcile(emptyList(), emptyMap(), library())

        assertEquals(List(5) { null }, after.map { it.eventId })
    }
}
