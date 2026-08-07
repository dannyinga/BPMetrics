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
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.PersonEntity
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
    val settings: List<PreferenceSnapshot> = emptyList()
) {
    companion object {
        /**
         * 1: records, data points, tags, people, watches.
         * 2: saved analyses and app settings, and a record's original id so analyses can be
         *    re-pointed at the right recordings after import reassigns them.
         *
         * Export presets are not here because they do not exist yet. When they do, this becomes 3.
         */
        const val FORMAT_VERSION = 2
    }
}

/**
 * A saved analysis and the snapshot rows it froze.
 *
 * Its rows reference recordings by their id at the time, which import reassigns — so
 * [SavedAnalysisRecordDto.recordId] is remapped during restore. The rest of each row is a snapshot
 * and travels as-is: that is the whole point of a frozen analysis.
 */
data class SavedAnalysisDto(
    val name: String,
    val createdAt: Long,
    val filterDescription: String = "",
    val kind: String = "GROUP",
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
    val records: List<SavedAnalysisRecordDto> = emptyList()
)

data class SavedAnalysisRecordDto(
    /** The recording's id in the library this backup came from. Remapped on restore. */
    val recordId: Long,
    val title: String,
    val date: Long,
    val minBpm: Double?,
    val avgBpm: Double?,
    val maxBpm: Double?,
    val activeDurationMs: Long,
    val tagsEncoded: String = "",
    val wearerName: String = "",
    val watchName: String = ""
)

data class PersonDto(
    val name: String,
    val colorArgb: Int
)

data class WatchDto(
    val watchId: String,
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
    val title: String,
    val description: String = "",
    val deviceId: String = "Watch",
    /** Who wore it, by name — matched to a person on restore, created if missing. */
    val wearerName: String? = null,
    /** Which watch, by its stable identifier rather than its current name. */
    val watchId: String? = null,
    val date: Long = 0L,
    val startTime: Long,
    val endTime: Long,
    val tags: List<String> = emptyList(), // "Category:Tag" format
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
        records: List<BpmRecord>,
        people: List<PersonEntity> = emptyList(),
        watches: List<WatchEntity> = emptyList(),
        categories: List<CategoryEntity> = emptyList(),
        savedAnalyses: List<SavedAnalysisDto> = emptyList(),
        settings: List<PreferenceSnapshot> = emptyList()
    ): String {
        val categoryNames = categories.associate { it.categoryId to it.name }
        val peopleById = people.associateBy { it.personId }

        val usedPeople = records
            .mapNotNull { it.metadata.personId }
            .distinct()
            .mapNotNull { peopleById[it] }
            .map { PersonDto(name = it.name, colorArgb = it.colorArgb) }

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
                date = record.metadata.date,
                startTime = record.metadata.startTime,
                endTime = record.metadata.endTime,
                // Category *name*, not id. This used to write the id while the importer expected a
                // name, so tags never survived a round trip.
                tags = record.tags.map { tag ->
                    "${categoryNames[tag.parentCategoryId] ?: "Uncategorized"}:${tag.name}"
                },
                dataPoints = record.dataPoints.map { BpmDataPointDto(it.timestamp, it.bpm) }
            )
        }

        return gson.toJson(
            LibraryBackup(
                exportedAt = System.currentTimeMillis(),
                people = usedPeople,
                watches = usedWatches,
                records = dtos,
                savedAnalyses = savedAnalyses,
                settings = settings
            )
        )
    }

    /** Kept for callers that only want the records array. */
    fun toJsonString(records: List<BpmRecord>): String = toBackupJson(records)

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

    /**
     * Shares records as a backup file.
     *
     * Pass the people, watches and categories to produce a restorable backup; without them this
     * degrades to the old record-only export.
     */
    fun shareJson(
        context: Context,
        records: List<BpmRecord>,
        people: List<PersonEntity> = emptyList(),
        watches: List<WatchEntity> = emptyList(),
        categories: List<CategoryEntity> = emptyList()
    ) {
        if (records.isEmpty()) return
        val fileName = if (records.size == 1) {
            val title = records.first().metadata.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(" ", "_")
            "${title}_export.bpmjson"
        } else {
            "BPMetrics_Backup_${System.currentTimeMillis()}.bpmjson"
        }
        val tempFile = File(context.cacheDir, fileName)
        try {
            FileWriter(tempFile).use { it.write(toBackupJson(records, people, watches, categories)) }
            ExportUtils.shareFile(context, tempFile, "application/json")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
