package inga.bpmetrics.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.ui.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Date

/**
 * One watch is one entry, whatever id its app is sending (Audit #39).
 *
 * The watch app makes its id up and keeps it in its own data, so a reinstall (as switching between
 * a debug and a release build forces) sends a new one. The Data Layer node doesn't change, and a
 * merge remembers the id it folded away.
 */
@RunWith(AndroidJUnit4::class)
class WatchIdentityTest {

    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        every { settingsRepository.defaultNamingCategoryId } returns flowOf(null)
        repository = LibraryRepository(context, settingsRepository, db)
    }

    @After
    fun closeDb() {
        repository.close()
        db.close()
    }

    private var clock = 1_000_000L

    private fun recording(watchId: String?, model: String = "SM-R905U"): BpmWatchRecord {
        clock += 3_600_000L
        return BpmWatchRecord(
            date = Date(clock),
            dataPoints = listOf(BpmDataPoint(clock, 90.0), BpmDataPoint(clock + 1_000, 95.0)),
            startTime = clock,
            endTime = clock + 60_000,
            deviceId = model,
            watchId = watchId
        )
    }

    private suspend fun watchOf(recordId: Long) = repository.getRecordWithId(recordId)!!.metadata.watchId

    @Test
    fun aReinstalledWatchLandsOnItsOldEntry() = runBlocking {
        val first = repository.saveWatchRecordToLibrary(recording("old-id"), sourceNodeId = "node-og")
        repository.renameWatch("old-id", "The OG")

        // Same watch, app reinstalled: a new id, the same node.
        val after = repository.saveWatchRecordToLibrary(recording("new-id"), sourceNodeId = "node-og")

        assertEquals("old-id", watchOf(first))
        assertEquals("old-id", watchOf(after))
        assertEquals(listOf("old-id"), db.watchDao().getAllWatchesFlow().first().map { it.watchId })
        // Remembered, so it matches even if the node is never reported again.
        val later = repository.saveWatchRecordToLibrary(recording("new-id"), sourceNodeId = null)
        assertEquals("old-id", watchOf(later))
    }

    @Test
    fun twoWatchesOfTheSameModelStayApart() = runBlocking {
        repository.saveWatchRecordToLibrary(recording("danny"), sourceNodeId = "node-a")
        repository.saveWatchRecordToLibrary(recording("bethany"), sourceNodeId = "node-b")

        assertEquals(
            setOf("danny", "bethany"),
            db.watchDao().getAllWatchesFlow().first().map { it.watchId }.toSet()
        )
    }

    @Test
    fun aMergeSticksWhenTheNodeIsUnknown() = runBlocking {
        // Restored entries have no node: the case where only the alias can recognise the watch.
        repository.saveWatchRecordToLibrary(recording("older"))
        repository.saveWatchRecordToLibrary(recording("newer"))

        repository.mergeWatches(fromWatchId = "newer", intoWatchId = "older")
        val next = repository.saveWatchRecordToLibrary(recording("newer"))

        assertEquals("older", watchOf(next))
        assertNull(db.watchDao().getWatch("newer"))
    }

    @Test
    fun aMergeCarriesTheNodeOver() = runBlocking {
        repository.saveWatchRecordToLibrary(recording("older"))
        repository.saveWatchRecordToLibrary(recording("newer"), sourceNodeId = "node-og")

        repository.mergeWatches(fromWatchId = "newer", intoWatchId = "older")
        // A third id, after another reinstall: matched by the node the merge carried over.
        val next = repository.saveWatchRecordToLibrary(recording("third"), sourceNodeId = "node-og")

        assertEquals("older", watchOf(next))
        assertEquals("node-og", db.watchDao().getWatch("older")!!.lastKnownNodeId)
    }

    @Test
    fun aMergeKeepsAliasesItHadAbsorbed() = runBlocking {
        repository.saveWatchRecordToLibrary(recording("a"))
        repository.saveWatchRecordToLibrary(recording("b"))
        repository.saveWatchRecordToLibrary(recording("c"))
        repository.mergeWatches("a", "b")
        repository.mergeWatches("b", "c")

        assertEquals("c", watchOf(repository.saveWatchRecordToLibrary(recording("a"))))
        assertEquals(setOf("a", "b"), repository.watchAliases()["c"].orEmpty().toSet())
    }
}
