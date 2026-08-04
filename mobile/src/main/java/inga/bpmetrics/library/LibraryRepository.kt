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
import kotlinx.coroutines.flow.firstOrNull
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
    private val watchDao = database.watchDao()

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
     * Updates the device ID and wearer name of a BPM record.
     */
    suspend fun updateRecordDeviceAndWearer(recordId: Long, deviceId: String, wearerName: String) {
        recordDao.updateDeviceAndWearer(recordId, deviceId, wearerName)
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
     * @return The ID of the newly created record.
     */
    suspend fun saveWatchRecordToLibrary(
        record: BpmWatchRecord,
        customTitle: String? = null,
        sourceNodeId: String? = null,
        preferRegistryName: Boolean = false
    ): Long {
        Log.d(tag, "Starting saveWatchRecordToLibrary")
        _savingRecord.value = true

        val watch = resolveWatch(record, sourceNodeId)

        // The wearer is stamped here and then frozen: renaming a watch later must not rewrite the
        // attribution of recordings already made under the old name.
        //
        // Records arriving from a watch take the registry's current name, because the watch itself
        // no longer names its wearer. Imported records keep the name in the file, which is their
        // own historical attribution and not ours to overwrite.
        val stampedWearer = if (preferRegistryName) {
            watch?.customName?.takeIf { it.isNotBlank() }
                ?: record.wearerName?.takeIf { it.isNotBlank() }
                ?: ""
        } else {
            record.wearerName?.takeIf { it.isNotBlank() }
                ?: watch?.customName?.takeIf { it.isNotBlank() }
                ?: ""
        }

        val recordEntity = BpmRecordEntity(
            title = customTitle ?: record.title?.takeIf { it.isNotBlank() } ?: "New Record",
            description = record.description ?: "",
            date = record.date.time,
            startTime = record.startTime,
            endTime = record.endTime,
            durationMs = record.durationMs,
            deviceId = record.deviceId,
            wearerName = stampedWearer,
            watchId = watch?.watchId
        )
        val recordId = recordDao.insertBpmRecordGetId(recordEntity)

        performAnalysisAndSaveDataPoints(record, recordId)

        // Handle Tags from Import cleanly without category duplication
        if (record.tagNames.isNotEmpty()) {
            attachTagsToRecord(recordId, record.tagNames)
        }

        // Only auto-name if it's a fresh recording without a title (and not from CSV with a title)
        if (customTitle == null && record.title.isNullOrBlank()) {
            autoNameRecord(recordId, "Untitled")
        }
        
        _savingRecord.value = false
        Log.d(tag, "Finished saveWatchRecordToLibrary for ID: $recordId")
        return recordId
    }

    // --- Watch Registry ---

    /** Every known watch, most recently used first. */
    fun getAllWatches(): Flow<List<WatchEntity>> = watchDao.getAllWatchesFlow()

    suspend fun getWatch(watchId: String): WatchEntity? = watchDao.getWatch(watchId)

    /**
     * Gives a watch a name. Only affects records that arrive from now on.
     */
    suspend fun renameWatch(watchId: String, name: String) {
        watchDao.updateName(watchId, name.trim())
        Log.d(tag, "Renamed watch $watchId to '${name.trim()}'")
    }

    suspend fun setWatchColor(watchId: String, colorArgb: Int?) = watchDao.updateColor(watchId, colorArgb)

    suspend fun countRecordsForWatch(watchId: String): Int = watchDao.countRecordsForWatch(watchId)

    /**
     * Registers a watch before it has ever sent a record.
     *
     * Because names are stamped at ingest, a watch handed out unnamed produces recordings labelled
     * with its model. Naming it in advance is how that is avoided.
     */
    suspend fun registerWatch(watchId: String, name: String, model: String = "") {
        val now = System.currentTimeMillis()
        watchDao.insertWatch(
            WatchEntity(
                watchId = watchId,
                customName = name.trim(),
                lastKnownModel = model,
                firstSeen = now,
                lastSeen = now
            )
        )
        // insertWatch ignores conflicts so it cannot clobber an existing name; apply the new one
        // explicitly for the case where the watch was already known.
        if (name.isNotBlank()) watchDao.updateName(watchId, name.trim())
    }

    /**
     * Re-stamps the wearer on records from one watch within a date range.
     *
     * The recovery path for recordings that arrived before their watch had been named.
     *
     * @return how many records were changed.
     */
    suspend fun reattributeRecords(watchId: String, wearerName: String, fromDate: Long, toDate: Long): Int {
        val changed = watchDao.reattributeRecords(watchId, wearerName.trim(), fromDate, toDate)
        Log.d(tag, "Re-attributed $changed record(s) from watch $watchId to '${wearerName.trim()}'")
        return changed
    }

    /**
     * Folds one watch entry into another, moving its records across.
     *
     * Needed because a watch that recorded before it could report a stable id is registered under
     * its model name, and the same watch on a current build registers under its generated id.
     */
    suspend fun mergeWatches(fromWatchId: String, intoWatchId: String) {
        if (fromWatchId == intoWatchId) return
        watchDao.reassignRecords(fromWatchId, intoWatchId)
        watchDao.deleteWatch(fromWatchId)
        Log.d(tag, "Merged watch $fromWatchId into $intoWatchId")
    }

    /** Removes a watch entry. Records keep their frozen wearer names and lose only the link. */
    suspend fun deleteWatch(watchId: String) = watchDao.deleteWatch(watchId)

    /**
     * Finds or creates the registry entry for an incoming record, and notes that it was seen.
     *
     * Prefers the stable id the watch generates. Older records only carry a device id, which is
     * the hardware model unless someone set one, so two identical watches collapse into a single
     * entry until they are separated by hand.
     */
    private suspend fun resolveWatch(record: BpmWatchRecord, sourceNodeId: String?): WatchEntity? {
        val key = record.watchId?.takeIf { it.isNotBlank() }
            ?: record.deviceId.takeIf { it.isNotBlank() }
            ?: return null

        val now = System.currentTimeMillis()
        watchDao.insertWatch(
            WatchEntity(
                watchId = key,
                lastKnownModel = record.deviceId,
                lastKnownNodeId = sourceNodeId.orEmpty(),
                firstSeen = now,
                lastSeen = now
            )
        )
        watchDao.touchWatch(key, now, record.deviceId, sourceNodeId.orEmpty())
        return watchDao.getWatch(key)
    }

    /**
     * Attaches tags to a record, resolving category:tag pairs and reusing existing categories/tags case-insensitively.
     *
     * Supports two formats:
     * - "CategoryName:TagName" (new format from v1.1 CSV/JSON exports)
     * - "TagName" (old format from pre-v1.1 CSV exports — searches all existing tags first)
     */
    suspend fun attachTagsToRecord(recordId: Long, tagNames: List<String>) {
        if (tagNames.isEmpty()) return
        val allCategories = tagDao.getAllCategoriesFlow().firstOrNull() ?: emptyList()

        tagNames.forEach { rawTag ->
            val parts = rawTag.split(":", limit = 2)
            val hasCategory = parts.size == 2 && parts[0].isNotBlank()
            val categoryName = if (hasCategory) parts[0].trim() else null
            val tagName = if (hasCategory) parts[1].trim() else rawTag.trim()

            if (tagName.isBlank()) return@forEach

            if (categoryName != null) {
                // Explicit Category:Tag format — find or create the category, then find or create the tag
                val existingCategory = allCategories.find { it.name.equals(categoryName, ignoreCase = true) }
                val categoryId = existingCategory?.categoryId
                    ?: tagDao.insertCategory(CategoryEntity(name = categoryName))

                val existingTags = tagDao.getTagsByCategoryFlow(categoryId).firstOrNull() ?: emptyList()
                val tagId = existingTags.find { it.name.equals(tagName, ignoreCase = true) }?.tagId
                    ?: tagDao.insertTag(TagEntity(name = tagName, parentCategoryId = categoryId))

                tagDao.insertRecordTagCrossRef(RecordTagCrossRef(recordId, tagId))
            } else {
                // Bare tag name (old CSV format) — search ALL existing tags across ALL categories
                var matchedTagId: Long? = null
                for (cat in allCategories) {
                    val tagsInCat = tagDao.getTagsByCategoryFlow(cat.categoryId).firstOrNull() ?: emptyList()
                    val match = tagsInCat.find { it.name.equals(tagName, ignoreCase = true) }
                    if (match != null) {
                        matchedTagId = match.tagId
                        break
                    }
                }

                if (matchedTagId != null) {
                    // Found existing tag — reuse it in its original category
                    tagDao.insertRecordTagCrossRef(RecordTagCrossRef(recordId, matchedTagId))
                } else {
                    // No existing tag found — create under "Uncategorized"
                    val uncatCategory = allCategories.find { it.name.equals("Uncategorized", ignoreCase = true) }
                    val uncatId = uncatCategory?.categoryId
                        ?: tagDao.insertCategory(CategoryEntity(name = "Uncategorized"))

                    val newTagId = tagDao.insertTag(TagEntity(name = tagName, parentCategoryId = uncatId))
                    tagDao.insertRecordTagCrossRef(RecordTagCrossRef(recordId, newTagId))
                }
            }
        }
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
            val nextTimestamp = if (i < record.dataPoints.size - 1) record.dataPoints[i + 1].timestamp
                                else record.durationMs
            val dt = nextTimestamp - dataPoint.timestamp

            if (dt <= BpmRecord.GAP_THRESHOLD_MS) {
                bpmWeightedSum += dataPoint.bpm * dt
                totalTime += dt
            }

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
        val avg = if (totalTime > 0) {
            bpmWeightedSum / totalTime
        } else {
            record.dataPoints.map { it.bpm }.average().takeIf { !it.isNaN() } ?: 0.0
        }

        val minId = dataPointIds.getOrNull(minIndex)
        val maxId = dataPointIds.getOrNull(maxIndex)

        Log.d(tag, "Updating analysis for record ID: $recordId. Avg: $avg, MinID: $minId, MaxID: $maxId")
        recordDao.updateAnalysis(recordId, minId, maxId, avg)
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
     * Updates an existing category (e.g., renaming).
     */
    suspend fun updateCategory(category: CategoryEntity) {
        tagDao.updateCategory(category)
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
     * Updates an existing tag (e.g., renaming).
     */
    suspend fun updateTag(tag: TagEntity) {
        tagDao.updateTag(tag)
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
}
