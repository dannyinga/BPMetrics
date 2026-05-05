package inga.bpmetrics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented test for [BpmWatchDao].
 *
 * Verifies that the temporary watch-side database correctly persists heart rate
 * data points during a workout session.
 */
@RunWith(AndroidJUnit4::class)
class BpmWatchDaoTest {

    private lateinit var db: BpmWatchDatabase
    private lateinit var dao: BpmWatchDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BpmWatchDatabase::class.java).build()
        dao = db.bpmWatchDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndFetchPoints() = runBlocking {
        val point1 = LocalBpmDataPoint(timestamp = 1000L, bpm = 72.0)
        val point2 = LocalBpmDataPoint(timestamp = 2000L, bpm = 75.0)
        
        dao.insert(point1)
        dao.insert(point2)
        
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
}
