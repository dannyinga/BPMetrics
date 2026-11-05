package inga.bpmetrics.export

import inga.bpmetrics.export.RuleRemap.Kind
import inga.bpmetrics.library.FilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRemapTest {

    /** "Danny BC" from a real backup: event 3, person 2. */
    private val dannyBc = FilterState(selectedEventIds = setOf(3), selectedPersonIds = setOf(2))

    @Test
    fun `every id is translated to the one it got on import`() {
        val result = RuleRemap.remap(
            dannyBc.copy(
                selectedTagIds = setOf(7),
                selectedLocationIds = setOf(1),
                selectedGroupIds = setOf(4),
                selectedWatchIds = setOf("501af8fd")
            ),
            RuleRemap.Maps(
                events = mapOf(3L to 103L),
                people = mapOf(2L to 50L),
                tags = mapOf(7L to 70L),
                locations = mapOf(1L to 11L),
                collections = mapOf(4L to 40L)
            )
        )

        assertEquals(setOf(103L), result.rule.selectedEventIds)
        assertEquals(setOf(50L), result.rule.selectedPersonIds)
        assertEquals(setOf(70L), result.rule.selectedTagIds)
        assertEquals(setOf(11L), result.rule.selectedLocationIds)
        assertEquals(setOf(40L), result.rule.selectedGroupIds)
        // A watch id is the watch's own, so it travels untouched.
        assertEquals(setOf("501af8fd"), result.rule.selectedWatchIds)
        assertTrue(result.lost.isEmpty())
    }

    @Test
    fun `an id that can't be translated matches nothing rather than everything`() {
        // A format 6 file: events have ids, people don't.
        val result = RuleRemap.remap(dannyBc, RuleRemap.Maps(events = mapOf(3L to 103L)))

        assertEquals(setOf(103L), result.rule.selectedEventIds)
        // Not empty: an empty person term would mean "anyone", widening the rule.
        assertEquals(setOf(RuleRemap.NOTHING), result.rule.selectedPersonIds)
        assertEquals(setOf(Kind.PERSON), result.lost)
    }

    @Test
    fun `a partly translated dimension keeps what it can`() {
        val result = RuleRemap.remap(
            FilterState(selectedPersonIds = setOf(2, 4)),
            RuleRemap.Maps(people = mapOf(4L to 44L))
        )
        assertEquals(setOf(44L), result.rule.selectedPersonIds)
        assertEquals(setOf(Kind.PERSON), result.lost)
    }

    @Test
    fun `dimensions the rule doesn't use stay empty`() {
        val result = RuleRemap.remap(FilterState(query = "Subtronics"), RuleRemap.Maps())
        assertEquals(FilterState(query = "Subtronics"), result.rule)
        assertTrue(result.lost.isEmpty())
    }
}
