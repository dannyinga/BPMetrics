package inga.bpmetrics.ui.library

import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.LocationEntity
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.TagEntity
import inga.bpmetrics.library.WatchEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A filter, read back as the sentence it is.
 *
 * The old filter was a dialog with a section per dimension: "Kyle at Coachella in the rain" lived in
 * three collapsed sections and became invisible the moment the dialog closed. Chips make it one
 * line you can scan, and removing a term is one tap on the term.
 *
 * What that demands is that the description is *resolved* — names, not counts. "1 tag" tells you
 * nothing you did not already know.
 */
class FilterChipsTest {

    private val kyle = PersonEntity(personId = 1, name = "Kyle", colorArgb = -65536)
    private val ben = PersonEntity(personId = 2, name = "Ben", colorArgb = -16776961)
    private val character = CategoryEntity(categoryId = 1, name = "Character")
    private val weather = CategoryEntity(categoryId = 2, name = "Weather")
    private val hulk = TagEntity(tagId = 10, name = "Hulk", parentCategoryId = 1)
    private val rain = TagEntity(tagId = 20, name = "Rain", parentCategoryId = 2)
    private val coachella = EventEntity(eventId = 100, name = "Coachella", createdAt = 1)
    private val gorge = LocationEntity(locationId = 5, name = "The Gorge", createdAt = 1)
    private val watch = WatchEntity(watchId = "abc", deviceName = "Pixel Watch")

    private fun chips(filter: LibraryViewModel.FilterState) = FilterChips.of(
        filter = filter,
        people = listOf(kyle, ben),
        tags = listOf(hulk, rain),
        categories = listOf(character, weather),
        events = listOf(coachella),
        locations = listOf(gorge),
        watches = listOf(watch),
        formatDate = { "d$it" }
    )

    @Test
    fun `an empty filter has no chips`() {
        assertTrue(chips(LibraryViewModel.FilterState()).isEmpty())
        assertTrue(LibraryViewModel.FilterState().isEmpty)
    }

    @Test
    fun `Kyle at Coachella in the rain reads as three terms`() {
        // The sentence the whole redesign exists to make readable.
        val filter = LibraryViewModel.FilterState(
            selectedPersonIds = setOf(1),
            selectedEventIds = setOf(100),
            selectedTagIds = setOf(20)
        )

        assertEquals(
            listOf("Kyle", "Weather › Rain", "Coachella"),
            chips(filter).map { it.label }
        )
    }

    @Test
    fun `a tag is qualified by its axis`() {
        // "Hulk" alone does not say what is being compared, and two categories can hold one word.
        val filter = LibraryViewModel.FilterState(selectedTagIds = setOf(10))

        assertEquals("Character › Hulk", chips(filter).single().label)
    }

    @Test
    fun `a person chip carries their colour`() {
        val filter = LibraryViewModel.FilterState(selectedPersonIds = setOf(1))

        assertEquals(-65536, chips(filter).single().colorArgb)
    }

    @Test
    fun `the order is stable regardless of what was added first`() {
        // A bar that reshuffles as terms are added is one nobody can scan.
        val a = LibraryViewModel.FilterState(
            selectedPersonIds = setOf(1),
            selectedLocationIds = setOf(5)
        )
        val b = LibraryViewModel.FilterState(
            selectedLocationIds = setOf(5),
            selectedPersonIds = setOf(1)
        )

        assertEquals(chips(a).map { it.label }, chips(b).map { it.label })
        assertEquals(listOf("Kyle", "The Gorge"), chips(a).map { it.label })
    }

    @Test
    fun `a rate band only appears when it narrows something`() {
        // A minimum of zero is the default and describes nothing.
        assertTrue(chips(LibraryViewModel.FilterState(minBpm = 0.0)).isEmpty())
        assertEquals(
            "120–max bpm",
            chips(LibraryViewModel.FilterState(minBpm = 120.0)).single().label
        )
        assertEquals(
            "0–180 bpm",
            chips(LibraryViewModel.FilterState(maxBpm = 180.0)).single().label
        )
    }

    @Test
    fun `a date range reads as a range`() {
        val filter = LibraryViewModel.FilterState(dateRange = 1L to 2L)

        assertEquals("d1 – d2", chips(filter).single().label)
    }

    @Test
    fun `something no longer in the library drops out rather than showing an id`() {
        // A person deleted while their chip was applied. Showing "person 7" would be worse than
        // showing nothing, and crashing worse still.
        val filter = LibraryViewModel.FilterState(selectedPersonIds = setOf(7))

        assertTrue(chips(filter).isEmpty())
    }

    // --- Removing a term ---

    @Test
    fun `removing a chip removes only that term`() {
        val filter = LibraryViewModel.FilterState(
            selectedPersonIds = setOf(1, 2),
            selectedTagIds = setOf(10)
        )
        val kyleChip = chips(filter).first { it.label == "Kyle" }

        val after = FilterChips.without(filter, kyleChip)

        assertEquals(setOf(2L), after.selectedPersonIds)
        assertEquals(setOf(10L), after.selectedTagIds)
    }

    @Test
    fun `every dimension can be removed`() {
        val filter = LibraryViewModel.FilterState(
            selectedPersonIds = setOf(1),
            selectedTagIds = setOf(10),
            selectedEventIds = setOf(100),
            selectedGroupIds = setOf(100),
            selectedLocationIds = setOf(5),
            selectedWatchIds = setOf("abc"),
            dateRange = 1L to 2L,
            minBpm = 120.0,
            maxBpm = 180.0
        )

        val cleared = chips(filter).fold(filter) { acc, chip -> FilterChips.without(acc, chip) }

        assertTrue("every chip should be removable: $cleared", cleared.isEmpty)
    }

    @Test
    fun `a watch is removed by its string id, not a number`() {
        // Watch ids are UUIDs, so the numeric shortcut every other dimension uses would silently
        // fail to match and leave the chip on screen.
        val filter = LibraryViewModel.FilterState(selectedWatchIds = setOf("abc"))
        val chip = chips(filter).single()

        assertTrue(FilterChips.without(filter, chip).selectedWatchIds.isEmpty())
    }

    @Test
    fun `removing a chip that is already gone changes nothing`() {
        val filter = LibraryViewModel.FilterState(selectedPersonIds = setOf(2))
        val stale = FilterChip(FilterDimension.PERSON, "person-1", "Kyle")

        assertEquals(filter, FilterChips.without(filter, stale))
    }

    @Test
    fun `the query is not a chip`() {
        // It lives in the search field, which is already visible. A chip for it would be the same
        // text twice, and removing one of the two would be ambiguous.
        val filter = LibraryViewModel.FilterState(query = "gorge")

        assertTrue(chips(filter).isEmpty())
    }
}
