package inga.bpmetrics.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Narrowing the library by a tag.
 *
 * The term with the most machinery behind it, and the only one that can *silently* do nothing: it
 * groups the chosen tags by category so that two characters mean "either" and a character plus a
 * venue mean "both", and that grouping needs to know which category each chosen tag belongs to.
 * Where that lookup comes up empty the term used to disappear, and a filter with no terms matches
 * everything — so ticking a tag showed the whole library rather than the recordings carrying it.
 */
class LibraryFilterTagTest {

    private val character = 1L
    private val venue = 2L

    private fun tag(id: Long, name: String, category: Long) =
        TagEntity(tagId = id, name = name, parentCategoryId = category)

    private fun record(id: Long, vararg tags: TagEntity) = BpmRecord(
        metadata = BpmRecordEntity(
            recordId = id,
            title = "Record $id",
            date = id * 1000,
            startTime = id * 1000,
            endTime = id * 1000 + 100,
            durationMs = 100
        ),
        minDataPoint = null,
        maxDataPoint = null,
        tags = tags.toList()
    )

    private val hulk = tag(10, "Hulk", character)
    private val spiderman = tag(11, "Spiderman", character)
    private val gorge = tag(20, "The Gorge", venue)

    /** Every tag the library knows, category and all — what the app passes. */
    private val registry = listOf(hulk, spiderman, gorge, tag(99, "Thor", character))

    private fun idsMatching(
        filter: FilterState,
        records: List<BpmRecord>,
        knowsRegistry: Boolean = true
    ): List<Long> = LibraryFilter.apply(
        records,
        filter,
        if (knowsRegistry) {
            FilterContext(categoryByTag = registry.associate { it.tagId to it.parentCategoryId })
        } else {
            FilterContext()
        }
    ).map { it.metadata.recordId }

    @Test
    fun `one tag narrows to the recordings carrying it`() {
        val records = listOf(record(1, hulk), record(2, spiderman), record(3))

        assertEquals(
            listOf(1L),
            idsMatching(FilterState(selectedTagIds = setOf(hulk.tagId)), records)
        )
    }

    @Test
    fun `two tags in one category mean either`() {
        val records = listOf(record(1, hulk), record(2, spiderman), record(3, gorge))

        assertEquals(
            listOf(1L, 2L),
            idsMatching(
                FilterState(selectedTagIds = setOf(hulk.tagId, spiderman.tagId)),
                records
            )
        )
    }

    @Test
    fun `two categories mean both`() {
        val records = listOf(record(1, hulk, gorge), record(2, hulk), record(3, gorge))

        assertEquals(
            listOf(1L),
            idsMatching(
                FilterState(selectedTagIds = setOf(hulk.tagId, gorge.tagId)),
                records
            )
        )
    }

    /**
     * The failure that made ticking a tag look like it had done nothing.
     *
     * The category lookup was built by walking the records, so a tag nothing carries had no entry
     * and was dropped from the term. Dropping the only term leaves an empty set of groups, and
     * `all {}` over nothing is true — so the filter matched the entire library. Selecting a tag
     * that nothing has must return nothing, which is the honest answer and the one that tells you
     * the tag is unused.
     */
    @Test
    fun `a tag nothing carries matches nothing, not everything`() {
        val records = listOf(record(1, hulk), record(2, spiderman))
        val unused = tag(99, "Thor", character)

        assertEquals(
            emptyList<Long>(),
            idsMatching(FilterState(selectedTagIds = setOf(unused.tagId)), records)
        )
    }

    /**
     * And with no registry either — a frozen snapshot filtered on its own terms.
     *
     * The safety net rather than the fix: an unknown tag groups on its own instead of vanishing,
     * so the term is still enforced and the answer is still nothing.
     */
    @Test
    fun `an unknown tag matches nothing even with no registry`() {
        val records = listOf(record(1, hulk), record(2, spiderman))

        assertEquals(
            emptyList<Long>(),
            idsMatching(
                FilterState(selectedTagIds = setOf(99L)),
                records,
                knowsRegistry = false
            )
        )
    }

    /** And it must not quietly widen a term that is otherwise fine. */
    @Test
    fun `an unused tag beside a used one still means either`() {
        val records = listOf(record(1, hulk), record(2, spiderman), record(3, gorge))
        val unused = tag(99, "Thor", character)

        assertEquals(
            listOf(1L),
            idsMatching(
                FilterState(selectedTagIds = setOf(hulk.tagId, unused.tagId)),
                records
            )
        )
    }

    /**
     * Ticking a whole category is one action, and every id in it arrives at once — including any
     * the library has never used. See `TagPickerDialog`.
     */
    @Test
    fun `ticking a whole category means any of its tags`() {
        val records = listOf(record(1, hulk), record(2, spiderman), record(3, gorge))
        val unused = tag(99, "Thor", character)

        assertEquals(
            listOf(1L, 2L),
            idsMatching(
                FilterState(
                    selectedTagIds = setOf(hulk.tagId, spiderman.tagId, unused.tagId)
                ),
                records
            )
        )
    }
}
