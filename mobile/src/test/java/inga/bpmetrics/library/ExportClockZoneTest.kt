package inga.bpmetrics.library

import android.content.Context
import inga.bpmetrics.ui.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The clock an export is drawn in.
 *
 * Reported as a Chicago set exporting in Pacific time with its venue set and inherited. Two faults,
 * stacked, and either one alone produced the symptom:
 *
 * 1. **The render read its zone off the preset.** A preset's zone is `ZoneId.systemDefault()` as of
 *    the moment it was created, frozen into its JSON — every one of the eight presets on the device
 *    that reported this carried `America/Los_Angeles`. So every export was captioned in Pacific
 *    time wherever it happened.
 * 2. **The recording's own zone was left stale.** It is frozen onto the row, and it was recomputed
 *    only when membership *moved* a recording. Filing by hand and moving an event beneath a venue
 *    both change a recording's clock without moving it, so neither ever updated it.
 *
 * Fixing only the first would have fallen straight back to Pacific on exactly the recordings the
 * second left stale.
 */
class ExportClockZoneTest {

    private val chicago =
        LocationEntity(locationId = 7, name = "Chicago", timeZoneId = "America/Chicago")

    private fun recording(id: Long, event: Long?, zone: String? = null) = BpmRecordEntity(
        recordId = id,
        title = "Record $id",
        date = id * 1000,
        startTime = id * 1000,
        endTime = id * 1000 + 100,
        durationMs = 100,
        eventId = event,
        timeZoneId = zone
    )

    // --- The precedence: the venue, and only then a fallback ---

    @Test
    fun `a recording with a venue answers the venue zone`() {
        assertEquals(
            "America/Chicago",
            resolvedZoneOf(listOf(recording(1, event = 1, zone = "America/Chicago")))
        )
    }

    /**
     * No venue answers *nothing*, not the phone's zone.
     *
     * The distinction is the whole fix. `clock` falls back to the reader's zone, which is right for
     * a screen and wrong here, because an export has a better fallback of its own. An answer that
     * arrives looking like a real zone cannot be told apart from one that was guessed.
     */
    @Test
    fun `no venue answers null rather than guessing`() {
        assertNull(resolvedZoneOf(listOf(recording(1, event = null))))
        assertNull(resolvedZoneOf(emptyList()))
    }

    /** One unfiled wearer at the front of the list must not decide the clock for everyone else. */
    @Test
    fun `the first recording with a zone decides, not the first recording`() {
        val records = listOf(
            recording(1, event = null),
            recording(2, event = 1, zone = "America/Chicago")
        )
        assertEquals("America/Chicago", resolvedZoneOf(records))
    }

    /** A zone id the platform does not recognise is ignored rather than trusted. */
    @Test
    fun `an unrecognised zone id is not an answer`() {
        assertNull(resolvedZoneOf(listOf(recording(1, event = 1, zone = "Mars/Olympus_Mons"))))
    }

    /**
     * With no venue, an export reads in the phone's zone, the one Contents showed the clip in.
     *
     * It used to fall back to the preset's frozen zone: an unfiled set at The Granada, shown as
     * 12:23 AM, rendered as 10:23 PM because every preset said Pacific.
     */
    @Test
    fun `an export with no venue reads in the phone's zone, not a preset's`() {
        val central = java.time.ZoneId.of("America/Chicago")
        assertEquals("America/Chicago", exportZoneOf(listOf(recording(1, event = null)), phoneZone = central))
        assertEquals(
            "America/Denver",
            exportZoneOf(listOf(recording(1, event = 1, zone = "America/Denver")), phoneZone = central)
        )
    }

    // --- The stale column ---

    private val context = mockk<Context>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val database = mockk<LibraryDatabase>(relaxed = true)
    private val recordDao = mockk<BpmRecordDao>(relaxed = true)
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val locationDao = mockk<LocationDao>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0

        mockkObject(LibraryDatabase.Companion)
        every { LibraryDatabase.getInstance(any()) } returns database
        every { database.bpmRecordDao() } returns recordDao
        every { database.eventDao() } returns eventDao
        every { database.locationDao() } returns locationDao
    }

    /**
     * The case that was actually hit: a recording filed into a venue's event by hand.
     *
     * `assignRecordsToEvent` writes the event before reconciling, so the recording is already where
     * it was put and membership has nothing to move. The zone was recomputed only on a move, so it
     * never was — and the export read the stale column.
     */
    @Test
    fun `a clock is recomputed even when nothing moved`() = runTest {
        val event = EventEntity(eventId = 1, name = "Chicago set", locationId = chicago.locationId)
        // Already filed — exactly the state a hand-filing leaves behind — with no zone yet.
        val filed = recording(10, event = 1, zone = null)

        coEvery { eventDao.getAllEvents() } returns listOf(event)
        coEvery { eventDao.getAllWindowPeople() } returns emptyList()
        coEvery { recordDao.getAllRecordEntities() } returns listOf(filed)
        coEvery { locationDao.getAll() } returns listOf(chicago)

        val moved = LibraryRepository(context, settingsRepository).reconcileMembership()

        // Nothing moved, which is precisely why this used to skip the zones entirely.
        assertEquals(0, moved)
        coVerify { recordDao.updateResolvedTimeZone(10L, "America/Chicago") }
    }

    /**
     * Moving an event beneath a venue: the recording stays put, and still changes clock.
     *
     * Its own event has no venue; the one it now sits inside does. Inherited, which is what the
     * report said.
     */
    @Test
    fun `a clock inherited through a moved event is recomputed`() = runTest {
        val festival = EventEntity(eventId = 1, name = "Festival", locationId = chicago.locationId)
        val day = EventEntity(eventId = 2, name = "Day 2", parentId = 1)
        val filed = recording(10, event = 2, zone = "America/Los_Angeles")

        coEvery { eventDao.getAllEvents() } returns listOf(festival, day)
        coEvery { eventDao.getAllWindowPeople() } returns emptyList()
        coEvery { recordDao.getAllRecordEntities() } returns listOf(filed)
        coEvery { locationDao.getAll() } returns listOf(chicago)

        LibraryRepository(context, settingsRepository).reconcileMembership()

        coVerify { recordDao.updateResolvedTimeZone(10L, "America/Chicago") }
    }

    /** A library already right is left alone — which is what makes running this every launch cheap. */
    @Test
    fun `a correct clock is not rewritten`() = runTest {
        val event = EventEntity(eventId = 1, name = "Chicago set", locationId = chicago.locationId)
        val filed = recording(10, event = 1, zone = "America/Chicago")

        coEvery { eventDao.getAllEvents() } returns listOf(event)
        coEvery { eventDao.getAllWindowPeople() } returns emptyList()
        coEvery { recordDao.getAllRecordEntities() } returns listOf(filed)
        coEvery { locationDao.getAll() } returns listOf(chicago)

        LibraryRepository(context, settingsRepository).reconcileMembership()

        coVerify(exactly = 0) { recordDao.updateResolvedTimeZone(any(), any()) }
    }
}
