package inga.bpmetrics.library

import android.content.Context
import android.util.Log
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Repository for managing BPM records and metadata (tags/categories) in the local Room database.
 *
 * This class provides an API for accessing and manipulating BPM data,
 * including creating, reading, updating, and deleting records.
 *
 * @param context The application context for initializing the database.
 * @param settingsRepository The repository for app preferences.
 */
class LibraryRepository(
    context: Context,
    private val settingsRepository: SettingsRepository
) {

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
    private val database = LibraryDatabase.getInstance(context)
    private val recordDao = database.bpmRecordDao()
    private val tagDao = database.tagDao()

    init {
        startRecordFlowFromDB()
    }

    /**
     * Starts a coroutine to collect BPM records from the database
     * and update the [_records] StateFlow.
     */
    private fun startRecordFlowFromDB() {
        scope.launch {
            recordDao.getAllRecordsFlow()
                .collect { records ->
                    _records.value = records  // send updates to controller/UI
                }
        }
    }

    /**
     * Updates the title of a BPM record.
     *
     * @param recordId The ID of the record to update.
     * @param newTitle The new title for the record.
     */
    fun updateRecordTitle(recordId: Long, newTitle: String) {
        scope.launch {
            Log.d(tag, "Updating title for record $recordId to: $newTitle")
            recordDao.updateTitleOnly(recordId, newTitle)
        }
    }

    /**
     * Updates the description of a BPM record.
     *
     * @param recordId The ID of the record to update.
     * @param newDescription The new description for the record.
     */
    fun updateRecordDescription(recordId: Long, newDescription: String) {
        scope.launch {
            Log.d(tag, "Updating description for record $recordId to: $newDescription")
            recordDao.updateDescriptionOnly(recordId, newDescription)
        }
    }

    /**
     * Deletes a BPM record and its associated data points from the database.
     *
     * @param id The ID of the record to delete.
     */
    suspend fun deleteRecordWithId(id: Long) {
        Log.d(tag, "Deleting record and data points for ID: $id")
        recordDao.deleteRecordById(id)
        recordDao.deleteDataPointsByRecordId(id)
    }

    /**
     * Deletes all BPM records and data points from the database.
     */
    suspend fun deleteAll() {
        Log.d(tag, "Deleting all records and data points from database")
        recordDao.deleteAllRecords()
        recordDao.deleteAllDataPoints()
    }

    /**
     * Retrieves a BPM record by its ID.
     *
     * @param id The ID of the record to retrieve.
     * @return The BpmRecord with the specified ID.
     */
    suspend fun getRecordWithId(id: Long) : BpmRecord {
        return recordDao.getRecord(id)
    }

    /**
     * Retrieves a BPM data point by its ID.
     *
     * @param id The ID of the data point to retrieve.
     * @return The BpmDataPointEntity with the specified ID.
     */
    suspend fun getDataPointWithId(id: Long) : BpmDataPointEntity{
        return recordDao.getDataPoint(id)
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
        val recordId = recordDao.insertBpmRecordGetId(recordEntity)
        Log.d(tag, "Base record inserted with ID: $recordId")
        
        // 2. Perform analysis and batch insert data points
        performAnalysisAndSaveDataPoints(record, recordId)

        // 3. Set the initial "Untitled" name
        autoNameRecord(recordId, "Untitled")
        
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
        val dataPointIds = recordDao.insertAllDataPoints(dataPointEntities)
        Log.d(tag, "Batch inserted ${dataPointIds.size} data points")

        // 4. Update the record with calculated analysis results using the generated IDs
        val avg = if (totalTime > 0) bpmWeightedSum / totalTime else 0.0
        val minId = dataPointIds.getOrNull(minIndex)
        val maxId = dataPointIds.getOrNull(maxIndex)

        Log.d(tag, "Updating analysis for record ID: $recordId. Avg: $avg, MinID: $minId, MaxID: $maxId")
        recordDao.updateAnalysis(recordId, minId, maxId, avg)
    }

    /**
     * Creates a base BpmRecordEntity from a BpmWatchRecord.
     *
     * @param record The BpmWatchRecord to convert.
     * @return A BpmRecordEntity with initial data.
     */
    private fun getBaseRecordEntity(record: BpmWatchRecord): BpmRecordEntity {
        return BpmRecordEntity(
            title = "New Record", // Temporary title before auto-naming
            date = record.date.time,
            startTime = record.startTime,
            endTime = record.endTime,
            durationMs = record.durationMs
        )
    }

    /**
     * Automatically names a record with a prefix and an incrementing count.
     * Example: "Spiderman 5", "Untitled 2".
     *
     * @param recordId The ID of the record to name.
     * @param prefix The prefix for the name.
     */
    private suspend fun autoNameRecord(recordId: Long, prefix: String) {
        val count = recordDao.countRecordsWithTitlePrefix(prefix)
        val newTitle = "$prefix ${count + 1}"
        recordDao.updateTitleOnly(recordId, newTitle)
    }

    // --- Category & Tag Management ---

    /**
     * Returns a flow of all available categories.
     */
    fun getAllCategories(): Flow<List<CategoryEntity>> = tagDao.getAllCategoriesFlow()

    /**
     * Returns a flow of all tags within a specific category.
     * 
     * @param categoryId The ID of the category.
     */
    fun getTagsByCategory(categoryId: Long): Flow<List<TagEntity>> = tagDao.getTagsByCategoryFlow(categoryId)

    /**
     * Creates a new category.
     * 
     * @param name The name of the category.
     */
    suspend fun createCategory(name: String) {
        tagDao.insertCategory(CategoryEntity(name = name))
    }

    /**
     * Creates a new tag under a specific category.
     * 
     * @param name The name of the tag.
     * @param categoryId The ID of the category this tag belongs to.
     */
    suspend fun createTag(name: String, categoryId: Long) {
        tagDao.insertTag(TagEntity(name = name, parentCategoryId = categoryId))
    }

    /**
     * Deletes a category and all its tags (via cascade).
     * 
     * @param category The category entity to delete.
     */
    suspend fun deleteCategory(category: CategoryEntity) {
        tagDao.deleteCategory(category)
    }

    /**
     * Deletes a specific tag.
     * 
     * @param tag The tag entity to delete.
     */
    suspend fun deleteTag(tag: TagEntity) {
        tagDao.deleteTag(tag)
    }

    // --- Record-Tag Assignment ---

    /**
     * Assigns a tag to a record and triggers an auto-rename if it belongs to the 
     * default naming category defined in settings.
     * 
     * @param recordId The ID of the record.
     * @param tagId The ID of the tag to assign.
     */
    suspend fun addTagToRecord(recordId: Long, tagId: Long) {
        tagDao.insertRecordTagCrossRef(RecordTagCrossRef(recordId, tagId))
        
        // Auto-Rename logic: Pull default naming category from settings
        val defaultNamingCatId = settingsRepository.defaultNamingCategoryId.first()
        val tag = tagDao.getTagById(tagId)
        
        if (tag != null && tag.parentCategoryId == defaultNamingCatId) {
            autoNameRecord(recordId, tag.name)
        }
    }

    /**
     * Removes a tag from a record.
     * 
     * @param recordId The ID of the record.
     * @param tagId The ID of the tag to remove.
     */
    suspend fun removeTagFromRecord(recordId: Long, tagId: Long) {
        tagDao.untagRecord(recordId, tagId)
    }

    /**
     * Returns a flow of all tags currently assigned to a specific record.
     * 
     * @param recordId The ID of the record.
     */
    fun getTagsForRecord(recordId: Long): Flow<List<TagEntity>> = tagDao.getTagsForRecordFlow(recordId)

    // --- Analysis ---

    /**
     * Returns a flow of tag rankings within a category by average BPM.
     * 
     * @param categoryId The ID of the category.
     */
    fun getCategoryRanking(categoryId: Long): Flow<List<TagRanking>> = tagDao.getCategoryRankingFlow(categoryId)

    /**
     * Performs an advanced analysis of specified tags within a date range.
     * 
     * @param tagIds List of tag IDs to analyze.
     * @param startDate Start of the date range (ms).
     * @param endDate End of the date range (ms).
     */
    suspend fun getAdvancedTagAnalysis(tagIds: List<Long>, startDate: Long, endDate: Long): List<AdvancedTagAnalysis> {
        return tagDao.getAdvancedTagAnalysis(tagIds, startDate, endDate)
    }
}
