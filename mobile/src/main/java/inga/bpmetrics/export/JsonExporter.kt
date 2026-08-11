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
    val collections: List<CollectionDto> = emptyList()
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
         * Export presets are still absent, deliberately: they describe how an export looks rather
         * than what the library contains, they are shareable as their own files, and a preset from
         * a newer build refuses to load on an older one — which a whole-library restore should
         * never do.
         */
        const val FORMAT_VERSION = 5
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
    val name: String = "",
    val groupName: String? = null,
    val notes: String = "",
    val createdAt: Long = 0L,
    /** Tags applied to the event itself, in "Category:Tag" form. Absent entirely before format 4. */
    val tags: List<String> = emptyList(),
    val type: String? = null,
    /** The event above this one, by name. Events nest as of the taxonomy work. */
    val parentName: String? = null,
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
    /** Events it names, by name. Ids are reassigned on import. */
    val eventNames: List<String> = emptyList(),
    /** Recordings it names, by their id in the library this backup came from. */
    val recordIds: List<Long> = emptyList(),
    /** The frozen rows, when [frozenAt] is set. */
    val frozenRecords: List<SavedAnalysisRecordDto> = emptyList()
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
    /** Which event this was part of, by name — events are renumbered on import. */
    val eventName: String? = null,
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
    val timestamp: Long,
    val bpm: Double
)

/**
 * Handles JSON batch export and import for single or multiple BPM records.
 */
object JsonExporter {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Serializes records together with the people and watches they refer to.
     *
     * Only the people and watches actually referenced are included, so exporting three recordings
     * does not carry the whole registry with them — but restoring those three still reproduces
     * everyone involved, with their colours.
     */
    fun toBackupJson(
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
        locations: List<inga.bpmetrics.library.LocationEntity> = emptyList()
    ): String {
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

        return gson.toJson(
            LibraryBackup(
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
    fun readBackup(context: Context, uri: Uri): LibraryBackup? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonString = BufferedReader(InputStreamReader(inputStream)).readText()
                parseBackup(jsonString)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Split out from [readBackup] so the parsing is testable without a content URI. */
    fun parseBackup(jsonString: String): LibraryBackup? {
        return try {
            when (val root = JsonParser.parseString(jsonString)) {
                // Current shape: { formatVersion, people, watches, records }
                else -> if (root.isJsonObject && root.asJsonObject.has("records")) {
                    gson.fromJson(jsonString, LibraryBackup::class.java)
                } else if (root.isJsonArray) {
                    // Legacy: a bare array of records.
                    val listType = object : TypeToken<List<BpmRecordJsonDto>>() {}.type
                    LibraryBackup(records = gson.fromJson(jsonString, listType))
                } else {
                    // A single record on its own, which older exports also produced.
                    val single = gson.fromJson(jsonString, BpmRecordJsonDto::class.java)
                    if (single != null) LibraryBackup(records = listOf(single)) else null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

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
