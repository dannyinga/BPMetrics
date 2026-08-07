package inga.bpmetrics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import inga.bpmetrics.db.LocalBpmDataPoint
import inga.bpmetrics.db.PendingRecordEntity
import inga.bpmetrics.db.RecordingDAO
import inga.bpmetrics.db.RecordingDB
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented test for [RecordingDAO].
 *
 * Verifies that the temporary watch-side database correctly persists heart rate
 * data points during a workout session, and holds finished records until they sync.
 */
@RunWith(AndroidJUnit4::class)
class BpmWatchDaoTest {

    private lateinit var db: RecordingDB
    private lateinit var dao: RecordingDAO

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecordingDB::class.java).build()
        dao = db.bpmWatchDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndFetchPoints() = runBlocking {
        dao.insert(LocalBpmDataPoint(timestamp = 1000L, bpm = 72.0))
        dao.insert(LocalBpmDataPoint(timestamp = 2000L, bpm = 75.0))

        val points = dao.getAllPoints()
        assertEquals(2, points.size)
        assertEquals(72.0, points[0].bpm, 0.0)
        assertEquals(2000L, points[1].timestamp)
    }

    @Test
    fun deleteAllPoints() = runBlocking {
        dao.insert(LocalBpmDataPoint(timestamp = 100L, bpm = 60.0))
        dao.deleteAll()

        val points = dao.getAllPoints()
        assertEquals(0, points.size)
    }

    @Test
    fun insertAllStoresAWholeBatch() = runBlocking {
        // Samples arrive from Health Services in batches and are written in one transaction
        // rather than one row at a time.
        val batch = (0 until 50).map { i ->
            LocalBpmDataPoint(timestamp = i * 1000L, bpm = 60.0 + i)
        }

        dao.insertAll(batch)

        val points = dao.getAllPoints()
        assertEquals(50, points.size)
        assertEquals(0L, points.first().timestamp)
        assertEquals(49_000L, points.last().timestamp)
    }

    @Test
    fun insertAllAcceptsAnEmptyBatch() = runBlocking {
        // Every delivered batch can be filtered down to nothing by the range check.
        dao.insertAll(emptyList())

        assertEquals(0, dao.getAllPoints().size)
    }

    @Test
    fun pointsAreReturnedInTimestampOrder() = runBlocking {
        dao.insertAll(
            listOf(
                LocalBpmDataPoint(timestamp = 3000L, bpm = 80.0),
                LocalBpmDataPoint(timestamp = 1000L, bpm = 70.0),
                LocalBpmDataPoint(timestamp = 2000L, bpm = 75.0)
            )
        )

        assertEquals(listOf(1000L, 2000L, 3000L), dao.getAllPoints().map { it.timestamp })
    }

    @Test
    fun getFirstPointReturnsTheEarliestSample() = runBlocking {
        assertNull(dao.getFirstPoint())

        dao.insertAll(
            listOf(
                LocalBpmDataPoint(timestamp = 5000L, bpm = 90.0),
                LocalBpmDataPoint(timestamp = 500L, bpm = 65.0)
            )
        )

        assertEquals(500L, dao.getFirstPoint()?.timestamp)
    }

    @Test
    fun pendingRecordsSurviveUntilExplicitlyRemoved() = runBlocking {
        // The outbox is what makes syncing reliable: a record stays put until the phone has it.
        dao.insertPendingRecord(PendingRecordEntity(recordJson = """{"one":1}"""))
        dao.insertPendingRecord(PendingRecordEntity(recordJson = """{"two":2}"""))

        val pending = dao.getAllPendingRecordsFlow().first()
        assertEquals(2, pending.size)

        dao.deletePendingRecord(pending.first())

        val remaining = dao.getAllPendingRecordsFlow().first()
        assertEquals(1, remaining.size)
        assertEquals("""{"two":2}""", remaining.first().recordJson)
    }

    @Test
    fun clearingPointsLeavesPendingRecordsAlone() = runBlocking {
        // A finished record is moved to the outbox before the session's samples are wiped,
        // so clearing one table must never take the other with it.
        dao.insert(LocalBpmDataPoint(timestamp = 100L, bpm = 60.0))
        dao.insertPendingRecord(PendingRecordEntity(recordJson = """{"kept":true}"""))

        dao.deleteAll()

        assertEquals(0, dao.getAllPoints().size)
        assertEquals(1, dao.getAllPendingRecordsFlow().first().size)
    }
}
