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
        // A relaxed mock would answer with a made-up watch, and every watch would look known.
        coEvery { repository.knownWatchFor(any(), any()) } returns null
        // Flows the restore reads. A relaxed mock's never emits, and `first()` on it throws.
        every { repository.getAllCategories() } returns flowOf(emptyList())
        every { repository.allTags } returns flowOf(emptyList())
        every { repository.allEventsInTree } returns flowOf(emptyList())
        every { repository.getAllCollections() } returns flowOf(emptyList())
        every { repository.tagsOf(any()) } returns flowOf(emptyList())
        every { repository.tagsOf(any()) } returns flowOf(emptyList())
        coEvery { repository.getPresetsForBackup() } returns emptyList()
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `a legacy group's cover goes on the event it becomes`() = runTest {
        coEvery { repository.createEvent("Bass Canyon", any()) } returns 9L

        restoreBackup(
            LibraryBackup(
                formatVersion = 4,
                eventGroups = listOf(EventGroupDto(name = "Bass Canyon", cover = CoverDto(cropTop = 0.2f)))
            ),
            repository, context
        )

        coVerify {
            repository.restoreCover(context, inga.bpmetrics.library.ItemRef.Event(9L), any(), any(), any())
        }
        coVerify(exactly = 0) {
            repository.restoreCover(context, inga.bpmetrics.library.ItemRef.Collection(9L), any(), any(), any())
        }
    }

    @Test
    fun `an event from before nesting goes under the group it named`() = runTest {
        // Written to events.groupId until S10, which nothing reads, so the event lost its place.
        coEvery { repository.createEvent("Bass Canyon", any()) } returns 9L
        coEvery { repository.createEvent("Day 1", any()) } returns 103L

        restoreBackup(
            LibraryBackup(
                formatVersion = 4,
                eventGroups = listOf(EventGroupDto(name = "Bass Canyon")),
                events = listOf(EventDto(name = "Day 1", groupName = "Bass Canyon"))
            ),
            repository, context
        )

        coVerify {
            repository.restoreEventTaxonomy(
                eventId = 103L, parentId = 9L,
                windowStart = any(), windowEnd = any(), type = any(),
                excludedFromParentAnalysis = any()
            )
        }
    }

    @Test
    fun `a rule is translated into the library it lands in`() = runTest {
        coEvery { repository.createEvent("Bass Canyon", any()) } returns 103L
        coEvery { repository.restoreCollection(any(), any(), any(), any(), any()) } returns 400L
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
        coEvery { repository.restoreCollection(any(), any(), any(), any(), any()) } returns 400L
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
    fun `a watch known here under another id is recognised, not added again`() = runTest {
        // Here it is 501af8fd; the file, written after a reinstall, calls it 9949b398.
        coEvery { repository.knownWatchFor("9949b398", "node-og") } returns
            inga.bpmetrics.library.WatchEntity(watchId = "501af8fd")
        coEvery { repository.restoreCollection(any(), any(), any(), any(), any()) } returns 400L
        val written = slot<String>()
        coEvery { repository.setCollectionRule(400L, capture(written)) } returns true

        val result = restoreBackup(
            LibraryBackup(
                watches = listOf(WatchDto(watchId = "9949b398", lastKnownNodeId = "node-og")),
                collections = listOf(
                    CollectionDto(
                        name = "Blue's",
                        filterJson = FilterCodec.toJson(FilterState(selectedWatchIds = setOf("9949b398")))
                    )
                )
            ),
            repository, context
        )

        coVerify(exactly = 0) {
            repository.restoreWatch(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify { repository.addWatchAlias("9949b398", "501af8fd") }
        assertEquals(0, result.watchesCreated)
        assertEquals(setOf("501af8fd"), FilterCodec.parseOrNull(written.captured)!!.selectedWatchIds)
    }

    // --- Add, replace or skip, per item ---

    private val readings = listOf(BpmDataPointDto(1_000L, 100.0), BpmDataPointDto(2_000L, 101.0))

    @Test
    fun `an event already here isn't added again, and its recordings land in it`() = runTest {
        every { repository.allEventsInTree } returns flowOf(
            listOf(inga.bpmetrics.library.EventEntity(eventId = 70, name = "Bass Canyon", createdAt = 111L))
        )
        coEvery { repository.saveWatchRecordToLibrary(any(), any(), any(), any()) } returns 77L

        val result = restoreBackup(
            LibraryBackup(
                events = listOf(EventDto(eventId = 3, name = "Bass Canyon", createdAt = 111L)),
                records = listOf(
                    BpmRecordJsonDto(startTime = 1_000L, endTime = 61_000L, eventId = 3, dataPoints = readings)
                )
            ),
            repository, context
        )

        coVerify(exactly = 0) { repository.createEvent(any(), any()) }
        coVerify { repository.assignRecordsToEvent(listOf(77L), 70L) }
        assertEquals(0, result.eventsCreated)
    }

    @Test
    fun `a skipped event hands its recordings to the event above it`() = runTest {
        coEvery { repository.createEvent("Bass Canyon", any()) } returns 103L
        coEvery { repository.saveWatchRecordToLibrary(any(), any(), any(), any()) } returns 77L

        restoreBackup(
            LibraryBackup(
                events = listOf(
                    EventDto(eventId = 3, name = "Bass Canyon"),
                    EventDto(eventId = 4, name = "Day 1", parentEventId = 3)
                ),
                records = listOf(
                    BpmRecordJsonDto(startTime = 1_000L, endTime = 61_000L, eventId = 4, dataPoints = readings)
                )
            ),
            repository, context,
            choices = RestoreChoices(mapOf(RestoreChoices.id(RestoreKind.EVENT, "id:4") to RestoreChoice.SKIP))
        )

        coVerify(exactly = 0) { repository.createEvent("Day 1", any()) }
        coVerify { repository.assignRecordsToEvent(listOf(77L), 103L) }
    }

    @Test
    fun `an added event keeps when it was made`() = runTest {
        restoreBackup(
            LibraryBackup(events = listOf(EventDto(eventId = 3, name = "Bass Canyon", createdAt = 111L))),
            repository, context
        )
        coVerify { repository.createEvent("Bass Canyon", 111L) }
    }

    @Test
    fun `a person here is kept unless replaced, and a new one can be skipped`() = runTest {
        val backup = LibraryBackup(
            people = listOf(PersonDto(name = "Danny", colorArgb = 9), PersonDto(name = "Bethany"))
        )

        restoreBackup(
            backup, repository, context,
            choices = RestoreChoices(mapOf(RestoreChoices.id(RestoreKind.PERSON, "bethany") to RestoreChoice.SKIP))
        )
        coVerify(exactly = 0) { repository.setPersonColor(any(), any()) }
        coVerify(exactly = 0) { repository.addPerson(any(), any()) }

        restoreBackup(
            backup, repository, context,
            choices = RestoreChoices(mapOf(RestoreChoices.id(RestoreKind.PERSON, "danny") to RestoreChoice.REPLACE))
        )
        coVerify { repository.setPersonColor(50L, 9) }
        coVerify { repository.addPerson("Bethany", any()) }
    }

    @Test
    fun `a recording here is replaced only when asked, and never re-saved`() = runTest {
        coEvery { repository.recordIdsByIdentity() } returns mapOf(Triple(1_000L, 61_000L, "Watch") to 5L)
        val backup = LibraryBackup(
            records = listOf(
                BpmRecordJsonDto(title = "Subtronics", startTime = 1_000L, endTime = 61_000L, dataPoints = readings)
            )
        )

        restoreBackup(backup, repository, context)
        coVerify(exactly = 0) { repository.rename(match { it is inga.bpmetrics.library.ItemRef.Recording }, any()) }

        val result = restoreBackup(
            backup, repository, context,
            choices = RestoreChoices(
                mapOf(RestoreChoices.id(RestoreKind.RECORDING, RestoreKeys.recording(1_000L, 61_000L, "Watch")) to RestoreChoice.REPLACE)
            )
        )
        coVerify { repository.rename(inga.bpmetrics.library.ItemRef.Recording(5L), "Subtronics") }
        coVerify(exactly = 0) { repository.saveWatchRecordToLibrary(any(), any(), any(), any()) }
        assertEquals(1, result.itemsReplaced)
    }

    @Test
    fun `a restore leaves this phone's settings alone unless asked`() = runTest {
        val backup = LibraryBackup(
            settings = listOf(inga.bpmetrics.ui.settings.PreferenceSnapshot("use_24_hour", "boolean", "true"))
        )

        restoreBackup(backup, repository, context)
        coVerify(exactly = 0) { repository.restoreSettings(any()) }

        restoreBackup(
            backup, repository, context,
            choices = RestoreChoices(mapOf(RestoreChoices.id(RestoreKind.SETTING, "use_24_hour") to RestoreChoice.REPLACE))
        )
        coVerify(exactly = 1) { repository.restoreSettings(backup.settings) }
    }

    @Test
    fun `the same recording twice in one file is saved once, and progress is reported`() = runTest {
        coEvery { repository.saveWatchRecordToLibrary(any(), any(), any(), any()) } returns 77L
        val twice = BpmRecordJsonDto(
            deviceId = "SM-R900", startTime = 1_000L, endTime = 61_000L,
            dataPoints = listOf(BpmDataPointDto(1_000L, 100.0), BpmDataPointDto(2_000L, 101.0))
        )
        val seen = mutableListOf<Pair<Int, Int>>()

        val result = restoreBackup(
            LibraryBackup(records = listOf(twice, twice)), repository, context,
            onProgress = { done, total -> seen += done to total }
        )

        coVerify(exactly = 1) { repository.saveWatchRecordToLibrary(any(), any(), any(), any()) }
        assertEquals(1, result.recordsImported)
        assertEquals(1, result.recordsAlreadyHere)
        assertEquals(listOf(1 to 2, 2 to 2), seen)
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

        coVerify(exactly = 0) {
            repository.restoreWatch(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals(0, result.watchesCreated)
        assertNull(result.failure)
    }
}
