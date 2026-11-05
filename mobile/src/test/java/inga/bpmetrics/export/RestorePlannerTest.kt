package inga.bpmetrics.export

import inga.bpmetrics.ui.settings.PreferenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview a restore shows before writing anything: every item, whether it's here already, and
 * what may be chosen for it. It has to agree with what the restore then does, so each case is a
 * matching rule `restoreBackup` uses.
 */
class RestorePlannerTest {

    private fun recording(start: Long, device: String = "SM-R900", readings: Int = 2) = BpmRecordJsonDto(
        title = "Set at $start",
        deviceId = device,
        startTime = start,
        endTime = start + 60_000,
        dataPoints = List(readings) { BpmDataPointDto(timestamp = start + it * 1_000L, bpm = 100.0) }
    )

    private val here = RestorePlanner.Library(
        recordIdentities = setOf(Triple(1_000L, 61_000L, "SM-R900")),
        peopleNames = setOf("danny"),
        watchIds = setOf("501af8fd"),
        venueNames = setOf("the gorge"),
        tags = setOf("artist:subtronics"),
        events = listOf(
            EventMatcher.Here(eventId = 70, name = "Bass Canyon", parentId = null, createdAt = 111L),
            EventMatcher.Here(eventId = 71, name = "Day 1", parentId = 70, createdAt = 222L)
        ),
        collections = setOf("concerts" to 333L),
        presets = mapOf("concert" to "{\"name\":\"Concert\"}"),
        settings = mapOf("use_24_hour" to "false", "date_format" to "MMM d, yyyy")
    )

    private fun RestorePreview.keysOf(kind: RestoreKind, matched: Boolean) =
        of(kind).filter { it.matched == matched }.map { it.label }

    @Test
    fun `recordings already here are matched, and unreadable ones are counted apart`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(
                records = listOf(
                    recording(1_000L), // here already
                    recording(2_000L),
                    recording(3_000L, readings = 0) // no readings: can't be read
                )
            ),
            here
        )
        assertEquals(listOf("Set at 1000"), preview.keysOf(RestoreKind.RECORDING, matched = true))
        assertEquals(listOf("Set at 2000"), preview.keysOf(RestoreKind.RECORDING, matched = false))
        assertEquals(1, preview.unreadableRecordings)
    }

    @Test
    fun `the same recording twice in one file is one item`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(records = listOf(recording(5_000L), recording(5_000L))),
            here
        )
        assertEquals(1, preview.of(RestoreKind.RECORDING).size)
    }

    @Test
    fun `an event is here when its name, parent and creation all agree`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(
                events = listOf(
                    EventDto(eventId = 3, name = "Bass Canyon", createdAt = 111L),
                    EventDto(eventId = 4, name = "Day 1", parentEventId = 3, createdAt = 222L),
                    // Same name, made on someone else's phone: a different event.
                    EventDto(eventId = 5, name = "Bass Canyon", createdAt = 999L),
                    // Same name and time, but under a new event, so it can't be the one here.
                    EventDto(eventId = 6, name = "Day 1", parentEventId = 5, createdAt = 222L)
                )
            ),
            here
        )
        val events = preview.of(RestoreKind.EVENT).associate { it.key to it.matched }
        assertEquals(mapOf("id:3" to true, "id:4" to true, "id:5" to false, "id:6" to false), events)
    }

    @Test
    fun `a child listed before its parent still matches`() {
        val matched = EventMatcher.match(
            listOf(
                EventDto(eventId = 4, name = "Day 1", parentEventId = 3, createdAt = 222L),
                EventDto(eventId = 3, name = "bass canyon", createdAt = 111L)
            ),
            here.events
        )
        assertEquals(mapOf(3L to 70L, 4L to 71L), matched)
    }

    @Test
    fun `people and venues are matched by name, ignoring case`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(
                people = listOf(PersonDto(name = "DANNY"), PersonDto(name = "Bethany")),
                locations = listOf(LocationDto(name = "The Gorge"), LocationDto(name = "Showbox"))
            ),
            here
        )
        assertEquals(listOf("Bethany"), preview.keysOf(RestoreKind.PERSON, matched = false))
        assertEquals(listOf("DANNY"), preview.keysOf(RestoreKind.PERSON, matched = true))
        assertEquals(listOf("Showbox"), preview.keysOf(RestoreKind.VENUE, matched = false))
    }

    @Test
    fun `watches are matched by id, by an alias, or by node`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(
                watches = listOf(
                    WatchDto(watchId = "501af8fd", deviceName = "Blue"),
                    WatchDto(watchId = "reinstalled", deviceName = "OG", lastKnownNodeId = "node-og"),
                    WatchDto(watchId = "9949b398", lastKnownModel = "SM-R900")
                )
            ),
            here.copy(watchNodes = setOf("node-og"))
        )
        assertEquals(listOf("Blue", "OG"), preview.keysOf(RestoreKind.WATCH, matched = true))
        assertEquals(listOf("SM-R900"), preview.keysOf(RestoreKind.WATCH, matched = false))
    }

    @Test
    fun `only tags that aren't here yet are listed`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(
                events = listOf(
                    EventDto(eventId = 1, name = "Bass Canyon", tags = listOf("Artist:Subtronics", "Artist:Excision"))
                )
            ),
            here
        )
        assertEquals(listOf("Artist › Excision"), preview.of(RestoreKind.TAG).map { it.label })
    }

    @Test
    fun `collections are here by name and creation`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(
                collections = listOf(
                    CollectionDto(name = "Concerts", createdAt = 333L),
                    CollectionDto(name = "Concerts", createdAt = 444L)
                )
            ),
            here
        )
        assertEquals(listOf(true, false), preview.of(RestoreKind.COLLECTION).map { it.matched })
    }

    @Test
    fun `only settings a person would recognise, and only ones that differ, are offered`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(
                settings = listOf(
                    PreferenceSnapshot("use_24_hour", "boolean", "true"),
                    PreferenceSnapshot("date_format", "string", "MMM d, yyyy"), // same as here
                    // An id from the other library, and bookkeeping that does harm when older.
                    PreferenceSnapshot("default_naming_category_id", "long", "4"),
                    PreferenceSnapshot("built_in_preset_revision", "int", "1")
                )
            ),
            here
        )
        val setting = preview.of(RestoreKind.SETTING).single()
        assertEquals("use_24_hour", setting.key)
        assertEquals("false", setting.from)
        assertEquals("true", setting.to)
    }

    @Test
    fun `what's here starts on Skip, what's new on Add, and a setting is kept`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(
                records = listOf(recording(1_000L), recording(9_000L)),
                settings = listOf(PreferenceSnapshot("use_24_hour", "boolean", "true"))
            ),
            here
        )
        val defaults = preview.items.associate { it.label to it.default }
        assertEquals(RestoreChoice.SKIP, defaults["Set at 1000"])
        assertEquals(RestoreChoice.ADD, defaults["Set at 9000"])
        assertEquals(RestoreChoice.SKIP, defaults["24-hour clock"])
        assertTrue(preview.changesAnything(RestoreChoices()))
    }

    @Test
    fun `restoring a library's own backup does nothing unless asked`() {
        val preview = RestorePlanner.preview(
            LibraryBackup(
                records = listOf(recording(1_000L)),
                people = listOf(PersonDto(name = "Danny")),
                events = listOf(EventDto(eventId = 3, name = "Bass Canyon", createdAt = 111L))
            ),
            here
        )
        assertFalse(preview.changesAnything(RestoreChoices()))
        assertTrue(
            preview.changesAnything(
                RestoreChoices(mapOf(RestoreChoices.id(RestoreKind.EVENT, "id:3") to RestoreChoice.REPLACE))
            )
        )
    }

    @Test
    fun `a choice an item can't take falls back to its default`() {
        // Adding a second copy of a recording that's here isn't offered.
        val choices = RestoreChoices(mapOf(RestoreChoices.id(RestoreKind.RECORDING, "k") to RestoreChoice.ADD))
        assertEquals(RestoreChoice.SKIP, choices.of(RestoreKind.RECORDING, "k", matched = true))
        assertEquals(RestoreChoice.ADD, choices.of(RestoreKind.RECORDING, "k", matched = false))
    }
}
