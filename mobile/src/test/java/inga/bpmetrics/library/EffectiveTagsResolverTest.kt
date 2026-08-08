package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the one place inheritance is decided.
 *
 * Filtering, analysis grouping and display all read through this, so a tag matching here and not
 * there is not possible — which is the whole reason it is one function.
 */
class EffectiveTagsResolverTest {

    private fun tag(id: Long, name: String, categoryId: Long = 1) =
        TagEntity(tagId = id, name = name, parentCategoryId = categoryId)

    private val loud = tag(1, "Loud")
    private val coachella = tag(2, "Coachella 2026")
    private val saturday = tag(3, "Saturday")

    @Test
    fun `a recording's own tags are direct`() {
        val result = EffectiveTagsResolver.resolve(
            directTags = listOf(loud),
            eventId = null,
            groupChain = emptyList(),
            eventTags = emptyMap(),
            groupTags = emptyMap()
        )

        assertEquals(1, result.size)
        assertEquals(TagSource.DIRECT, result.single().source)
        assertFalse(result.single().isInherited)
    }

    @Test
    fun `all three levels arrive, each knowing where it came from`() {
        val result = EffectiveTagsResolver.resolve(
            directTags = listOf(loud),
            eventId = 10,
            groupChain = listOf(20L),
            eventTags = mapOf(10L to listOf(saturday)),
            groupTags = mapOf(20L to listOf(coachella))
        )

        assertEquals(
            listOf(
                "Loud" to TagSource.DIRECT,
                "Saturday" to TagSource.EVENT,
                "Coachella 2026" to TagSource.GROUP
            ),
            result.map { it.tag.name to it.source }
        )
    }

    @Test
    fun `the nearest source wins when the same tag is applied twice`() {
        // Applied to the recording and to its group. One tag, and calling it direct is what keeps
        // it removable in the place someone actually applied it.
        val result = EffectiveTagsResolver.resolve(
            directTags = listOf(coachella),
            eventId = 10,
            groupChain = listOf(20L),
            eventTags = emptyMap(),
            groupTags = mapOf(20L to listOf(coachella))
        )

        assertEquals(1, result.size)
        assertEquals(TagSource.DIRECT, result.single().source)
    }

    @Test
    fun `an event tag beats the same tag on the group`() {
        val result = EffectiveTagsResolver.resolve(
            directTags = emptyList(),
            eventId = 10,
            groupChain = listOf(20L),
            eventTags = mapOf(10L to listOf(coachella)),
            groupTags = mapOf(20L to listOf(coachella))
        )

        assertEquals(1, result.size)
        assertEquals(TagSource.EVENT, result.single().source)
    }

    @Test
    fun `an unfiled recording inherits nothing`() {
        val result = EffectiveTagsResolver.resolve(
            directTags = listOf(loud),
            eventId = null,
            groupChain = emptyList(),
            eventTags = mapOf(10L to listOf(saturday)),
            groupTags = mapOf(20L to listOf(coachella))
        )

        assertEquals(listOf("Loud"), result.map { it.tag.name })
    }

    @Test
    fun `an event outside any group inherits only the event's tags`() {
        val result = EffectiveTagsResolver.resolve(
            directTags = emptyList(),
            eventId = 10,
            groupChain = emptyList(),
            eventTags = mapOf(10L to listOf(saturday)),
            groupTags = mapOf(20L to listOf(coachella))
        )

        assertEquals(listOf("Saturday"), result.map { it.tag.name })
        assertTrue(result.single().isInherited)
    }

    @Test
    fun `moving a recording out from under a group drops the inherited tag immediately`() {
        // The reason inheritance is resolved rather than copied. Nothing is written, so nothing
        // is left behind to clean up — and nothing lies.
        val before = EffectiveTagsResolver.resolve(
            directTags = emptyList(),
            eventId = 10,
            groupChain = listOf(20L),
            eventTags = emptyMap(),
            groupTags = mapOf(20L to listOf(coachella))
        )
        val after = EffectiveTagsResolver.resolve(
            directTags = emptyList(),
            eventId = 11,
            groupChain = emptyList(),
            eventTags = emptyMap(),
            groupTags = mapOf(20L to listOf(coachella))
        )

        assertEquals(listOf("Coachella 2026"), before.map { it.tag.name })
        assertTrue(after.isEmpty())
    }

    @Test
    fun `resolveAll walks a library and reaches each group through its event`() {
        val filed = record(1, eventId = 10, tags = listOf(loud))
        val elsewhere = record(2, eventId = 11)
        val unfiled = record(3, eventId = null)

        val resolved = EffectiveTagsResolver.resolveAll(
            records = listOf(filed, elsewhere, unfiled),
            eventTags = mapOf(10L to listOf(saturday)),
            groupTags = mapOf(20L to listOf(coachella)),
            groupIdByEvent = mapOf(10L to 20L, 11L to null)
        )

        assertEquals(
            listOf("Loud", "Saturday", "Coachella 2026"),
            resolved.getValue(1).map { it.tag.name }
        )
        assertTrue(resolved.getValue(2).isEmpty())
        assertTrue(resolved.getValue(3).isEmpty())
    }

    @Test
    fun `index groups a flat query result by owner`() {
        val indexed = EffectiveTagsResolver.index(
            listOf(
                OwnedTag(10, saturday),
                OwnedTag(10, loud),
                OwnedTag(11, coachella)
            )
        )

        assertEquals(listOf("Saturday", "Loud"), indexed.getValue(10).map { it.name })
        assertEquals(listOf("Coachella 2026"), indexed.getValue(11).map { it.name })
    }

    private fun record(id: Long, eventId: Long?, tags: List<TagEntity> = emptyList()) = BpmRecord(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Record $id",
            date = 0L,
            startTime = 0L,
            endTime = 1000L,
            durationMs = 1000L,
            eventId = eventId
        ),
        dataPoints = emptyList(),
        minDataPoint = null,
        maxDataPoint = null,
        tags = tags
    )

    @Test
    fun `a tag on a festival reaches a recording inside one of its days`() {
        // Collections nest, so inheritance climbs the whole chain. Without this, tagging Coachella
        // would reach nothing once its sets were filed under days rather than under it directly.
        val festivalTag = TagEntity(tagId = 90, name = "Coachella", parentCategoryId = 1)

        val result = EffectiveTagsResolver.resolve(
            directTags = emptyList(),
            eventId = 10,
            groupChain = listOf(20L, 21L),
            eventTags = emptyMap(),
            groupTags = mapOf(21L to listOf(festivalTag))
        )

        assertEquals(listOf(90L), result.map { it.tag.tagId })
        assertEquals(TagSource.GROUP, result.single().source)
    }

    @Test
    fun `the nearest collection wins when a tag is set at two levels`() {
        // Same tag on the day and on the festival is one tag. Attributing it to the day is what
        // keeps it removable where someone would look for it.
        val shared = TagEntity(tagId = 91, name = "Rain", parentCategoryId = 1)

        val result = EffectiveTagsResolver.resolve(
            directTags = emptyList(),
            eventId = 10,
            groupChain = listOf(20L, 21L),
            eventTags = emptyMap(),
            groupTags = mapOf(20L to listOf(shared), 21L to listOf(shared))
        )

        assertEquals(1, result.size)
    }
}
