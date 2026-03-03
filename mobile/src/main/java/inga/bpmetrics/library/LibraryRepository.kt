package inga.bpmetrics.library

import android.content.Context
import android.util.Log
import inga.bpmetrics.core.BpmWatchRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Repository for managing BPM records in the local Room database.
 *
 * This class provides an API for accessing and manipulating BPM data,
 * including creating, reading, updating, and deleting records.
 *
 * @param context The application context for initializing the database.
 */
class LibraryRepository(context: Context) {

    // A flow that emits true when a record is being saved, and false otherwise.
    private val _savingRecord = MutableStateFlow(false)
    /**
     * A StateFlow that indicates whether a record is currently being saved to the database.
     */
    val savingRecord = _savingRecord.asStateFlow()

    // A flow that emits the list of all BPM records.
    private val _records = MutableStateFlow<List<BpmRecord>>(emptyList())
    /**
     * A StateFlow that provides the current list of all BPM records in the database.
     */
    val records: StateFlow<List<BpmRecord>> = _records.asStateFlow()

    private val tag = "LibraryRepository"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = LibraryDatabase.getInstance(context).bpmRecordDao()

    init {
        startRecordFlowFromDB()
    }

    /**
     * Starts a coroutine to collect BPM records from the database
     * and update the [_records] StateFlow.
     */
    private fun startRecordFlowFromDB() {
        scope.launch {
            dao.getAllRecordsFlow()
                .collect { records ->
                    _records.value = records  // send updates to controller/UI
                }
        }
    }

    /**
     * Updates the title of a BPM record.
     *
     * @param record The record to update.
     * @param newTitle The new title for the record.
     */
    fun updateRecordTitle(record: BpmRecord, newTitle: String) {
        scope.launch {
            Log.d(tag, "Updating title for record ${record.metadata.recordId} to: $newTitle")
            // Update the local object immediately for UI snappiness
            record.metadata.title = newTitle
            // Update ONLY the title in the DB so we don't touch analysis columns
            dao.updateTitleOnly(record.metadata.recordId, newTitle)
        }
    }

    /**
     * Deletes a BPM record and its associated data points from the database.
     *
     * @param id The ID of the record to delete.
     */
    suspend fun deleteRecordWithId(id: Long) {
        Log.d(tag, "Deleting record and data points for ID: $id")
        dao.deleteRecordById(id)
        dao.deleteDataPointsByRecordId(id)
    }

    /**
     * Deletes all BPM records and data points from the database.
     */
    suspend fun deleteAll() {
        Log.d(tag, "Deleting all records and data points from database")
        dao.deleteAllRecords()
        dao.deleteAllDataPoints()
    }

    /**
     * Retrieves a BPM record by its ID.
     *
     * @param id The ID of the record to retrieve.
     * @return The BpmRecord with the specified ID.
     */
    suspend fun getRecordWithId(id: Long) : BpmRecord {
        return dao.getRecord(id)
    }

    /**
     * Retrieves a BPM data point by its ID.
     *
     * @param id The ID of the data point to retrieve.
     * @return The BpmDataPointEntity with the specified ID.
     */
    suspend fun getDataPointWithId(id: Long) : BpmDataPointEntity{
        return dao.getDataPoint(id)
    }

    /**
     * Saves a BpmWatchRecord to the library.
     *
     * This function creates a base record, analyzes it to calculate min, max, and average BPM,
     * saves all data points in a single batch, and then updates the record with the results.
     *
     * @param record The BpmWatchRecord to save.
     */
    suspend fun saveWatchRecordToLibrary(record: BpmWatchRecord) {
        Log.d(tag, "Starting saveWatchRecordToLibrary")
        _savingRecord.value = true
        
        // 1. Insert base record to get the recordId
        val recordEntity = getBaseRecordEntity(record)
        val recordId = dao.insertBpmRecordGetId(recordEntity)
        Log.d(tag, "Base record inserted with ID: $recordId")
        
        // 2. Perform analysis and batch insert data points
        performAnalysisAndSaveDataPoints(record, recordId)
        
        _savingRecord.value = false
        Log.d(tag, "Finished saveWatchRecordToLibrary for ID: $recordId")
    }

    /**
     * Analyzes a BpmWatchRecord, saves its data points in a batch, and updates the record with statistics.
     *
     * @param record The source BpmWatchRecord.
     * @param recordId The ID of the record in the database.
     */
    private suspend fun performAnalysisAndSaveDataPoints(
        record: BpmWatchRecord,
        recordId: Long
    ) {
        if (record.dataPoints.isEmpty()) {
            Log.w(tag, "No data points to analyze for record ID: $recordId")
            return
        }

        Log.d(tag, "Analyzing ${record.dataPoints.size} data points for record ID: $recordId")

        var maxBpm = -1.0
        var maxIndex = 0

        var minBpm = 300.0
        var minIndex = 0

        var bpmWeightedSum = 0.0
        var totalTime = 0L

        val dataPointEntities = record.dataPoints.mapIndexed { i, dataPoint ->
            // Calculate weighted average components
            val dt = (if (i < record.dataPoints.size - 1) record.dataPoints[i + 1].timestamp
                      else record.durationMs) - dataPoint.timestamp

            bpmWeightedSum += dataPoint.bpm * dt
            totalTime += dt

            // Track min/max indices
            if (dataPoint.bpm > maxBpm) {
                maxBpm = dataPoint.bpm
                maxIndex = i
            }
            if (dataPoint.bpm < minBpm) {
                minBpm = dataPoint.bpm
                minIndex = i
            }

            BpmDataPointEntity(
                recordOwnerId = recordId,
                timestamp = dataPoint.timestamp,
                bpm = dataPoint.bpm
            )
        }

        // 3. Batch insert all data points
        val dataPointIds = dao.insertAllDataPoints(dataPointEntities)
        Log.d(tag, "Batch inserted ${dataPointIds.size} data points")

        // 4. Update the record with calculated analysis results using the generated IDs
        val avg = if (totalTime > 0) bpmWeightedSum / totalTime else 0.0
        val minId = dataPointIds.getOrNull(minIndex)
        val maxId = dataPointIds.getOrNull(maxIndex)

        Log.d(tag, "Updating analysis for record ID: $recordId. Avg: $avg, MinID: $minId, MaxID: $maxId")
        dao.updateAnalysis(recordId, minId, maxId, avg)
    }

    /**
     * Creates a base BpmRecordEntity from a BpmWatchRecord.
     *
     * The title of the record is generated from the record's date and start time.
     *
     * @param record The BpmWatchRecord to convert.
     * @return A BpmRecordEntity with initial data.
     */
    private fun getBaseRecordEntity(record: BpmWatchRecord): BpmRecordEntity {
        var title = record.date.toString()

        val formatter = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.getDefault())

        title += " ${
            Instant.ofEpochMilli(record.startTime)
                .atZone(ZoneId.systemDefault())
                .format(formatter)
        }"

        return BpmRecordEntity(
            title = title,
            date = record.date.time,
            startTime = record.startTime,
            endTime = record.endTime,
            durationMs = record.durationMs
        )
    }
}
