package inga.bpmetrics.datasync

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.LibraryRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.sql.Date

@RunWith(AndroidJUnit4::class)
class DataClientProcessorTest {

    private val mockDataClient = mockk<DataClient>(relaxed = true)
    private val mockRepository = mockk<LibraryRepository>(relaxed = true)
    private lateinit var processor: DataClientProcessor

    @Before
    fun setup() {
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        mockkStatic(DataMapItem::class)
        processor = DataClientProcessor(mockDataClient, mockRepository)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun setupMockRecordResponse(mockItem: DataItem, uri: Uri, record: BpmWatchRecord) {
        val json = Gson().toJson(record)
        val bytes = json.toByteArray(Charsets.UTF_8)
        
        val mockDataMapItem = mockk<DataMapItem>()
        val mockDataMap = mockk<DataMap>()
        val mockAsset = mockk<Asset>()
        
        every { DataMapItem.fromDataItem(mockItem) } returns mockDataMapItem
        every { mockDataMapItem.dataMap } returns mockDataMap
        every { mockDataMap.getAsset("record_asset") } returns mockAsset
        
        val mockResponse = mockk<DataClient.GetFdForAssetResponse>()
        every { mockResponse.inputStream } returns ByteArrayInputStream(bytes)
        
        val mockTask = mockk<Task<DataClient.GetFdForAssetResponse>>()
        every { mockDataClient.getFdForAsset(mockAsset) } returns mockTask
        coEvery { mockTask.await() } returns mockResponse

        val mockDeleteTask = mockk<Task<Int>>()
        every { mockDataClient.deleteDataItems(uri) } returns mockDeleteTask
        coEvery { mockDeleteTask.await() } returns 1
    }

    @Test
    fun testProcessValidDataItem() = runBlocking {
        val recordId = "test_record_1"
        val uri = Uri.parse("wear://host/bpm_record/$recordId")
        val mockItem = mockk<DataItem> {
            every { this@mockk.uri } returns uri
        }

        setupMockRecordResponse(mockItem, uri, BpmWatchRecord(Date(0), emptyList(), 0, 1000))

        processor.processDataItem(mockItem)

        coVerify { mockRepository.saveWatchRecordToLibrary(any()) }
        coVerify { mockDataClient.deleteDataItems(uri) }
    }

    @Test
    fun testProcessDuplicateRecordSkipped() = runBlocking {
        val uri = Uri.parse("wear://host/bpm_record/duplicate_id")
        val mockItem = mockk<DataItem> {
            every { this@mockk.uri } returns uri
        }

        // Mock success for the first call
        setupMockRecordResponse(mockItem, uri, BpmWatchRecord(Date(0), emptyList(), 0, 1000))

        // Process first time (Success)
        processor.processDataItem(mockItem)
        // Process second time (Should return early)
        processor.processDataItem(mockItem)

        // Deletion and Repository should only have been called once
        coVerify(exactly = 1) { mockRepository.saveWatchRecordToLibrary(any()) }
        coVerify(exactly = 1) { mockDataClient.deleteDataItems(uri) }
    }

    @Test
    fun testProcessInvalidPathSkipped() = runBlocking {
        val mockItem = mockk<DataItem> {
            every { uri } returns Uri.parse("wear://host/other_path/123")
        }

        processor.processDataItem(mockItem)

        coVerify(exactly = 0) { mockRepository.saveWatchRecordToLibrary(any()) }
    }

    @Test
    fun testProcessErrorHandlingWithMissingAsset() = runBlocking {
        val uri = Uri.parse("wear://host/bpm_record/error_id")
        val mockItem = mockk<DataItem> {
            every { this@mockk.uri } returns uri
        }

        val mockDataMapItem = mockk<DataMapItem>()
        val mockDataMap = mockk<DataMap>()
        every { DataMapItem.fromDataItem(mockItem) } returns mockDataMapItem
        every { mockDataMapItem.dataMap } returns mockDataMap
        every { mockDataMap.getAsset("record_asset") } returns null

        processor.processDataItem(mockItem)

        coVerify(exactly = 0) { mockRepository.saveWatchRecordToLibrary(any()) }
    }
}
