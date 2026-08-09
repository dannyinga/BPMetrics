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
                // Both of these are for installs that already had presets; a fresh install was
                // just seeded with the current list at the current framing and needs neither.
                offerNewBuiltInPresets()
                refreshSupersededPresetFraming()
                rescueLegacyExportDefaults()
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
        reconcileMembership()
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
        
        // A recording arriving may land inside an existing window, so where it lives is not
        // something ingest can decide — only the one resolver can.
        reconcileMembership()

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
        reconcileMembership()
        return id
    }

    /**
     * How many bulk operations are in progress. See [inBulk].
     *
     * Not thread-safe, and deliberately not: every mutation path here runs on the repository's own
     * IO dispatcher from a single caller at a time, and a lock around a counter would suggest a
     * concurrency guarantee the rest of this class does not make.
     */
    private var bulkDepth = 0

    /**
     * Runs [block] with membership reconciliation deferred until it finishes.
     *
     * Every mutation reconciles, which is what makes the stored column trustworthy — but a restore
     * creating fifty events and importing a thousand recordings would then run a thousand full
     * reconciles, each walking the whole library. The result is identical and the wait is minutes.
     *
     * Nested calls are counted rather than flagged, so a bulk operation calling another one still
     * reconciles exactly once, at the outermost exit.
     *
     * The reconcile runs in a `finally`: a restore that fails halfway has still moved rows, and
     * leaving the derived column stale would be a worse state than either finishing or not starting.
     */
    suspend fun <T> inBulk(block: suspend () -> T): T {
        bulkDepth++
        return try {
            block()
        } finally {
            bulkDepth--
            if (bulkDepth == 0) reconcileMembership()
        }
    }

    /**
     * Recomputes where every recording lives, and writes it. The only thing that writes it.
     *
     * `bpm_records.eventId` is a cache of what [EventMembership.resolve] says, kept because reads
     * are constant and the things that change the answer happen twice a week. What makes a cache
     * safe is not the cache, it is having exactly one writer — the previous arrangement made every
     * feature that changed something also responsible for correcting what depended on it, and four
     * separate "0 recordings" defects came from one of them not knowing it had to.
     *
     * So: callers read the column, and call this after one of the mutations below. Nothing else
     * assigns membership.
     *
     * | Invalidates | Does not |
     * |---|---|
     * | A recording arriving, deleted, split or merged | A title or note |
     * | A window created, moved or cleared | A tag, cover or type |
     * | An event created, deleted or reparented | Anything on a person or watch |
     * | The people qualifying a window | |
     *
     * Being able to write that table down is the point. A short closed list can be audited;
     * "wherever it matters" cannot.
     *
     * The **whole** library is recomputed rather than the part that changed. A few thousand
     * recordings against a few hundred events is milliseconds, and working out which rows *could*
     * have been affected would be a second, subtler definition of membership sitting next to the
     * first — the same class of mistake in a new place.
     *
     * @return how many recordings changed hands, which is normally zero and is worth logging when
     * it is not.
     */
    suspend fun reconcileMembership(): Int {
        if (bulkDepth > 0) return 0
        val events = eventDao.getAllEvents()
        val recordings = recordDao.getAllRecordEntities()
        val windowPeople = eventDao.getAllWindowPeople()
            .groupBy({ it.eventId }, { it.personId })
            .mapValues { it.value.toSet() }

        val resolved = EventMembership.resolve(events, windowPeople, recordings)

        // Grouped so this is one statement per destination rather than one per recording, then
        // chunked because Room turns `IN (:ids)` into one bind variable per id and SQLite caps
        // those at 999 — which a first reconcile over a whole unfiled library reaches immediately.
        val moved = recordings.filter { resolved[it.recordId] != it.eventId }
        moved.groupBy { resolved[it.recordId] }.forEach { (eventId, records) ->
            records.map { it.recordId }.chunked(SQL_VARIABLE_LIMIT).forEach { chunk ->
                eventDao.assignRecordsToEvent(chunk, eventId)
            }
        }

        if (moved.isNotEmpty()) {
            Log.i(tag, "Reconciled membership: ${moved.size} recordings moved")
        }
        return moved.size
    }

    suspend fun renameEvent(eventId: Long, name: String) = eventDao.rename(eventId, name.trim())

    suspend fun setEventNotes(eventId: Long, notes: String) = eventDao.updateNotes(eventId, notes)

    /**
     * Puts an event's place in the tree back as a backup recorded it.
     *
     * Deliberately unguarded, unlike an editor's move: a backup is a snapshot of a tree that was
     * already valid, and refusing part of it would restore a *different* library from the one that
     * was saved. [EventTree] tolerates a cycle rather than hanging on one, so a corrupt file makes
     * a repairable mess instead of an unopenable app.
     */
    suspend fun restoreEventTaxonomy(
        eventId: Long,
        parentId: Long?,
        windowStart: Long?,
        windowEnd: Long?,
        type: String?,
        excludedFromParentAnalysis: Boolean
    ) {
        eventDao.updateTaxonomy(
            eventId, parentId, windowStart, windowEnd, type, excludedFromParentAnalysis
        )
        // A window is the membership rule, so changing one changes where recordings live.
        reconcileMembership()
    }

    // --- Cover images ---

    /**
     * Where a cover can be put.
     *
     * One type rather than three near-identical methods, because everything around setting a cover
     * — importing the file, deleting the one it replaces, invalidating the cache — is the same
     * whichever of the three it lands on, and three copies of that is three chances to forget the
     * delete and leak a file.
     */
    sealed interface CoverOwner {
        data class Event(val eventId: Long) : CoverOwner
        data class Collection(val groupId: Long) : CoverOwner
        data class Recording(val recordId: Long) : CoverOwner
    }

    private suspend fun currentCoverPath(owner: CoverOwner): String? = when (owner) {
        is CoverOwner.Event -> eventDao.coverPathOf(owner.eventId)
        // A collection is an event, so both go to the same table. Two calls into two tables is
        // how a cover set on a collection came to be read back from an event and found missing.
        is CoverOwner.Collection -> eventDao.coverPathOf(owner.groupId)
        is CoverOwner.Recording -> eventDao.recordCoverPathOf(owner.recordId)
    }

    private suspend fun writeCover(owner: CoverOwner, cover: Cover?) {
        val p = cover?.path
        val l = cover?.cropLeft
        val t = cover?.cropTop
        val r = cover?.cropRight
        val b = cover?.cropBottom
        val blur = cover?.blur
        when (owner) {
            is CoverOwner.Event -> eventDao.updateCover(owner.eventId, p, l, t, r, b, blur)
            is CoverOwner.Collection -> eventDao.updateCover(owner.groupId, p, l, t, r, b, blur)
            is CoverOwner.Recording -> eventDao.updateRecordCover(owner.recordId, p, l, t, r, b, blur)
        }
    }

    /**
     * Copies [source] into app storage and hangs it on [owner].
     *
     * The file the cover replaces is deleted here rather than left behind. Nothing else knows a
     * cover has been superseded, so a caller that forgot would leak a file per change with no way
     * to find it again — the row that named it is already gone.
     *
     * @return true if the image could be read and stored.
     */
    suspend fun setCover(
        context: android.content.Context,
        owner: CoverOwner,
        source: android.net.Uri,
        nameHint: String
    ): Boolean {
        val id = when (owner) {
            is CoverOwner.Event -> owner.eventId
            is CoverOwner.Collection -> owner.groupId
            is CoverOwner.Recording -> owner.recordId
        }
        val previous = currentCoverPath(owner)
        val stored = CoverStore.importFrom(context, source, nameHint, id) ?: return false

        writeCover(owner, Cover(stored))

        // Only after the new one is safely written. Deleting first would leave the owner pointing
        // at nothing if the import failed halfway.
        if (previous != stored) {
            CoverStore.delete(context, previous)
            inga.bpmetrics.ui.components.invalidateCover(previous)
        }
        Log.i(tag, "Cover set on $owner")
        return true
    }

    /**
     * Gives a person a photograph, replacing whatever they had.
     *
     * The same import machinery as a cover — copied, downscaled, the old file deleted — differing
     * only in which directory it lands in. See [CoverStore.Kind].
     */
    suspend fun setPersonPhoto(
        context: android.content.Context,
        personId: Long,
        source: android.net.Uri,
        nameHint: String
    ): Boolean {
        val previous = personDao.getPerson(personId)?.photoPath
        val stored = CoverStore.importFrom(
            context, source, nameHint, personId, CoverStore.Kind.PERSON
        ) ?: return false

        // A new picture starts unframed. Keeping the old crop would show whichever part of the
        // previous photograph the fractions happened to describe, which on a differently shaped
        // image is somewhere arbitrary.
        personDao.updatePhoto(personId, stored, null, null, null, null)
        if (previous != stored) {
            CoverStore.delete(context, previous)
            inga.bpmetrics.ui.components.invalidateCover(previous)
        }
        return true
    }

    /**
     * Restores a cover from the bytes a backup carried, keeping the framing it was saved with.
     *
     * Distinct from [setCover], which starts from a gallery `Uri` and deliberately resets the crop
     * because a new picture's fractions describe somewhere arbitrary. Here the picture and the crop
     * belong together — they are the same cover, coming home.
     *
     * The crop is written even when [bytes] is null or unwritable, so a backup whose image could not
     * be read still restores the framing. Replacing the missing picture then lands it as it was set,
     * rather than resetting to the whole frame.
     */
    suspend fun restoreCover(
        context: android.content.Context,
        owner: CoverOwner,
        bytes: ByteArray?,
        crop: Cover,
        nameHint: String
    ) {
        val id = when (owner) {
            is CoverOwner.Event -> owner.eventId
            is CoverOwner.Collection -> owner.groupId
            is CoverOwner.Recording -> owner.recordId
        }
        val stored = bytes?.let { CoverStore.writeBytes(context, it, nameHint, id) }
        writeCover(owner, crop.copy(path = stored ?: ""))
    }

    /** The same, for a person's photograph. See [restoreCover]. */
    suspend fun restorePersonPhoto(
        context: android.content.Context,
        personId: Long,
        bytes: ByteArray?,
        crop: Cover,
        nameHint: String
    ) {
        val stored = bytes?.let {
            CoverStore.writeBytes(context, it, nameHint, personId, CoverStore.Kind.PERSON)
        }
        personDao.updatePhoto(
            personId, stored, crop.cropLeft, crop.cropTop, crop.cropRight, crop.cropBottom
        )
    }

    /**
     * The tag with this name on this axis, creating either if it is not there.
     *
     * A restore needs this because a backup names tags rather than numbering them — ids are
     * reassigned on insert, so "Character:Hulk" has to be looked up or made afresh. Restoring into a
     * library that already has the tag reuses it, which is what merging two libraries should do.
     */
    suspend fun findOrCreateTag(categoryName: String, tagName: String): Long? {
        if (tagName.isBlank()) return null
        val categoryId = tagDao.getAllCategories()
            .firstOrNull { it.name.equals(categoryName, ignoreCase = true) }
            ?.categoryId
            ?: tagDao.insertCategory(CategoryEntity(name = categoryName))

        return tagDao.getAllTags()
            .firstOrNull { it.parentCategoryId == categoryId && it.name.equals(tagName, true) }
            ?.tagId
            ?: tagDao.insertTag(TagEntity(name = tagName, parentCategoryId = categoryId))
    }

    /** Re-frames the photograph a person already has, leaving the file alone. */
    suspend fun setPersonPhotoCrop(personId: Long, photo: Cover) {
        personDao.updatePhoto(
            personId,
            photo.path,
            photo.cropLeft,
            photo.cropTop,
            photo.cropRight,
            photo.cropBottom
        )
        inga.bpmetrics.ui.components.invalidateCover(photo.path)
    }

    /** Takes a person's photograph off, back to their colour and initial. */
    suspend fun clearPersonPhoto(context: android.content.Context, personId: Long) {
        val previous = personDao.getPerson(personId)?.photoPath
        personDao.updatePhoto(personId, null, null, null, null, null)
        CoverStore.delete(context, previous)
        inga.bpmetrics.ui.components.invalidateCover(previous)
    }

    /** Re-frames the cover already on [owner], leaving the file alone. */
    suspend fun setCoverCrop(owner: CoverOwner, cover: Cover) {
        writeCover(owner, cover)
        inga.bpmetrics.ui.components.invalidateCover(cover.path)
    }

    /**
     * Takes the cover off [owner] and deletes its file.
     *
     * On an event or a collection this restores inheritance rather than meaning "no picture" — the
     * one above it applies again, which is the point of storing null rather than an empty string.
     */
    suspend fun clearCover(context: android.content.Context, owner: CoverOwner) {
        val previous = currentCoverPath(owner)
        writeCover(owner, null)
        CoverStore.delete(context, previous)
        inga.bpmetrics.ui.components.invalidateCover(previous)
    }

    /**
     * The one event a set of recordings share, or null if they do not share one.
     *
     * Used by library multi-select, which puts a cover on the event rather than on each recording —
     * so it has to be able to say *which* event, and to refuse rather than guess when the selection
     * spans several. Unfiled recordings count as not sharing: null is not an event.
     */
    suspend fun sharedEventOf(recordIds: List<Long>): Long? {
        if (recordIds.isEmpty()) return null
        // A single unfiled recording means they do not all share an event, however many of the
        // others agree. Asked separately because Room cannot return the null that says so.
        if (eventDao.unfiledCountAmong(recordIds) > 0) return null
        return eventDao.distinctEventIdsFor(recordIds).singleOrNull()
    }

    /** Removes every stored cover and the rows pointing at them. */
    suspend fun clearAllCovers(context: android.content.Context): Int {
        // The whole tree, so collections are included — they are events. Reading the filtered
        // list here would leave every collection cover behind while reporting them all cleared.
        eventDao.getAllEvents().forEach {
            if (it.coverPath != null) {
                eventDao.updateCover(it.eventId, null, null, null, null, null, null)
            }
        }
        val removed = CoverStore.clearAll(context)
        inga.bpmetrics.ui.components.invalidateAllCovers()
        Log.i(tag, "Cleared $removed cover image(s)")
        return removed
    }

    /** Files an event under a collection, refusing a move that would make a cycle. */
    suspend fun setEventGroup(eventId: Long, groupId: Long?) = setEventParent(eventId, groupId)

    /**
     * Removes an event and releases its recordings.
     *
     * Deleting the container must never delete the contents — those recordings are the only copy of
     * something that happened, and the event is just a label someone put on them.
     */
    suspend fun deleteEvent(eventId: Long) {
        eventDao.unfileRecordsForEvent(eventId)
        eventDao.deleteEvent(eventId)
        reconcileMembership()
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
        // Hand filing is an input to membership, not the answer: a window covering these
        // recordings still wins. Reconciling here means the library shows the real answer at once
        // rather than the requested one until something else happens to recompute.
        reconcileMembership()
        Log.d(tag, "Filed $changed recording(s) under event ${eventId ?: "nothing"}")
        return changed
    }

    /** Of these recordings, the ones not yet in an event. Chunked for the same reason as above. */
    suspend fun recordIdsWithoutEvent(recordIds: Collection<Long>): List<Long> =
        recordIds.toList()
            .chunked(SQL_VARIABLE_LIMIT)
            .flatMap { chunk -> eventDao.recordIdsWithoutEvent(chunk) }

    // --- Collections ---
    //
    // A collection is an event with [COLLECTION_TYPE]. It was its own table until migration 23→24;
    // everything below now reads and writes the one tree, so a count, a span or a roll-up has one
    // implementation instead of two that have to be kept agreeing. `event_groups` still exists and
    // is no longer read — see MIGRATION_23_24 for why it is still there.
    //
    // The names are unchanged so the screens did not all have to move at once. They read a little
    // oddly against `EventEntity`, and go when the library screen is redesigned in Sprint 3.

    /** Every collection, newest first. */
    fun getAllEventGroups(): Flow<List<EventEntity>> =
        eventDao.getCollectionsFlow()

    /**
     * The whole tree, collections included.
     *
     * What anything walking ancestry or descendants needs. [getAllEvents] hides collections so the
     * screens do not list them twice; a walk that hid them would break the chain in the middle.
     */
    val allEventsInTree: Flow<List<EventEntity>> = eventDao.getAllEventsFlowUnfiltered()

    suspend fun getEventGroup(groupId: Long): EventEntity? =
        eventDao.getEvent(groupId)?.takeIf { it.isCollection }

    /**
     * Every recording anywhere beneath a collection.
     *
     * Through [EventTree.descendantsOf], so a recording three levels down counts. The query this
     * replaced looked one level deep, which is the "0 recordings" defect that started all of this.
     */
    fun getRecordsForGroup(groupId: Long): Flow<List<BpmRecordEntity>> =
        combine(eventDao.getAllEventsFlowUnfiltered(), recordDao.getAllRecordEntitiesFlow()) { events, records ->
            val within = EventTree.descendantsOf(events, groupId)
            records.filter { it.eventId in within }.sortedBy { it.startTime }
        }

    /** When a collection starts and ends: its window, or the span of everything beneath it. */
    suspend fun getGroupSpan(groupId: Long): TimeSpan? {
        val events = eventDao.getAllEvents()
        val records = recordDao.getAllRecordEntities()
        val membership = records.associate { it.recordId to it.eventId }
        return EventTree.spanOf(events, groupId, records, membership)
            ?.let { TimeSpan(it.startMs, it.endMs) }
    }

    /** How many events sit beneath a collection, at any depth. Excludes the collection itself. */
    suspend fun countEventsForGroup(groupId: Long): Int =
        EventTree.descendantsOf(eventDao.getAllEvents(), groupId).size - 1

    /** How many recordings sit beneath a collection, at any depth. */
    suspend fun countRecordsForGroup(groupId: Long): Int {
        val within = EventTree.descendantsOf(eventDao.getAllEvents(), groupId)
        return recordDao.getAllRecordEntities().count { it.eventId in within }
    }

    suspend fun createEventGroup(name: String): Long {
        val id = eventDao.insertEvent(
            EventEntity(
                name = name.trim(),
                type = COLLECTION_TYPE,
                createdAt = System.currentTimeMillis()
            )
        )
        Log.d(tag, "Created collection '${name.trim()}' as $id")
        reconcileMembership()
        return id
    }

    suspend fun renameEventGroup(groupId: Long, name: String) =
        eventDao.rename(groupId, name.trim())

    suspend fun setEventGroupNotes(groupId: Long, notes: String) =
        eventDao.updateNotes(groupId, notes)

    /**
     * Joins several recordings of one person into a single one.
     *
     * The merged recording is written *before* anything is deleted. The other order means a
     * failure halfway leaves someone with neither the parts nor the whole.
     *
     * Tags from every part are carried across, so nothing a recording was marked with is lost by
     * joining it to another. Event membership comes from the earliest part, which is the one whose
     * start the merged recording takes.
     *
     * @return the new record's id, or null when the recordings could not honestly be joined.
     */
    suspend fun mergeRecords(records: List<BpmRecord>, deleteOriginals: Boolean): Long? {
        if (!RecordMerge.canMerge(records)) {
            Log.w(tag, "Refused to merge ${records.size} recording(s)")
            return null
        }
        val merged = RecordMerge.combine(records) ?: return null
        val ordered = records.sortedBy { it.metadata.startTime }
        val first = ordered.first()

        val newId = saveWatchRecordToLibrary(
            inga.bpmetrics.core.BpmWatchRecord(
                date = java.sql.Date(merged.startTime),
                dataPoints = merged.points.map {
                    inga.bpmetrics.core.BpmDataPoint(it.timestampMs, it.bpm)
                },
                startTime = merged.startTime,
                endTime = merged.endTime
            ),
            first.metadata.title.ifBlank { "Merged recording" }
        )

        records.flatMap { it.tags }.distinctBy { it.tagId }.forEach { tag ->
            addTagToRecord(newId, tag.tagId)
        }
        updateRecordDeviceAndWearer(newId, first.metadata.deviceId, first.metadata.personId)
        first.metadata.eventId?.let { assignRecordsToEvent(listOf(newId), it) }

        if (deleteOriginals) {
            records.forEach { deleteRecordWithId(it.metadata.recordId) }
        }
        Log.i(tag, "Merged ${records.size} recordings into $newId")
        return newId
    }

    /** Removes a collection and releases what it held. Its events and children survive. */
    suspend fun deleteEventGroup(groupId: Long) = deleteEvent(groupId)

    /**
     * Files one collection inside another.
     *
     * @return false when the move would make a collection its own ancestor, or nest deeper than
     *   a cycle. Refused here rather than in the UI: a cycle makes
     *   every walk of the tree non-terminating, and no screen should be the only thing standing
     *   between the database and an infinite loop.
     */
    suspend fun setEventGroupParent(groupId: Long, parentGroupId: Long?): Boolean =
        setEventParent(groupId, parentGroupId)

    /**
     * Files one event inside another.
     *
     * @return false when the move would make an event its own ancestor. Refused here rather than in
     *   the UI: a cycle makes every walk of the tree non-terminating, and no screen should be the
     *   only thing standing between the database and an infinite loop.
     */
    suspend fun setEventParent(eventId: Long, parentId: Long?): Boolean {
        val all = eventDao.getAllEvents()
        if (parentId != null && EventTree.wouldCycle(all, eventId, parentId)) {
            Log.w(tag, "Refused to file event $eventId under $parentId")
            return false
        }
        val current = all.firstOrNull { it.eventId == eventId } ?: return false
        eventDao.updateTaxonomy(
            eventId = eventId,
            parentId = parentId,
            windowStart = current.windowStart,
            windowEnd = current.windowEnd,
            type = current.type,
            excluded = current.excludedFromParentAnalysis
        )
        reconcileMembership()
        return true
    }

    /**
     * A collection and everything nested inside it, itself included.
     *
     * What "analyse Coachella" resolves to: the festival, its days, and every event in any of them.
     */
    suspend fun descendantGroupIds(groupId: Long): Set<Long> =
        EventTree.descendantsOf(eventDao.getAllEvents(), groupId)

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

    /**
     * The tags applied to each event, keyed by event id.
     *
     * A backup carried record tags from the beginning and never carried these, so a restore returned
     * every recording and lost the labelling of the containers holding them.
     */
    suspend fun getEventTagsForBackup(): Map<Long, List<TagEntity>> =
        tagDao.getAllEventTagsFlow().first()
            .groupBy({ it.ownerId }, { it.tag })

    /** The tags applied to each collection, keyed by collection id. See [getEventTagsForBackup]. */
    suspend fun getGroupTagsForBackup(): Map<Long, List<TagEntity>> = getEventTagsForBackup()

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

    /** The sort the library should open on, or null to keep the built-in order. */
    suspend fun getDefaultSort(): String? = settingsRepository.defaultSort.first()

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
        settingsRepository.setBuiltInPresetRevision(
            inga.bpmetrics.export.ExportPreset.BUILT_IN_REVISION
        )
    }

    /**
     * Gives an existing install the built-in presets added since it was seeded.
     *
     * Seeding runs only against an empty table, so every install that already has presets would
     * otherwise never see a new one — the shipped list would be a fresh-install-only feature.
     *
     * Offered once each, tracked by revision rather than by checking which names are missing. A
     * name check cannot tell "never had this one" from "deleted it on purpose", so it would put a
     * deleted preset back on every launch and leave no way to be rid of it.
     */
    suspend fun offerNewBuiltInPresets() {
        val shipped = inga.bpmetrics.export.ExportPreset.BUILT_IN_REVISION
        val seen = settingsRepository.builtInPresetRevision()
        if (seen >= shipped) return

        // Nothing at all yet: that is the seeder's job, and it sets the revision itself.
        if (presetDao.count() == 0) return

        val existing = presetDao.getAll().map { it.name }.toSet()
        var added = 0
        inga.bpmetrics.export.ExportPreset.BUILT_IN
            .filter { it.name !in existing }
            .forEach { preset ->
                presetDao.insert(
                    ExportPresetEntity(
                        name = preset.name,
                        configJson = preset.toJson(),
                        // Never the default. Someone already has one chosen, and quietly moving
                        // what a new export starts from is not a thing an upgrade should do.
                        isDefault = false,
                        isBuiltIn = true,
                        createdAt = System.currentTimeMillis()
                    )
                )
                added++
            }

        settingsRepository.setBuiltInPresetRevision(shipped)
        if (added > 0) Log.i(tag, "Added $added new built-in preset(s)")
    }

    /**
     * Saves the old settings-screen export defaults as a preset, once.
     *
     * Presets replaced those defaults and the settings screen no longer shows them — but someone
     * who had spent time getting them right should not simply find them gone. They become a preset
     * named so its origin is obvious, and only then are the old keys left behind.
     *
     * Does nothing when the defaults were never touched: a preset identical to the shipped one is
     * clutter, not rescue.
     */
    private suspend fun rescueLegacyExportDefaults() {
        val preset = settingsRepository.legacyExportDefaultsAsPreset() ?: return
        presetDao.insert(
            ExportPresetEntity(
                name = preset.name,
                configJson = preset.toJson(),
                createdAt = System.currentTimeMillis()
            )
        )
        settingsRepository.markExportDefaultsMigrated()
        Log.i(tag, "Kept the old export defaults as a preset")
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
     * Sets someone's own resting and maximum rate, or clears them back to the app-wide figures.
     *
     * Ordered here rather than trusted from the caller: a resting rate above a maximum would make
     * every zone percentage negative, and the two fields are far enough apart on screen to get
     * them the wrong way round.
     */
    suspend fun setPersonZones(personId: Long, restingBpm: Int?, maxBpm: Int?) {
        val low = restingBpm?.coerceIn(30, 120)
        val high = maxBpm?.coerceIn(120, 230)
        val ordered = if (low != null && high != null && low >= high) high - 1 else low
        personDao.updateZones(personId, ordered, high)
    }

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

    /**
     * Puts a profile's creation time back to what a backup recorded, so the people list keeps the
     * order it had. Only a restore has any business calling this.
     */
    suspend fun setPersonCreatedAt(personId: Long, createdAt: Long) =
        personDao.updateCreatedAt(personId, createdAt)

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

    // Collections are events, so their tags live in `event_tag_cross_ref` alongside everything
    // else — migration 23→24 copied the existing ones across. These three used to write and read
    // `event_group_tag_cross_ref`, which would have put new tags somewhere inheritance no longer
    // looks: applied, visible on the collection, and silently absent from every recording under it.

    suspend fun addTagToGroup(groupId: Long, tagId: Long) = addTagToEvent(groupId, tagId)

    suspend fun removeTagFromGroup(groupId: Long, tagId: Long) = removeTagFromEvent(groupId, tagId)

    fun getTagsForGroup(groupId: Long): Flow<List<TagEntity>> = getTagsForEvent(groupId)

    /** Every event's tags, indexed by event. Live, so applying one anywhere updates every reader. */
    val allEventTags: Flow<Map<Long, List<TagEntity>>> =
        tagDao.getAllEventTagsFlow().map { EffectiveTagsResolver.index(it) }

    /** Collections are events, so their tags come from the same index. */
    val allGroupTags: Flow<Map<Long, List<TagEntity>>> get() = allEventTags

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
        eventDao.getAllEventsFlowUnfiltered()
    ) { library, eventTags, events ->
        // One chain, walked once. This used to take an event-to-collection map and a
        // collection-to-parent map and stitch them together inside the resolver; a tag on a
        // festival reaching a recording two levels down depended on those two agreeing. Since the
        // fold there is only `parentId`, so inheritance climbs the same tree membership does.
        EffectiveTagsResolver.resolveAll(
            records = library,
            eventTags = EffectiveTagsResolver.index(eventTags),
            events = events
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
