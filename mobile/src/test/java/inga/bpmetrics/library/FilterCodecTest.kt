package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A selection's rule surviving a restart.
 *
 * A smart collection stores the *question*, so it has to be readable back exactly — a rule that
 * comes back slightly different is worse than one that fails outright, because it silently answers a
 * question nobody asked.
 *
 * The case that actually breaks this is a rule saved by an older build. Gson does not run
 * constructors: given a class with a required parameter it allocates through `Unsafe` and leaves
 * missing keys null, including on properties declared non-null. `FilterState` has defaults on every
 * field so Kotlin emits a no-arg constructor for Gson to call — the same rule the backup format
 * depends on, and the same bug if anyone adds a required field.
 */
class FilterCodecTest {

    @Test
    fun `an empty filter survives the round trip`() {
        val filter = FilterState()

        assertEquals(filter, FilterCodec.fromJson(FilterCodec.toJson(filter)))
    }

    @Test
    fun `every dimension survives the round trip`() {
        val filter = FilterState(
            query = "gorge",
            dateRange = 1_700_000_000_000L to 1_700_100_000_000L,
            selectedTagIds = setOf(1, 2),
            minBpm = 120.0,
            maxBpm = 180.0,
            selectedPersonIds = setOf(3),
            selectedWatchIds = setOf("abc-def"),
            selectedEventIds = setOf(4),
            selectedGroupIds = setOf(5),
            selectedLocationIds = setOf(6)
        )

        assertEquals(filter, FilterCodec.fromJson(FilterCodec.toJson(filter)))
    }

    @Test
    fun `a rule saved before a dimension existed still reads`() {
        // The real compatibility case: JSON written when there was no query and no location. Those
        // must come back as "not narrowing", not as nulls that throw on first use.
        val old = """{"selectedPersonIds":[3],"minBpm":0.0,"selectedTagIds":[]}"""

        val read = FilterCodec.fromJson(old)

        assertEquals(setOf(3L), read.selectedPersonIds)
        assertEquals("", read.query)
        assertTrue(read.selectedLocationIds.isEmpty())
    }

    @Test
    fun `an unreadable rule becomes an empty filter rather than a crash`() {
        // Visibly wrong and recoverable beats crashing on opening the library.
        assertTrue(FilterCodec.fromJson("not json at all").isEmpty)
        assertTrue(FilterCodec.fromJson("").isEmpty)
    }

    @Test
    fun `a collection reading its own rule gets null rather than everything`() {
        // The opposite default, deliberately. An empty FilterState selects the whole library, so an
        // unreadable rule falling back to it would silently turn "every Subtronics recording" into
        // "everything" — and a set that has quietly grown is far harder to notice than one that has
        // quietly shrunk.
        assertEquals(null, FilterCodec.parseOrNull("not json at all"))

        val set = CollectionEntity(name = "Broken", filterJson = "not json at all")
        assertEquals(null, set.rule())
    }

    @Test
    fun `a null date range stays null rather than becoming a zero range`() {
        // A zero range would match nothing, so a view with no date term would silently return an
        // empty library.
        val read = FilterCodec.fromJson(
            FilterCodec.toJson(FilterState(query = "x"))
        )

        assertEquals(null, read.dateRange)
    }

    @Test
    fun `a watch id survives as a string`() {
        // Watch ids are UUIDs. A number-shaped one must not come back as a Long and stop matching.
        val filter = FilterState(selectedWatchIds = setOf("12345"))

        assertEquals(
            setOf("12345"),
            FilterCodec.fromJson(FilterCodec.toJson(filter)).selectedWatchIds
        )
    }
}
