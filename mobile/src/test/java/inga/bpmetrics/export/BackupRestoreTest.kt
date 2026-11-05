package inga.bpmetrics.export

import android.content.Context
import android.util.Log
import inga.bpmetrics.library.FilterCodec
import inga.bpmetrics.library.FilterState
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a restore writes, against a repository that records the calls.
 *
 * Each case is a way a restore used to put something in the wrong place: a legacy group's cover
 * onto the collections table, a smart collection's rule with ids from another library, and
 * watches without their wearer.
 */
class BackupRestoreTest {

    private val repository = mockk<LibraryRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    /** Already in the library being restored into, with an id the file knows nothing about. */
    private val danny = PersonEntity(personId = 50, name = "Danny", colorArgb = 0)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        coEvery { repository.inBulk(any<suspend () -> RestoreResult>()) } coAnswers {
            firstArg<suspend () -> RestoreResult>().invoke()
        }
        every { repository.getAllPeople() } returns flowOf(listOf(danny))
        every { repository.getAllLocations() } returns flowOf(emptyList())
        every { repository.getAllWatches() } returns flowOf(emptyList())
        coEvery { repository.recordIdsByIdentity() } returns emptyMap()
        coEvery { repository.addPerson(any(), any()) } returns 60L
        coEvery { repository.setCollectionRule(any(), any()) } returns true
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `a legacy group's cover goes on the event it becomes`() = runTest {
        coEvery { repository.createEventGroup("Bass Canyon") } returns 9L

        restoreBackup(
            LibraryBackup(
                formatVersion = 4,
                eventGroups = listOf(EventGroupDto(name = "Bass Canyon", cover = CoverDto(cropTop = 0.2f)))
            ),
            repository, context
        )

        coVerify {
            repository.restoreCover(context, LibraryRepository.CoverOwner.Event(9L), any(), any(), any())
        }
        coVerify(exactly = 0) {
            repository.restoreCover(context, LibraryRepository.CoverOwner.Collection(9L), any(), any(), any())
        }
    }

    @Test
    fun `a rule is translated into the library it lands in`() = runTest {
        coEvery { repository.createEvent("Bass Canyon", any()) } returns 103L
        coEvery { repository.restoreCollection(any(), any(), any(), any()) } returns 400L
        val written = slot<String>()
        coEvery { repository.setCollectionRule(400L, capture(written)) } returns true

        val result = restoreBackup(
            LibraryBackup(
                people = listOf(PersonDto(name = "Danny")),
                events = listOf(EventDto(eventId = 3, name = "Bass Canyon")),
                collections = listOf(
                    CollectionDto(
                        name = "Danny BC",
                        sourceId = 12,
                        filterJson = FilterCodec.toJson(
                            FilterState(selectedEventIds = setOf(3), selectedPersonIds = setOf(2))
                        ),
                        ruleNames = RuleNames(people = listOf(NamedRef(2, "Danny")))
                    )
                )
            ),
            repository, context
        )

        val rule = FilterCodec.parseOrNull(written.captured)!!
        assertEquals(setOf(103L), rule.selectedEventIds)
        // Danny was already here as 50; the file called him 2.
        assertEquals(setOf(50L), rule.selectedPersonIds)
        assertTrue(result.rulesNeedingAttention.isEmpty())
    }

    @Test
    fun `a rule from an older file is reported, and matches nothing it can't identify`() = runTest {
        coEvery { repository.createEvent("Bass Canyon", any()) } returns 103L
        coEvery { repository.restoreCollection(any(), any(), any(), any()) } returns 400L
        val written = slot<String>()
        coEvery { repository.setCollectionRule(400L, capture(written)) } returns true

        val result = restoreBackup(
            LibraryBackup(
                formatVersion = 6,
                events = listOf(EventDto(eventId = 3, name = "Bass Canyon")),
                collections = listOf(
                    CollectionDto(
                        name = "Danny BC",
                        filterJson = FilterCodec.toJson(
                            FilterState(selectedEventIds = setOf(3), selectedPersonIds = setOf(2))
                        )
                    )
                )
            ),
            repository, context
        )

        val rule = FilterCodec.parseOrNull(written.captured)!!
        assertEquals(setOf(103L), rule.selectedEventIds)
        assertEquals(setOf(RuleRemap.NOTHING), rule.selectedPersonIds)
        assertEquals(listOf("Danny BC"), result.rulesNeedingAttention)
        assertTrue(result.summary().contains("Danny BC"))
    }

    @Test
    fun `a watch comes back with its wearer and its own dates`() = runTest {
        restoreBackup(
            LibraryBackup(
                people = listOf(PersonDto(name = "Danny")),
                watches = listOf(
                    WatchDto(
                        watchId = "501af8fd", deviceName = "Blue", lastKnownModel = "SM-R900",
                        wearerName = "Danny", colorArgb = 7, firstSeen = 1_000L, lastSeen = 2_000L
                    ),
                    // A format 6 watch: no wearer, no dates.
                    WatchDto(watchId = "9949b398", lastKnownModel = "SM-R900")
                )
            ),
            repository, context
        )

        coVerify {
            repository.restoreWatch("501af8fd", "Blue", "SM-R900", 50L, 7, 1_000L, 2_000L)
        }
        // Unknown stays unknown: 0, never the time of the restore.
        coVerify { repository.restoreWatch("9949b398", "", "SM-R900", null, null, 0L, 0L) }
    }

    @Test
    fun `a watch the library already has keeps what it has`() = runTest {
        every { repository.getAllWatches() } returns flowOf(
            listOf(inga.bpmetrics.library.WatchEntity(watchId = "501af8fd"))
        )

        val result = restoreBackup(
            LibraryBackup(watches = listOf(WatchDto(watchId = "501af8fd", wearerName = "Danny"))),
            repository, context
        )

        coVerify(exactly = 0) { repository.restoreWatch(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(0, result.watchesCreated)
        assertNull(result.failure)
    }
}
