package inga.bpmetrics.library

import android.content.Context
import android.util.Log
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
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
    private val settingsRepository: SettingsRepository,
    /**
     * The database to work against. Defaults to the real one, so no production call site changes.
     *
     * Injectable because [init] starts collecting from the DAOs immediately: a test that swapped
     * the DAO fields by reflection afterwards would have its writes go to one database while
     * [records] kept reading the collector wired up at construction — which is exactly what the
     * instrumented tests were doing, silently, for as long as they existed.
     */
    private val database: LibraryDatabase = LibraryDatabase.getInstance(context)
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

    /**
     * Keeps a database failure in the background collector from reaching the default uncaught
     * handler and taking the process with it. The library going quiet is bad; the app dying is
     * worse, and offers no diagnostic either.
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Unhandled failure in library scope", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val recordDao = database.bpmRecordDao()
    private val tagDao = database.tagDao()
    private val watchDao = database.watchDao()
    private val personDao = database.personDao()
    private val eventDao = database.eventDao()
    private val eventGroupDao = database.eventGroupDao()
    private val savedAnalysisDao = database.savedAnalysisDao()
    private val presetDao = database.exportPresetDao()

    /** Backs the render queue, so a batch outlives the process that queued it. */
    val renderJobStore = inga.bpmetrics.export.RenderJobStore(database.renderJobDao())

    init {
        startRecordFlowFromDB()
    }

    /**
     * Turns any saved same-time analyses into events, once per install.
     *
     * Called from [inga.bpmetrics.BPMetricsApp] rather than from this constructor. Constructing a
     * repository should not move a user's data around — a caller that only wanted to read one
     * record would trigger it, and a unit test with mocked DAOs would start doing I/O it never
     * asked for.
     *
     * Marked done only on success, so a failure retries next launch rather than stranding them.
     */
    /**
     * Writes the built-in presets on first launch, off the main thread.
     *
     * Same reasoning as [convertConcurrentAnalysesOnce]: constructing a repository should not write
     * to the database, so this is called from the application object rather than from `init`.
     */
    fun seedBuiltInPresetsOnce() {
        scope.launch {
            try {
                seedBuiltInPresetsIfEmpty()
                // After seeding, because a fresh install writes the current framing and has
                // nothing to bring forward — this is for the installs that already had presets.
                refreshSupersededPresetFraming()
            } catch (e: Exception) {
                Log.e(tag, "Could not seed the built-in export presets", e)
            }
        }
    }

    fun convertConcurrentAnalysesOnce() {
        scope.launch {
            try {
                if (settingsRepository.hasConvertedConcurrentAnalyses()) return@launch
                val result = convertConcurrentAnalysesToEvents(this@LibraryRepository)
                if (result.failure == null) settingsRepository.setConvertedConcurrentAnalyses()
            } catch (e: Exception) {
                Log.e(tag, "Could not check whether same-time analyses need converting", e)
            }
        }
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
     * Suspends until the write has happened, rather than launching it into the repository's own
     * scope and returning. Both callers already reload the record immediately afterwards, so
     * fire-and-forget meant they reliably reloaded the value from *before* the edit — a rename
     * that appeared not to take until something else refreshed the screen.
     *
     * @param recordId The ID of the record to update.
     * @param newTitle The new title for the record.
     */
    suspend fun updateRecordTitle(recordId: Long, newTitle: String) {
        Log.d(tag, "Updating title for record $recordId to: $newTitle")
        recordDao.updateTitleOnly(recordId, newTitle)
    }

    /**
     * Updates the description of a BPM record.
     *
     * @param recordId The ID of the record to update.
     * @param newDescription The new description for the record.
     */
    suspend fun updateRecordDescription(recordId: Long, newDescription: String) {
        Log.d(tag, "Updating description for record $recordId to: $newDescription")
        recordDao.updateDescriptionOnly(recordId, newDescription)
    }

    /**
     * Updates the device ID and wearer name of a BPM record.
     */
    /**
     * Corrects a single recording's device and who wore it.
     *
     * The name is written alongside the link so the recording stays readable if that profile is
     * removed later — the same pairing used when a record first arrives.
     */
    suspend fun updateRecordDeviceAndWearer(recordId: Long, deviceId: String, personId: Long?) {
        val name = personId?.let { personDao.getPerson(it)?.name }.orEmpty()
        recordDao.updateDeviceAndWearer(recordId, deviceId, name, personId)
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

        // Who wore it is settled here and never revisited: handing the watch to someone else
        // tomorrow must not rewrite the attribution of recordings already made.
        //
        // Records arriving from a watch take whoever the registry says is wearing it, because the
        // watch itself no longer names its wearer. Imported records carry only a name, which is
        // their own historical attribution and not ours to overwrite — so those are matched to an
        // existing profile if one fits, and left as a bare name if not.
        val person = if (preferRegistryName) {
            watch?.currentPersonId?.let { personDao.getPerson(it) }
                ?: record.wearerName?.takeIf { it.isNotBlank() }?.let { personDao.findByName(it) }
        } else {
            record.wearerName?.takeIf { it.isNotBlank() }?.let { personDao.findByName(it) }
                ?: watch?.currentPersonId?.let { personDao.getPerson(it) }
        }

        // The name is stamped alongside the link, not instead of it. The link is what displays,
        // so a rename reaches every recording; the name is what remains readable if the profile
        // is deleted later.
        val stampedWearer = person?.name
            ?: record.wearerName?.takeIf { it.isNotBlank() }
            ?: watch?.currentWearerName?.takeIf { it.isNotBlank() }
            ?: ""

        val recordEntity = BpmRecordEntity(
            title = customTitle ?: record.title?.takeIf { it.isNotBlank() } ?: "New Record",
            description = record.description ?: "",
            date = record.date.time,
            startTime = record.startTime,
            endTime = record.endTime,
            durationMs = record.durationMs,
            deviceId = record.deviceId,
            wearerName = stampedWearer,
            watchId = watch?.watchId,
            personId = person?.personId
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

    // --- Saved Analyses ---

    /** Saved analyses, newest first. */
    fun getSavedAnalyses(): Flow<List<SavedAnalysisEntity>> = savedAnalysisDao.getAllFlow()

    /**
     * Stores an analysis, copying the values it was computed from.
     *
     * Copying rather than referencing is the point: the analysis is a statement about a moment,
     * and must not change when the library does.
     *
     * @return the id of the stored analysis.
     */
    suspend fun saveAnalysis(
        name: String,
        filterDescription: String,
        records: List<AnalysisSnapshotRecord>
    ): Long {
        val analysisId = savedAnalysisDao.insertAnalysis(
            SavedAnalysisEntity(
                name = name.trim(),
                createdAt = System.currentTimeMillis(),
                filterDescription = filterDescription
            )
        )

        savedAnalysisDao.insertRecords(
            records.map { record ->
                SavedAnalysisRecordEntity(
                    analysisId = analysisId,
                    recordId = record.recordId,
                    title = record.title,
                    date = record.date,
                    minBpm = record.minBpm,
                    avgBpm = record.avgBpm,
                    maxBpm = record.maxBpm,
                    activeDurationMs = record.activeDurationMs,
                    tagsEncoded = encodeTags(record.tags),
                    wearerName = record.wearerName,
                    watchName = record.watchName,
                    personId = record.personId,
                    personColorArgb = record.personColorArgb,
                    eventId = record.eventId,
                    eventName = record.eventName,
                    zonesEncoded = encodeZones(record.zones)
                )
            }
        )

        Log.d(tag, "Saved analysis '$name' with ${records.size} record(s)")
        return analysisId
    }

    /**
     * Every stored same-time analysis, with its rows.
     *
     * Exists for the one-time conversion into events — see [convertConcurrentAnalysesToEvents].
     */
    suspend fun getConcurrentAnalyses(): List<LoadedAnalysis> =
        savedAnalysisDao.getAllFlow().first()
            .filter { it.isConcurrent }
            .mapNotNull { loadSavedAnalysis(it.analysisId) }

    /** Reads a stored analysis back into the shape the analysis screen works from. */
    suspend fun loadSavedAnalysis(analysisId: Long): LoadedAnalysis? {
        val stored = savedAnalysisDao.getAnalysis(analysisId) ?: return null
        return LoadedAnalysis(
            metadata = stored.metadata,
            records = stored.records.map { entity ->
                AnalysisSnapshotRecord(
                    recordId = entity.recordId,
                    title = entity.title,
                    date = entity.date,
                    minBpm = entity.minBpm,
                    avgBpm = entity.avgBpm,
                    maxBpm = entity.maxBpm,
                    activeDurationMs = entity.activeDurationMs,
                    tags = decodeTags(entity.tagsEncoded),
                    wearerName = entity.wearerName,
                    watchName = entity.watchName,
                    personId = entity.personId,
                    personColorArgb = entity.personColorArgb,
                    eventId = entity.eventId,
                    eventName = entity.eventName,
                    zones = decodeZones(entity.zonesEncoded)
                )
            },
            recordsStillInLibrary = savedAnalysisDao.countRecordsStillPresent(analysisId)
        )
    }

    suspend fun renameSavedAnalysis(analysisId: Long, name: String) =
        savedAnalysisDao.rename(analysisId, name.trim())

    suspend fun deleteSavedAnalysis(analysisId: Long) = savedAnalysisDao.deleteAnalysis(analysisId)

    // --- Events and groups ---

    fun getAllEvents(): Flow<List<EventEntity>> = eventDao.getAllEventsFlow()

    fun getEventsForGroup(groupId: Long): Flow<List<EventEntity>> =
        eventDao.getEventsForGroupFlow(groupId)

    fun getUngroupedEvents(): Flow<List<EventEntity>> = eventDao.getUngroupedEventsFlow()

    fun getRecordsForEvent(eventId: Long): Flow<List<BpmRecordEntity>> =
        eventDao.getRecordsForEventFlow(eventId)

    /** Recordings filed under no event. Pinned in the events view so nothing goes missing. */
    fun getUnfiledRecords(): Flow<List<BpmRecordEntity>> = eventDao.getUnfiledRecordsFlow()

    fun countUnfiledRecords(): Flow<Int> = eventDao.countUnfiledRecordsFlow()

    suspend fun getEvent(eventId: Long): EventEntity? = eventDao.getEvent(eventId)

    /** When an event happened, derived from its recordings. Null while it has none. */
    suspend fun getEventSpan(eventId: Long): TimeSpan? = eventDao.getEventSpan(eventId)?.toSpan()

    suspend fun countRecordsForEvent(eventId: Long): Int = eventDao.countRecordsForEvent(eventId)

    suspend fun createEvent(name: String, groupId: Long? = null): Long {
        val id = eventDao.insertEvent(
            EventEntity(name = name.trim(), groupId = groupId, createdAt = System.currentTimeMillis())
        )
        Log.d(tag, "Created event '${name.trim()}' as $id")
        return id
    }

    suspend fun renameEvent(eventId: Long, name: String) = eventDao.rename(eventId, name.trim())

    suspend fun setEventNotes(eventId: Long, notes: String) = eventDao.updateNotes(eventId, notes)

    suspend fun setEventGroup(eventId: Long, groupId: Long?) = eventDao.setGroup(eventId, groupId)

    /**
     * Removes an event and releases its recordings.
     *
     * Deleting the container must never delete the contents — those recordings are the only copy of
     * something that happened, and the event is just a label someone put on them.
     */
    suspend fun deleteEvent(eventId: Long) {
        eventDao.unfileRecordsForEvent(eventId)
        eventDao.deleteEvent(eventId)
        Log.d(tag, "Deleted event $eventId; its recordings are unfiled, not removed")
    }

    /**
     * Files recordings under an event, or unfiles them when [eventId] is null.
     *
     * Chunked for the same reason as [assignPersonToRecords]: Room turns `IN (:ids)` into one bind
     * variable per id and SQLite caps those at 999, which select-all reaches.
     *
     * @return how many recordings changed.
     */
    suspend fun assignRecordsToEvent(recordIds: Collection<Long>, eventId: Long?): Int {
        if (recordIds.isEmpty()) return 0
        val changed = recordIds.toList()
            .chunked(SQL_VARIABLE_LIMIT)
            .sumOf { chunk -> eventDao.assignRecordsToEvent(chunk, eventId) }
        Log.d(tag, "Filed $changed recording(s) under event ${eventId ?: "nothing"}")
        return changed
    }

    /** Of these recordings, the ones not yet in an event. Chunked for the same reason as above. */
    suspend fun recordIdsWithoutEvent(recordIds: Collection<Long>): List<Long> =
        recordIds.toList()
            .chunked(SQL_VARIABLE_LIMIT)
            .flatMap { chunk -> eventDao.recordIdsWithoutEvent(chunk) }

    fun getAllEventGroups(): Flow<List<EventGroupEntity>> = eventGroupDao.getAllGroupsFlow()

    suspend fun getEventGroup(groupId: Long): EventGroupEntity? = eventGroupDao.getGroup(groupId)

    fun getRecordsForGroup(groupId: Long): Flow<List<BpmRecordEntity>> =
        eventGroupDao.getRecordsForGroupFlow(groupId)

    suspend fun getGroupSpan(groupId: Long): TimeSpan? = eventGroupDao.getGroupSpan(groupId)?.toSpan()

    suspend fun countEventsForGroup(groupId: Long): Int = eventGroupDao.countEventsForGroup(groupId)

    suspend fun countRecordsForGroup(groupId: Long): Int = eventGroupDao.countRecordsForGroup(groupId)

    suspend fun createEventGroup(name: String): Long {
        val id = eventGroupDao.insertGroup(
            EventGroupEntity(name = name.trim(), createdAt = System.currentTimeMillis())
        )
        Log.d(tag, "Created group '${name.trim()}' as $id")
        return id
    }

    suspend fun renameEventGroup(groupId: Long, name: String) =
        eventGroupDao.rename(groupId, name.trim())

    suspend fun setEventGroupNotes(groupId: Long, notes: String) =
        eventGroupDao.updateNotes(groupId, notes)

    /** Removes a collection and releases what it held. Its events and children survive. */
    suspend fun deleteEventGroup(groupId: Long) {
        eventGroupDao.ungroupEvents(groupId)
        eventGroupDao.orphanChildren(groupId)
        eventGroupDao.deleteGroup(groupId)
        Log.d(tag, "Deleted collection $groupId; its events and children are released, not removed")
    }

    /**
     * Files one collection inside another.
     *
     * @return false when the move would make a collection its own ancestor, or nest deeper than
     *   [CollectionTree.MAX_DEPTH]. Both are refused here rather than in the UI: a cycle makes
     *   every walk of the tree non-terminating, and no screen should be the only thing standing
     *   between the database and an infinite loop.
     */
    suspend fun setEventGroupParent(groupId: Long, parentGroupId: Long?): Boolean {
        val all = eventGroupDao.getAllGroups()
        if (!CollectionTree.canReparent(all, groupId, parentGroupId)) {
            Log.w(tag, "Refused to file collection $groupId under $parentGroupId")
            return false
        }
        eventGroupDao.setParent(groupId, parentGroupId)
        return true
    }

    /**
     * A collection and everything nested inside it, itself included.
     *
     * What "analyse Coachella" resolves to: the festival, its days, and every event in any of them.
     */
    suspend fun descendantGroupIds(groupId: Long): Set<Long> =
        CollectionTree.descendantsOf(eventGroupDao.getAllGroups(), groupId)

    /** Every saved analysis with its frozen rows, for a backup to carry. */
    suspend fun getSavedAnalysesForBackup(): List<inga.bpmetrics.export.SavedAnalysisDto> =
        savedAnalysisDao.getAllFlow().first().mapNotNull { meta ->
            val full = savedAnalysisDao.getAnalysis(meta.analysisId) ?: return@mapNotNull null
            inga.bpmetrics.export.SavedAnalysisDto(
                name = full.metadata.name,
                createdAt = full.metadata.createdAt,
                filterDescription = full.metadata.filterDescription,
                kind = full.metadata.kind,
                windowStartMs = full.metadata.windowStartMs,
                windowEndMs = full.metadata.windowEndMs,
                records = full.records.map { row ->
                    inga.bpmetrics.export.SavedAnalysisRecordDto(
                        recordId = row.recordId,
                        title = row.title,
                        date = row.date,
                        minBpm = row.minBpm,
                        avgBpm = row.avgBpm,
                        maxBpm = row.maxBpm,
                        activeDurationMs = row.activeDurationMs,
                        tagsEncoded = row.tagsEncoded,
                        wearerName = row.wearerName,
                        watchName = row.watchName
                    )
                }
            )
        }

    /**
     * Writes a saved analysis back from a backup.
     *
     * Its rows arrive already re-pointed at the recordings' new ids — the caller does the remapping,
     * because only the restore knows where each recording landed.
     */
    suspend fun restoreSavedAnalysis(dto: inga.bpmetrics.export.SavedAnalysisDto) {
        val analysisId = savedAnalysisDao.insertAnalysis(
            SavedAnalysisEntity(
                name = dto.name,
                createdAt = dto.createdAt,
                filterDescription = dto.filterDescription,
                kind = dto.kind,
                windowStartMs = dto.windowStartMs,
                windowEndMs = dto.windowEndMs
            )
        )
        savedAnalysisDao.insertRecords(
            dto.records.map { row ->
                SavedAnalysisRecordEntity(
                    analysisId = analysisId,
                    recordId = row.recordId,
                    title = row.title,
                    date = row.date,
                    minBpm = row.minBpm,
                    avgBpm = row.avgBpm,
                    maxBpm = row.maxBpm,
                    activeDurationMs = row.activeDurationMs,
                    tagsEncoded = row.tagsEncoded,
                    wearerName = row.wearerName,
                    watchName = row.watchName
                )
            }
        )
    }

    /** Every app preference, for a backup to carry. */
    suspend fun getSettingsForBackup() = settingsRepository.exportPreferences()

    /** Applies preferences from a backup. Returns how many were understood. */
    suspend fun restoreSettings(snapshots: List<inga.bpmetrics.ui.settings.PreferenceSnapshot>): Int =
        settingsRepository.importPreferences(snapshots)

    /**
     * Which library view the user last had open, read once rather than observed.
     *
     * The library owns this while it is on screen; settings only remembers it across launches.
     */
    suspend fun getLibraryViewMode(): String = settingsRepository.libraryViewMode.first()

    suspend fun setLibraryViewMode(mode: String) = settingsRepository.setLibraryViewMode(mode)

    /** Recordings the user has permanently waved off as an event suggestion. */
    val dismissedSuggestionRecords: Flow<Set<Long>> = settingsRepository.dismissedSuggestionRecords

    // --- Export presets ---

    fun getExportPresets(): Flow<List<ExportPresetEntity>> = presetDao.getAllFlow()

    suspend fun getExportPreset(presetId: Long): ExportPresetEntity? = presetDao.getPreset(presetId)

    suspend fun getDefaultExportPreset(): ExportPresetEntity? = presetDao.getDefault()

    /**
     * Writes the built-in presets, once.
     *
     * Seeded here rather than in the migration so a fresh install and an upgrade take the identical
     * path — otherwise what ships is defined twice, in Kotlin and in SQL, and the two drift. Keyed
     * on the table being empty rather than a flag: a user who deletes every preset gets them back,
     * which is better than an empty list they cannot repopulate.
     */
    suspend fun seedBuiltInPresetsIfEmpty() {
        if (presetDao.count() > 0) return
        inga.bpmetrics.export.ExportPreset.BUILT_IN.forEachIndexed { index, preset ->
            presetDao.insert(
                ExportPresetEntity(
                    name = preset.name,
                    configJson = preset.toJson(),
                    // The first is default so a new export has something selected rather than
                    // starting from nothing.
                    isDefault = index == 0,
                    isBuiltIn = true,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        Log.i(tag, "Seeded ${inga.bpmetrics.export.ExportPreset.BUILT_IN.size} built-in presets")
    }

    /**
     * Brings presets still carrying an old shipped framing up to the current one.
     *
     * Seeding only runs on an empty table, so an install from before the default changed keeps
     * drawing the graph where that build put it — and because a preset reapplies itself on every
     * export, dragging the frame fixes the clip in hand and nothing after it. Only framing nobody
     * chose is touched; a preset dragged even slightly off a shipped default is left alone.
     */
    suspend fun refreshSupersededPresetFraming() {
        var updated = 0
        presetDao.getAll().forEach { entity ->
            val preset = inga.bpmetrics.export.ExportPreset.fromJson(entity.configJson)
                ?: return@forEach
            if (!preset.hasSupersededFraming()) return@forEach
            presetDao.updateConfig(entity.presetId, preset.withDefaultFraming().toJson())
            updated++
        }
        if (updated > 0) Log.i(tag, "Moved $updated preset(s) onto the current graph framing")
    }

    suspend fun saveExportPreset(name: String, configJson: String): Long =
        presetDao.insert(
            ExportPresetEntity(
                name = name.trim(),
                configJson = configJson,
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun updateExportPreset(presetId: Long, name: String, configJson: String) =
        presetDao.update(presetId, name.trim(), configJson)

    /** At most one default, enforced by clearing the rest first rather than by a constraint. */
    suspend fun setDefaultExportPreset(presetId: Long) {
        presetDao.clearDefault()
        presetDao.markDefault(presetId)
    }

    suspend fun deleteExportPreset(presetId: Long) = presetDao.delete(presetId)

    suspend fun dismissSuggestionRecords(recordIds: Set<Long>) =
        settingsRepository.dismissSuggestionRecords(recordIds)

    /**
     * Flattens tags to `categoryId:categoryName:tagName`, one per line.
     *
     * Colons and newlines are stripped from the parts rather than escaped — these are display
     * labels, and a tag containing a separator is not worth a parser for.
     */
    private fun encodeTags(tags: List<AnalysisSnapshotTag>): String =
        tags.joinToString("\n") { tag ->
            "${tag.categoryId}:${tag.categoryName.sanitizeForEncoding()}:${tag.tagName.sanitizeForEncoding()}"
        }

    private fun decodeTags(encoded: String): List<AnalysisSnapshotTag> {
        if (encoded.isBlank()) return emptyList()
        return encoded.lineSequence().mapNotNull { line ->
            val parts = line.split(":", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val categoryId = parts[0].toLongOrNull() ?: return@mapNotNull null
            AnalysisSnapshotTag(tagName = parts[2], categoryId = categoryId, categoryName = parts[1])
        }.toList()
    }

    private fun String.sanitizeForEncoding(): String = replace(":", " ").replace("\n", " ")

    /**
     * Flattens time-in-band to `name:ms`, one per line — same shape and same reasoning as tags.
     *
     * Frozen at save because it cannot be recomputed: a saved analysis keeps no data points, so
     * the split has to be captured or it is gone.
     */
    private fun encodeZones(zones: List<SnapshotZone>): String =
        zones.joinToString("\n") { "${it.name.sanitizeForEncoding()}:${it.durationMs}" }

    private fun decodeZones(encoded: String): List<SnapshotZone> {
        if (encoded.isBlank()) return emptyList()
        return encoded.lineSequence().mapNotNull { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val ms = parts[1].toLongOrNull() ?: return@mapNotNull null
            SnapshotZone(name = parts[0], durationMs = ms)
        }.toList()
    }

    // --- Watch Registry ---

    /** Every known watch, most recently used first. */
    fun getAllWatches(): Flow<List<WatchEntity>> = watchDao.getAllWatchesFlow()

    suspend fun getWatch(watchId: String): WatchEntity? = watchDao.getWatch(watchId)

    /**
     * Names the watch itself. Affects no recordings — this identifies the hardware.
     */
    suspend fun renameWatch(watchId: String, deviceName: String) {
        watchDao.updateDeviceName(watchId, deviceName.trim())
        Log.d(tag, "Watch $watchId is now called '${deviceName.trim()}'")
    }

    /**
     * Sets who is wearing a watch. Only affects records that arrive from now on.
     *
     * Null hands the watch back to nobody, so its next recordings arrive unattributed rather than
     * carrying whoever had it last.
     */
    suspend fun setWatchPerson(watchId: String, personId: Long?) {
        watchDao.updatePerson(watchId, personId)
        // Keep the legacy name column in step so a downgrade, or any path still reading it, does
        // not report a wearer this watch no longer has.
        watchDao.updateWearer(watchId, personId?.let { personDao.getPerson(it)?.name }.orEmpty())
        Log.d(tag, "Watch $watchId is now worn by person $personId")
    }

    suspend fun countRecordsForWatch(watchId: String): Int = watchDao.countRecordsForWatch(watchId)

    /**
     * Registers a watch before it has ever sent a record.
     *
     * Because names are stamped at ingest, a watch handed out unnamed produces recordings labelled
     * with its model. Naming it in advance is how that is avoided.
     */
    suspend fun registerWatch(
        watchId: String,
        deviceName: String,
        personId: Long? = null,
        model: String = ""
    ) {
        val now = System.currentTimeMillis()
        watchDao.insertWatch(
            WatchEntity(
                watchId = watchId,
                deviceName = deviceName.trim(),
                lastKnownModel = model,
                firstSeen = now,
                lastSeen = now,
                currentPersonId = personId
            )
        )
        // insertWatch ignores conflicts so it cannot clobber existing values; apply them
        // explicitly for the case where the watch was already known.
        if (deviceName.isNotBlank()) watchDao.updateDeviceName(watchId, deviceName.trim())
        if (personId != null) setWatchPerson(watchId, personId)
    }

    /**
     * Re-attributes records from one watch within a date range to a person.
     *
     * The recovery path for recordings that arrived before anyone had been assigned to the watch.
     *
     * @return how many records were changed.
     */
    suspend fun reattributeRecords(watchId: String, personId: Long, fromDate: Long, toDate: Long): Int {
        val person = personDao.getPerson(personId) ?: return 0
        val changed = personDao.reattributeRecords(watchId, personId, person.name, fromDate, toDate)
        Log.d(tag, "Re-attributed $changed record(s) from watch $watchId to '${person.name}'")
        return changed
    }

    // --- People ---

    /** Everyone who wears a watch. */
    fun getAllPeople(): Flow<List<PersonEntity>> = personDao.getAllPeopleFlow()

    suspend fun getPerson(personId: Long): PersonEntity? = personDao.getPerson(personId)

    /**
     * Creates a profile, giving it a colour that differs from the ones already in use.
     *
     * @return the new person's id.
     */
    suspend fun addPerson(name: String, colorArgb: Int? = null): Long {
        val existing = personDao.getAllPeople()
        val color = colorArgb ?: PersonColors.defaultFor(existing.size)
        val id = personDao.insertPerson(
            PersonEntity(
                name = name.trim(),
                colorArgb = color,
                createdAt = System.currentTimeMillis()
            )
        )
        Log.d(tag, "Added person '${name.trim()}' as $id")
        return id
    }

    /**
     * Renames someone, everywhere.
     *
     * Records hold the person rather than a copy of their name, so this reaches every recording
     * they have ever made. That is the intent: a misspelling is worth correcting throughout. What
     * this cannot do — and must not — is change *who* a past recording belongs to.
     */
    suspend fun renamePerson(personId: Long, name: String) {
        personDao.updateName(personId, name.trim())
        Log.d(tag, "Person $personId is now called '${name.trim()}'")
    }

    suspend fun setPersonColor(personId: Long, colorArgb: Int) =
        personDao.updateColor(personId, colorArgb)

    suspend fun countRecordsForPerson(personId: Long): Int = personDao.countRecordsForPerson(personId)

    /**
     * Attributes a chosen set of recordings to someone, or to nobody.
     *
     * The correction path for a batch that arrived before its watch had anyone assigned — picking
     * them out of the library by hand is the only way to describe "these ones", since no filter
     * expresses it.
     *
     * @return how many recordings changed.
     */
    suspend fun assignPersonToRecords(recordIds: Collection<Long>, personId: Long?): Int {
        if (recordIds.isEmpty()) return 0
        val name = personId?.let { personDao.getPerson(it)?.name }.orEmpty()

        // Chunked because Room turns `IN (:recordIds)` into one bind variable per id, and SQLite
        // caps those at 999 on older Android. Selecting every recording in the library is a single
        // tap away, so this is reachable rather than theoretical.
        val changed = recordIds.toList()
            .chunked(SQL_VARIABLE_LIMIT)
            .sumOf { chunk -> personDao.assignPersonToRecords(chunk, personId, name) }

        Log.d(tag, "Attributed $changed record(s) to ${name.ifBlank { "nobody" }}")
        return changed
    }

    /**
     * Removes a profile without erasing the history attached to it.
     *
     * Each of their recordings keeps the name it was stamped with, so the library still says who
     * made it — it just stops being a profile you can filter by or recolour.
     */
    suspend fun deletePerson(personId: Long) {
        personDao.unlinkWatches(personId)
        personDao.unlinkRecords(personId)
        personDao.deletePerson(personId)
        Log.d(tag, "Deleted person $personId; their recordings keep the name they were stamped with")
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

    // --- Tags on events and groups ---
    //
    // A tag applied here reaches every recording underneath, resolved on read. Nothing is written
    // onto the recordings: see §2.5 of the product doc, and [EffectiveTagsResolver].

    suspend fun addTagToEvent(eventId: Long, tagId: Long) =
        tagDao.insertEventTagCrossRef(EventTagCrossRef(eventId, tagId))

    suspend fun removeTagFromEvent(eventId: Long, tagId: Long) = tagDao.untagEvent(eventId, tagId)

    fun getTagsForEvent(eventId: Long): Flow<List<TagEntity>> = tagDao.getTagsForEventFlow(eventId)

    suspend fun addTagToGroup(groupId: Long, tagId: Long) =
        tagDao.insertGroupTagCrossRef(EventGroupTagCrossRef(groupId, tagId))

    suspend fun removeTagFromGroup(groupId: Long, tagId: Long) = tagDao.untagGroup(groupId, tagId)

    fun getTagsForGroup(groupId: Long): Flow<List<TagEntity>> = tagDao.getTagsForGroupFlow(groupId)

    /** Every event's tags, indexed by event. Live, so applying one anywhere updates every reader. */
    val allEventTags: Flow<Map<Long, List<TagEntity>>> =
        tagDao.getAllEventTagsFlow().map { EffectiveTagsResolver.index(it) }

    /** Every group's tags, indexed by group. */
    val allGroupTags: Flow<Map<Long, List<TagEntity>>> =
        tagDao.getAllGroupTagsFlow().map { EffectiveTagsResolver.index(it) }

    /**
     * Effective tags for every recording in the library, keyed by record id.
     *
     * One query per level rather than one per recording. Resolving per row would issue three
     * queries for every visible tile while scrolling, to answer a question that is the same for
     * every recording in an event.
     */
    val effectiveTags: Flow<Map<Long, List<EffectiveTag>>> = combine(
        records,
        tagDao.getAllEventTagsFlow(),
        tagDao.getAllGroupTagsFlow(),
        eventDao.getAllEventsFlow(),
        eventGroupDao.getAllGroupsFlow()
    ) { library, eventTags, groupTags, events, groups ->
        EffectiveTagsResolver.resolveAll(
            records = library,
            eventTags = EffectiveTagsResolver.index(eventTags),
            groupTags = EffectiveTagsResolver.index(groupTags),
            groupIdByEvent = events.associate { it.eventId to it.groupId },
            // Collections nest, so inheritance climbs the whole chain: a tag on a festival reaches
            // the recordings inside its days without being applied to each of them.
            parentByGroup = groups.associate { it.groupId to it.parentGroupId }
        )
    }

    /**
     * Returns a flow of all tags currently assigned to a specific record.
     * 
     * @param recordId The ID of the record.
     */
    fun getTagsForRecord(recordId: Long): Flow<List<TagEntity>> = tagDao.getTagsForRecordFlow(recordId)

    /**
     * Stops the background collector started in [init].
     *
     * Never called in the app — this repository lives as long as the process does. It exists for
     * tests, which build one per test method against a database they then close: without it the
     * collector outlives its database and the next emission fails on a closed connection, taking
     * an unrelated test down with it.
     */
    fun close() {
        scope.cancel()
    }

    private companion object {
        /**
         * How many ids may go into one `IN (...)` clause.
         *
         * SQLite's bind-variable ceiling is 999 on older Android versions; this leaves room for the
         * statement's other parameters.
         */
        const val SQL_VARIABLE_LIMIT = 500
    }
}
