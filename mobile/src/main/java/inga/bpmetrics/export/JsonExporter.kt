package inga.bpmetrics.export

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordWithPoints
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.Cover
import inga.bpmetrics.library.TagEntity
import inga.bpmetrics.library.WatchEntity
import inga.bpmetrics.ui.settings.PreferenceSnapshot
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.sql.Date

/**
 * A whole library, in one file.
 *
 * The previous format was a bare array of records carrying a title, a device string and a wearer
 * *name*. That is enough to move a recording between phones and not enough to restore a library:
 * it drops every person profile and their colour, every watch in the registry, and the links from
 * each recording to both. Restoring from it would return the recordings and lose who made them.
 *
 * So people and watches are carried alongside the records, and each record refers to them by a
 * stable key rather than by a database id — ids are reassigned on insert, names and watch UUIDs
 * are not.
 *
 * ## Every field of every DTO below has a default. This is load-bearing.
 *
 * Gson does not run constructors. Given a class with a required parameter it allocates the object
 * through `Unsafe` and assigns whatever fields the JSON mentions, so a Kotlin default never runs and
 * a key the file omits is left as **null** — including on a property declared non-null, which the
 * compiler then lets every call site dereference. `EventDto.tags: List<String> = emptyList()` came
 * back null from a format 3 file for exactly this reason, and the restore fell over on the first
 * `forEach`. Restoring an older backup is the one moment this format exists for.
 *
 * When *all* parameters have defaults, Kotlin emits a synthetic no-arg constructor, Gson finds and
 * calls it, and the defaults apply as written. So the rule is: no required parameters, ever, in
 * anything that gets deserialised here. A new field arriving without one reintroduces the bug for
 * every field of its class, not just its own.
 *
 * @property formatVersion Incremented when the shape changes, so an older build can refuse a file
 * it would otherwise half-read.
 */
data class LibraryBackup(
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAt: Long = 0L,
    val people: List<PersonDto> = emptyList(),
    val watches: List<WatchDto> = emptyList(),
    val records: List<BpmRecordJsonDto> = emptyList(),
    val savedAnalyses: List<SavedAnalysisDto> = emptyList(),
    val settings: List<PreferenceSnapshot> = emptyList(),
    val eventGroups: List<EventGroupDto> = emptyList(),
    val locations: List<LocationDto> = emptyList(),
    val events: List<EventDto> = emptyList(),
    /** Collections, views and frozen analyses — one kind of thing since format 5. */
    val collections: List<CollectionDto> = emptyList(),
    /** How exports look. Carried since format 6 — see the note on [FORMAT_VERSION]. */
    val presets: List<ExportPresetDto> = emptyList()
) {
    companion object {
        /**
         * 1: records, data points, tags, people, watches.
         * 2: saved analyses and app settings, and a record's original id so analyses can be
         *    re-pointed at the right recordings after import reassigns them.
         * 3: events and event groups.
         *
         * Added here the moment events existed, rather than once they had a screen. A backup that
         * silently stops being complete is the failure this format keeps having to be rescued from.
         *
         * 4: everything three had stopped covering. Tags on events and on collections, collection
         *    nesting, cover images and their crops, people's photographs and their own heart rate
         *    figures, and an event's type, window and parent.
         *
         *    Three sprints of schema went by without this being touched, so a backup taken then
         *    restored a library with every recording intact and every piece of *organisation* gone
         *    — which is worse than an obvious failure, because it looks like it worked. The round
         *    trip is now asserted by a test for exactly that reason.
         *
         * 5: collections — which had never been carried at all. Not the sets, not their membership,
         *    and not the saved views or saved analyses that turned out to be the same object. The
         *    coverage test that exists to catch a gap like this was pointed at `EventGroupEntity`,
         *    the table the 23→24 fold left dead, so it passed while asserting nothing about the one
         *    in use. `savedAnalyses` stays *readable* so a format 4 file still restores its frozen
         *    numbers, but nothing writes it any more: a frozen analysis is a collection now.
         *
         * 6: events carry their **id**, and everything that referenced an event by name now
         *    references it by id as well — records, collections, and an event's own parent.
         *
         *    Name was never an identity. Two events called "Day 2", one inside Bass Canyon and one
         *    inside Griztronics, merged into a single event on restore and both evenings' recordings
         *    landed in it. The old comment called that "a fair reading of what the user meant by
         *    naming them the same thing", which reads plausibly right up until you have a festival
         *    with days in it — a name is only unique among its siblings, and nesting exists because
         *    of that. Names are still written, for older readers and for reading the file by eye.
         *
         *    Export presets arrive here too. They were left out on the grounds that they describe
         *    how an export looks rather than what the library holds, and are shareable as their own
         *    files. Both true, and neither is a reason for "back up everything" to quietly mean
         *    "everything except the half-dozen looks you built up". The version guard that made a
         *    preset from a newer build refuse to load is applied per preset, so one unreadable
         *    preset is skipped and reported rather than failing the restore.
         *
         *    And the venue registry is finally *written*. `locations` had been in the format since
         *    format 4 and the exporter never passed it, so every backup carried events naming
         *    venues that were not in the file — and a restore silently dropped every venue and
         *    every clock that depended on one. This is the fourth entry in this list of the same
         *    shape, which is why the coverage test now asserts the writer's arguments rather than
         *    only the format's fields.
         */
        const val FORMAT_VERSION = 6
    }
}

/**
 * An event, keyed by name rather than id.
 *
 * Ids are reassigned on import, so a record says which event it belonged to by *name* and the
 * restore rebuilds the links. Two events sharing a name will merge on restore, which is a fair
 * reading of what the user meant by naming them the same thing.
 */
/**
 * A cover image and how it is framed, carried inline.
 *
 * The bytes rather than the stored file name. A cover lives in the app's own private storage under
 * a name meaningful only to that install, so exporting the name produces a backup that restores
 * every crop and every blur setting and no pictures at all — which looks like it worked.
 *
 * Covers are downscaled to 512px on the long edge before they are stored, so a library with a dozen
 * of them adds a few hundred kilobytes to the file. That is the right trade against a backup that
 * is quietly incomplete.
 */
data class CoverDto(
    val imageBase64: String? = null,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val blur: Float = 0f,
    /** How far it is darkened. See [inga.bpmetrics.library.Cover.dim]. */
    val dim: Float = 0f
)

data class EventDto(
    /**
     * Its id in the library this backup came from.
     *
     * The event's *identity*, as distinct from its name. Restores before format 6 keyed events by
     * name, so two events called "Day 2" — one inside Bass Canyon, one inside Griztronics — merged
     * into a single event and every recording from both evenings landed in it. That is not a fair
     * reading of naming two things the same: a name is only ever unique among its siblings, which
     * is most of the point of nesting them.
     *
     * Zero in files written before format 6, where the name is all there is to go on.
     */
    val eventId: Long = 0L,
    val name: String = "",
    val groupName: String? = null,
    val notes: String = "",
    val createdAt: Long = 0L,
    /** Tags applied to the event itself, in "Category:Tag" form. Absent entirely before format 4. */
    val tags: List<String> = emptyList(),
    val type: String? = null,
    /**
     * The event above this one, by name.
     *
     * Kept so a pre-format-6 file still restores, and because it makes the JSON readable by eye.
     * [parentEventId] is what a restore follows now — a parent named "Day 2" is exactly as
     * ambiguous as a child named "Day 2".
     */
    val parentName: String? = null,
    /** The event above this one, by id. Authoritative since format 6. */
    val parentEventId: Long? = null,
    val windowStart: Long? = null,
    val windowEnd: Long? = null,
    val excludedFromParentAnalysis: Boolean = false,
    val cover: CoverDto? = null,
    /** Where it happened, by name — locations are renumbered on import, as everything else is. */
    val locationName: String? = null
)

data class EventGroupDto(
    val name: String = "",
    val notes: String = "",
    val createdAt: Long = 0L,
    /** Tags applied to the collection itself. Absent entirely before format 4. */
    val tags: List<String> = emptyList(),
    /**
     * The collection above this one, by name.
     *
     * Without it a three-level festival came back as three unrelated collections — the nesting was
     * simply dropped, silently, and rebuilding it by hand is exactly the work a backup exists to
     * avoid.
     */
    val parentName: String? = null,
    val cover: CoverDto? = null
)

/**
 * A saved analysis and the snapshot rows it froze.
 *
 * Its rows reference recordings by their id at the time, which import reassigns — so
 * [SavedAnalysisRecordDto.recordId] is remapped during restore. The rest of each row is a snapshot
 * and travels as-is: that is the whole point of a frozen analysis.
 */
/**
 * A selection, as a backup carries it.
 *
 * Everything a collection is after the fold: hand-picked members, an optional rule, an optional
 * frozen answer. Format 5 writes this; formats 1-4 had no collections at all, which is why a backup
 * taken then restored a library with every recording intact and every set gone.
 */
data class CollectionDto(
    val name: String = "",
    val notes: String = "",
    val createdAt: Long = 0L,
    /** The rule, if it is a smart collection. Serialised `FilterState`. */
    val filterJson: String? = null,
    val excludedRecordJson: String = "",
    val isPinned: Boolean = false,
    /** Set when its numbers are frozen — what a saved analysis was before the fold. */
    val frozenAt: Long? = null,
    val cover: CoverDto? = null,
    /** Events it names, by name. Superseded by [eventIds] since format 6. */
    val eventNames: List<String> = emptyList(),
    /** Events it names, by their id in the source library. Authoritative since format 6. */
    val eventIds: List<Long> = emptyList(),
    /** Recordings it names, by their id in the library this backup came from. */
    val recordIds: List<Long> = emptyList(),
    /** The frozen rows, when [frozenAt] is set. */
    val frozenRecords: List<SavedAnalysisRecordDto> = emptyList()
)

/**
 * An export preset: a saved look, by name.
 *
 * The configuration travels as the JSON the app already stores, so this carries whatever a preset
 * meant on the device it came from without this format having to know the shape of a look. A preset
 * written by a newer build may not parse here — that is checked per preset on restore, so it is
 * skipped and counted rather than taking the whole library down with it.
 */
data class ExportPresetDto(
    val name: String = "",
    val configJson: String = "",
    val isDefault: Boolean = false,
    /** Shipped with the app. Restored as an ordinary preset if the target build lacks it. */
    val isBuiltIn: Boolean = false,
    val createdAt: Long = 0L
)

data class SavedAnalysisDto(
    val name: String = "",
    val createdAt: Long = 0L,
    val filterDescription: String = "",
    val kind: String = "GROUP",
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
    val records: List<SavedAnalysisRecordDto> = emptyList()
)

data class SavedAnalysisRecordDto(
    /** The recording's id in the library this backup came from. Remapped on restore. */
    val recordId: Long = 0L,
    val title: String = "",
    val date: Long = 0L,
    val minBpm: Double? = null,
    val avgBpm: Double? = null,
    val maxBpm: Double? = null,
    val activeDurationMs: Long = 0L,
    val tagsEncoded: String = "",
    val wearerName: String = "",
    val watchName: String = ""
)

data class PersonDto(
    val name: String = "",
    val colorArgb: Int = 0,
    /** When the profile was made, so a restored library sorts people as it did. */
    val createdAt: Long = 0L,
    /** Their own resting and maximum, or null to inherit the app-wide figures. */
    val restingBpm: Int? = null,
    val maxBpm: Int? = null,
    /**
     * Their photograph, inline as base64.
     *
     * The bytes rather than the file name, because a backup is one file that has to restore on a
     * different device — a name pointing into another install's private storage restores to
     * nothing. Photographs are capped at 512px, so this costs tens of kilobytes each.
     */
    val photoBase64: String? = null,
    val photoCrop: CoverDto? = null
)

/**
 * A venue, keyed by name rather than id.
 *
 * Ids are reassigned on import, so events refer to a location by what it is called and the restore
 * rebuilds the links. Two locations sharing a name merge, which is a fair reading of naming them
 * the same thing.
 */
data class LocationDto(
    val name: String = "",
    val timeZoneId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val photoBase64: String? = null
)

data class WatchDto(
    val watchId: String = "",
    val deviceName: String = "",
    val lastKnownModel: String = ""
)

/**
 * Serialized representation of a complete BPM record for JSON export and import.
 */
data class BpmRecordJsonDto(
    /**
     * The recording's id in the library this came from.
     *
     * Import assigns new ids, so this is only meaningful as the left-hand side of a mapping — it is
     * what lets a saved analysis find the recordings it referred to after they have been renumbered.
     */
    val recordId: Long = 0L,
    val title: String = "",
    val description: String = "",
    val deviceId: String = "Watch",
    /** Who wore it, by name — matched to a person on restore, created if missing. */
    val wearerName: String? = null,
    /** Which watch, by its stable identifier rather than its current name. */
    val watchId: String? = null,
    /** Which event this was part of, by name. Superseded by [eventId] since format 6. */
    val eventName: String? = null,
    /**
     * Which event this was part of, by its id in the source library.
     *
     * A name cannot say *which* "Day 2" a recording belonged to. This can.
     */
    val eventId: Long? = null,
    val date: Long = 0L,
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val tags: List<String> = emptyList(), // "Category:Tag" format
    /** A cover set on this recording directly, rather than inherited from an event. */
    val cover: CoverDto? = null,
    /**
     * A location chosen for this recording specifically.
     *
     * The *override*, not the resolved answer — that is derived from the event tree on restore, so
     * carrying it would preserve a stale value where the tree has since been rearranged.
     */
    val locationName: String? = null,
    val dataPoints: List<BpmDataPointDto> = emptyList()
)

data class BpmDataPointDto(
    val timestamp: Long = 0L,
    val bpm: Double = 0.0
)

/**
 * What came of opening a file as a backup.
 *
 * A reason rather than a null, because "that file could not be imported" is what a friend was told
 * about a perfectly good backup, and it gave nobody anything to go on.
 */
sealed interface BackupFile {
    data class Read(val backup: LibraryBackup) : BackupFile
    data class Unreadable(val reason: String) : BackupFile
}

/**
 * Handles JSON batch export and import for single or multiple BPM records.
 */
object JsonExporter {

    /**
     * Compact, not pretty-printed.
     *
     * Readings are nearly all of a backup, and indenting each one onto four lines of its own more
     * than doubled the file — a library's worth of readings came out large enough that sending it
     * by text was a gamble and reading it back could exhaust a phone's memory.
     */
    private val gson: Gson = GsonBuilder().create()

    /** See [buildBackup]. Kept for tests and small payloads; files are written by [writeBackup]. */
    fun toBackupJson(
        records: List<BpmRecordWithPoints>,
        people: List<PersonEntity> = emptyList(),
        watches: List<WatchEntity> = emptyList(),
        categories: List<CategoryEntity> = emptyList(),
        settings: List<PreferenceSnapshot> = emptyList(),
        events: List<EventEntity> = emptyList(),
        collections: List<inga.bpmetrics.library.CollectionBackup> = emptyList(),
        eventTags: Map<Long, List<TagEntity>> = emptyMap(),
        readImage: (String) -> ByteArray? = { null },
        locations: List<inga.bpmetrics.library.LocationEntity> = emptyList(),
        presets: List<inga.bpmetrics.library.ExportPresetEntity> = emptyList()
    ): String = gson.toJson(
        buildBackup(
            records, people, watches, categories, settings, events, collections, eventTags,
            readImage, locations, presets
        )
    )

    /**
     * Writes a backup to [out] as it is serialised, rather than building it as one string first.
     *
     * A whole library as a single `String` is two bytes per character held at once, and then
     * copied again into a byte array to write it.
     */
    fun writeBackup(out: java.io.OutputStream, backup: LibraryBackup) {
        java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8)).let { writer ->
            gson.toJson(backup, LibraryBackup::class.java, writer)
            writer.flush()
        }
    }

    /**
     * Assembles records together with the people and watches they refer to.
     *
     * Only the people and watches actually referenced are included, so exporting three recordings
     * does not carry the whole registry with them — but restoring those three still reproduces
     * everyone involved, with their colours.
     */
    fun buildBackup(
        // With readings: a backup carries every one of them, which is the whole point of it.
        records: List<BpmRecordWithPoints>,
        people: List<PersonEntity> = emptyList(),
        watches: List<WatchEntity> = emptyList(),
        categories: List<CategoryEntity> = emptyList(),
        settings: List<PreferenceSnapshot> = emptyList(),
        events: List<EventEntity> = emptyList(),
        /**
         * Every selection, with its members and any frozen rows.
         *
         * Collections had never been carried. `savedAnalyses` is gone from this signature: a
         * frozen analysis is a collection now, and nothing writes the old shape.
         */
        collections: List<inga.bpmetrics.library.CollectionBackup> = emptyList(),
        /** Tags applied to each event, by event id. */
        eventTags: Map<Long, List<TagEntity>> = emptyMap(),
        /**
         * Reads a stored image by its file name, for inlining.
         *
         * Passed in rather than read here so this stays a pure transformation and can be tested
         * without a filesystem — the round-trip test supplies bytes directly.
         */
        readImage: (String) -> ByteArray? = { null },
        /** The venue registry, so events can name where they were. */
        locations: List<inga.bpmetrics.library.LocationEntity> = emptyList(),
        /** Saved export looks. Carried since format 6. */
        presets: List<inga.bpmetrics.library.ExportPresetEntity> = emptyList()
    ): LibraryBackup {
        val locationNames = locations.associate { it.locationId to it.name }
        val categoryNames = categories.associate { it.categoryId to it.name }
        val peopleById = people.associateBy { it.personId }
        val eventsById = events.associateBy { it.eventId }

        // The events these recordings belong to, **and everything above them**.
        //
        // Only the direct events would export a set and lose the day and the festival it was part
        // of — `parentName` would name something the file does not contain, and the restore would
        // rebuild a flat list. Collections are events since the fold, so one ancestry walk covers
        // what used to be a separate climb through collections and their parents.
        val usedEvents = records
            .mapNotNull { it.metadata.eventId }
            .distinct()
            .flatMap { inga.bpmetrics.library.EventTree.ancestryOf(events, it) }
            .distinctBy { it.eventId }
        val eventNames = events.associate { it.eventId to it.name }
        val usedEventDtos = usedEvents.map { event ->
            EventDto(
                eventId = event.eventId,
                name = event.name,
                // Always null now. An event says where it sits with `parentName`, which reaches any
                // depth; `groupName` only ever named a collection and stays in the format so a
                // file written before the fold still restores.
                groupName = null,
                notes = event.notes,
                createdAt = event.createdAt,
                tags = eventTags[event.eventId].orEmpty().map { it.qualified(categoryNames) },
                type = event.type,
                parentName = event.parentId?.let { eventNames[it] },
                parentEventId = event.parentId,
                windowStart = event.windowStart,
                windowEnd = event.windowEnd,
                excludedFromParentAnalysis = event.excludedFromParentAnalysis,
                cover = event.ownCover?.toDto(readImage),
                locationName = event.locationId?.let { locationNames[it] }
            )
        }

        val usedPeople = records
            .mapNotNull { it.metadata.personId }
            .distinct()
            .mapNotNull { peopleById[it] }
            .map { person ->
                PersonDto(
                    name = person.name,
                    colorArgb = person.colorArgb,
                    createdAt = person.createdAt,
                    restingBpm = person.restingBpm,
                    maxBpm = person.maxBpm,
                    photoBase64 = person.photoPath?.let(readImage)?.toBase64(),
                    photoCrop = person.ownPhoto?.toDto { null }
                )
            }

        val usedWatchIds = records.mapNotNull { it.metadata.watchId }.toSet()
        val usedWatches = watches
            .filter { it.watchId in usedWatchIds }
            .map { WatchDto(it.watchId, it.deviceName, it.lastKnownModel) }

        val dtos = records.map { record ->
            BpmRecordJsonDto(
                recordId = record.metadata.recordId,
                title = record.metadata.title,
                description = record.metadata.description,
                deviceId = record.metadata.deviceId,
                // The live profile name where there is one, else whatever was frozen on the record.
                wearerName = record.metadata.personId?.let { peopleById[it]?.name }
                    ?: record.metadata.wearerName.takeIf { it.isNotBlank() },
                watchId = record.metadata.watchId,
                eventName = record.metadata.eventId?.let { eventsById[it]?.name },
                eventId = record.metadata.eventId,
                date = record.metadata.date,
                startTime = record.metadata.startTime,
                endTime = record.metadata.endTime,
                // Category *name*, not id. This used to write the id while the importer expected a
                // name, so tags never survived a round trip.
                tags = record.tags.map { it.qualified(categoryNames) },
                locationName = record.metadata.locationId?.let { locationNames[it] },
                cover = record.metadata.coverPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        Cover(
                            path = it,
                            cropLeft = record.metadata.coverCropLeft ?: 0f,
                            cropTop = record.metadata.coverCropTop ?: 0f,
                            cropRight = record.metadata.coverCropRight ?: 1f,
                            cropBottom = record.metadata.coverCropBottom ?: 1f,
                            blur = record.metadata.coverBlur ?: 0f,
                            dim = record.metadata.coverDim ?: 0f
                        ).toDto(readImage)
                    },
                dataPoints = record.dataPoints.map { BpmDataPointDto(it.timestamp, it.bpm) }
            )
        }

        return LibraryBackup(
            exportedAt = System.currentTimeMillis(),
            locations = locations.map { place ->
                LocationDto(
                    name = place.name,
                    timeZoneId = place.timeZoneId,
                    latitude = place.latitude,
                    longitude = place.longitude,
                    photoBase64 = place.photoPath?.let(readImage)?.toBase64()
                )
            },
            people = usedPeople,
            watches = usedWatches,
            records = dtos,
            settings = settings,
            events = usedEventDtos,
            // `savedAnalyses` and `eventGroups` are left empty on purpose. Nothing writes
            // either since the folds — a frozen analysis is a collection, and a collection
            // travels below rather than as a tier. Both stay in the format so a file written
            // before then still restores.
            // Every saved look. Left out of the format until 6 on the reasoning that a preset
            // describes how an export looks rather than what the library holds — true, and not
            // a reason for "back up everything" to mean "everything except the looks you built".
            presets = presets.map { preset ->
                ExportPresetDto(
                    name = preset.name,
                    configJson = preset.configJson,
                    isDefault = preset.isDefault,
                    isBuiltIn = preset.isBuiltIn,
                    createdAt = preset.createdAt
                )
            },
            collections = collections.map { backup ->
                val set = backup.collection
                CollectionDto(
                    name = set.name,
                    notes = set.notes,
                    createdAt = set.createdAt,
                    filterJson = set.filterJson,
                    excludedRecordJson = set.excludedRecordJson,
                    isPinned = set.isPinned,
                    frozenAt = set.frozenAt,
                    cover = set.ownCover?.toDto(readImage),
                    eventNames = backup.eventNames,
                    eventIds = backup.eventIds,
                    recordIds = backup.recordIds,
                    frozenRecords = backup.frozenRecords.map { row ->
                        SavedAnalysisRecordDto(
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
        )
    }

    /** Kept for callers that only want the records array. */
    fun toJsonString(records: List<BpmRecordWithPoints>): String = toBackupJson(records)

    /**
     * Reads a backup file, accepting both shapes.
     *
     * Files written before this format existed are a bare JSON array; the current one is an object
     * with a `records` field. Both are still read, because a backup that cannot be restored by a
     * later build is not a backup.
     */
    fun readBackup(context: Context, uri: Uri): LibraryBackup? =
        (openBackup(context, uri) as? BackupFile.Read)?.backup

    /**
     * Reads a backup straight off the stream, and says why when it cannot.
     *
     * Streamed rather than read into a string: the old path held the whole file as text, then as a
     * parse tree, then as objects — three copies of a library at once, enough to run a phone out of
     * memory on a large one. Running out of memory is an `Error`, not an `Exception`, so it went
     * straight past the catch and took the app down with it.
     */
    fun openBackup(context: Context, uri: Uri): BackupFile {
        fun reader() = context.contentResolver.openInputStream(uri)
            ?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)) }
        return try {
            reader()?.use { parseBackup(it) { reader() } }
                ?: BackupFile.Unreadable("That file could not be opened")
        } catch (e: OutOfMemoryError) {
            BackupFile.Unreadable("That backup is too large for this phone to read")
        } catch (e: Exception) {
            android.util.Log.e("JsonExporter", "Could not read backup", e)
            BackupFile.Unreadable("That file is not a BPMetrics backup")
        }
    }

    /** Split out from [readBackup] so the parsing is testable without a content URI. */
    fun parseBackup(jsonString: String): LibraryBackup? = try {
        (parseBackup(java.io.StringReader(jsonString)) { java.io.StringReader(jsonString) }
            as? BackupFile.Read)?.backup
    } catch (e: Exception) {
        null
    }

    /**
     * Reads any of the three shapes a backup has had, deciding from the first token.
     *
     * - An object with `records`: the current format.
     * - A bare array of records: the first format.
     * - A single record on its own, which older exports also produced.
     *
     * Anything else is refused with a reason rather than returned empty.
     *
     * @param reopen A fresh reader over the same content. Needed only for the single-record shape:
     * an object is read as a backup first, because that is what every large file is, and only if
     * that finds nothing is it read again as one record — which is small by definition.
     */
    fun parseBackup(input: java.io.Reader, reopen: () -> java.io.Reader?): BackupFile {
        val reader = com.google.gson.stream.JsonReader(input)
        return when (reader.peek()) {
            com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                val listType = object : TypeToken<List<BpmRecordJsonDto>>() {}.type
                val records: List<BpmRecordJsonDto>? = gson.fromJson(reader, listType)
                BackupFile.Read(LibraryBackup(records = records.orEmpty()))
            }
            com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                val backup: LibraryBackup? = gson.fromJson(reader, LibraryBackup::class.java)
                if (backup != null && !backup.isEmpty()) return BackupFile.Read(backup)

                val single = reopen()?.use { gson.fromJson(it, BpmRecordJsonDto::class.java) }
                if (single != null && single.dataPoints.isNotEmpty()) {
                    BackupFile.Read(LibraryBackup(records = listOf(single)))
                } else {
                    BackupFile.Unreadable("That file is not a BPMetrics backup")
                }
            }
            else -> BackupFile.Unreadable("That file is not a BPMetrics backup")
        }
    }

    /** Holds nothing a restore could add. A JSON object that is not a backup reads as this. */
    private fun LibraryBackup.isEmpty(): Boolean =
        records.isEmpty() && people.isEmpty() && watches.isEmpty() && events.isEmpty() &&
            eventGroups.isEmpty() && locations.isEmpty() && collections.isEmpty() &&
            savedAnalyses.isEmpty() && settings.isEmpty() && presets.isEmpty()

    /**
     * Whether a file's content starts like JSON, ignoring its name and type.
     *
     * A backup that has been texted, emailed or downloaded loses both: messaging apps hand it over
     * as `application/octet-stream`, under a content URI with no extension in it. Deciding from
     * those sent a perfectly good backup down the CSV importer, which said only that it failed.
     */
    fun looksLikeJson(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val head = ByteArray(64)
            val n = stream.read(head)
            startsLikeJson(head.copyOf(maxOf(n, 0)))
        } ?: false
    } catch (e: Exception) {
        false
    }

    /** The decision behind [looksLikeJson], on the first bytes of a file. */
    internal fun startsLikeJson(head: ByteArray): Boolean =
        // Skips a UTF-8 byte order mark and leading whitespace.
        head.dropWhile { it == 0xEF.toByte() || it == 0xBB.toByte() || it == 0xBF.toByte() }
            .firstOrNull { !it.toInt().toChar().isWhitespace() }
            ?.let { it == '{'.code.toByte() || it == '['.code.toByte() }
            ?: false

    /**
     * Imports a list of [BpmWatchRecord]s from a JSON URI.
     *
     * Restoring the people and watches themselves is the caller's job — see the repository's
     * restore path. This returns only what the ordinary ingest route can accept.
     */
    fun importFromJson(context: Context, uri: Uri): List<BpmWatchRecord> {
        val backup = readBackup(context, uri) ?: return emptyList()
        return backup.records.toWatchRecords()
    }

    /** Converts parsed records into the shape the ingest path accepts, dropping anything unusable. */
    fun List<BpmRecordJsonDto>.toWatchRecords(): List<BpmWatchRecord> = mapNotNull { dto ->
        if (dto.startTime > 0 && dto.endTime > dto.startTime && dto.dataPoints.isNotEmpty()) {
            BpmWatchRecord(
                date = Date(dto.date.takeIf { it > 0 } ?: dto.startTime),
                dataPoints = dto.dataPoints.map { BpmDataPoint(it.timestamp, it.bpm) },
                startTime = dto.startTime,
                endTime = dto.endTime,
                title = dto.title,
                description = dto.description,
                tagNames = dto.tags,
                deviceId = dto.deviceId,
                wearerName = dto.wearerName,
                watchId = dto.watchId
            )
        } else null
    }

}

/** How far up a chain of collections to walk before assuming a cycle. */
private const val MAX_ANCESTRY = 32

/** A tag as the backup writes it: the axis it belongs to, then the value. */
internal fun TagEntity.qualified(categoryNames: Map<Long, String>): String =
    "${categoryNames[parentCategoryId] ?: "Uncategorized"}:$name"

internal fun ByteArray.toBase64(): String =
    android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

internal fun String.fromBase64(): ByteArray? =
    runCatching { android.util.Base64.decode(this, android.util.Base64.NO_WRAP) }.getOrNull()

/**
 * A cover as the backup carries it, with the image inlined if it can be read.
 *
 * The crop survives even when the image cannot — a missing file leaves the framing intact so a
 * replacement picture lands the way the old one was set, rather than resetting to the whole frame.
 */
internal fun Cover.toDto(readImage: (String) -> ByteArray?): CoverDto =
    CoverDto(
        imageBase64 = readImage(path)?.toBase64(),
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRight = cropRight,
        cropBottom = cropBottom,
        blur = blur,
        dim = dim
    )
