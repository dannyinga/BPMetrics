package inga.bpmetrics.datasync

import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataItemBuffer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataClientListenerTest {

    private val mockDataClient = mockk<DataClient>(relaxed = true)
    private val mockProcessor = mockk<DataClientProcessor>(relaxed = true)
    private lateinit var listener: DataClientListener

    @Before
    fun setup() {
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        listener = DataClientListener(mockDataClient, mockProcessor)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    /**
     * The sweep itself moved to [DataClientProcessor] so the manifest-registered service could run
     * it too — a record that arrives while the app is closed is exactly the case that needs it. So
     * this now checks that the listener *asks for* a sweep, rather than reaching into the data
     * layer itself; who reads the data layer is the processor's business.
     */
    @Test
    fun testOnStartAddsListenerAndSweeps() = runBlocking {
        val mockOwner = mockk<LifecycleOwner>()
        coEvery { mockProcessor.sweepExistingRecords() } returns Unit

        listener.onStart(mockOwner)

        verify { mockDataClient.addListener(listener) }
        coVerify { mockProcessor.sweepExistingRecords() }
    }

    @Test
    fun testOnStopRemovesListener() {
        val mockOwner = mockk<LifecycleOwner>()
        listener.onStop(mockOwner)
        verify { mockDataClient.removeListener(listener) }
    }

    @Test
    fun testOnDataChangedProcessesItems() = runBlocking {
        val mockEventBuffer = mockk<DataEventBuffer>(relaxed = true)
        val mockEvent = mockk<DataEvent>()
        val mockItem = mockk<DataItem>()

        // Explicitly mock the chain to prevent ClassCastException
        every { mockEvent.type } returns DataEvent.TYPE_CHANGED
        every { mockEvent.dataItem } returns mockItem
        every { mockItem.freeze() } returns mockItem // Ensure freeze returns the same DataItem mock
        
        every { mockEventBuffer.iterator() } returns mutableListOf(mockEvent).iterator()

        listener.onDataChanged(mockEventBuffer)

        // Wait a bit for the coroutine in listener to finish
        Thread.sleep(150) 

        coVerify { mockProcessor.processDataItem(any()) }
        verify { mockEventBuffer.release() }
    }
}
