package inga.bpmetrics.export

import android.util.Log
import inga.bpmetrics.export.JsonExporter.toWatchRecords
import inga.bpmetrics.library.LibraryRepository
import kotlinx.coroutines.flow.first

/**
 * What a restore did, so the UI can say something specific rather than "done".
 */
data class RestoreResult(
    val peopleCreated: Int = 0,
    val watchesCreated: Int = 0,
    val recordsImported: Int = 0,
    val recordsSkipped: Int = 0,
    val eventsCreated: Int = 0,
    val groupsCreated: Int = 0,
    val analysesRestored: Int = 0,
    val settingsRestored: Int = 0,
    val failure: String? = null
) {
    val succeeded: Boolean get() = failure == null
}

/**
 * Rebuilds a library from a backup file.
 *
 * Order matters. People and watches are created first, because a record's link to them is resolved
 * during ingest — `saveWatchRecordToLibrary` looks a person up by name and a watch up by id, and
 * finding neither would import the recordings as unattributed and quietly lose who made them.
 *
 * Existing people and watches are matched, not duplicated, so restoring into a library that already
 * has some of them merges rather than doubling.
 */
suspend fun restoreBackup(
    backup: LibraryBackup,
    repository: LibraryRepository
): RestoreResult {
    val tag = "BackupRestore"

    if (backup.formatVersion > LibraryBackup.FORMAT_VERSION) {
        return RestoreResult(
            failure = "This backup was written by a newer version of the app " +
                "(format ${backup.formatVersion}, this build reads ${LibraryBackup.FORMAT_VERSION})."
        )
    }

    return try {
        var peopleCreated = 0
        val existingPeople = repository.getAllPeople().first().associateBy { it.name.lowercase() }
        backup.people.forEach { person ->
            if (existingPeople[person.name.lowercase()] == null) {
                repository.addPerson(person.name, person.colorArgb)
                peopleCreated++
            }
        }

        var watchesCreated = 0
        val existingWatchIds = repository.getAllWatches().first().map { it.watchId }.toSet()
        backup.watches.forEach { watch ->
            if (watch.watchId !in existingWatchIds) {
                repository.registerWatch(
                    watchId = watch.watchId,
                    deviceName = watch.deviceName,
                    model = watch.lastKnownModel
                )
                watchesCreated++
            }
        }

        // Groups before events before records, so each link exists before something needs it.
        var groupsCreated = 0
        val groupIdsByName = mutableMapOf<String, Long>()
        backup.eventGroups.forEach { group ->
            groupIdsByName[group.name] = repository.createEventGroup(group.name)
            groupsCreated++
        }

        var eventsCreated = 0
        val eventIdsByName = mutableMapOf<String, Long>()
        backup.events.forEach { event ->
            // Two events sharing a name merge, which is a fair reading of naming them the same.
            if (eventIdsByName.containsKey(event.name)) return@forEach
            eventIdsByName[event.name] = repository.createEvent(
                name = event.name,
                groupId = event.groupName?.let { groupIdsByName[it] }
            )
            eventsCreated++
        }

        // Import records and remember where each one landed. Ids are reassigned on insert, so a
        // saved analysis pointing at record 42 has to be told which recording that is now — without
        // this it would point at whatever happens to be 42 in the new library, which is worse than
        // pointing at nothing.
        val idMap = mutableMapOf<Long, Long>()
        var imported = 0
        backup.records.forEach { dto ->
            val watchRecord = listOf(dto).toWatchRecords().firstOrNull() ?: return@forEach
            // The ordinary ingest path, so a restored recording is indistinguishable from one that
            // arrived from a watch — same analysis, same person resolution, same auto-naming.
            val newId = repository.saveWatchRecordToLibrary(watchRecord)
            if (dto.recordId != 0L) idMap[dto.recordId] = newId

            // Filed after saving rather than through the ingest path, which knows nothing about
            // events — it exists to accept what a watch sends, and a watch has no idea what set it
            // was at.
            dto.eventName?.let { name ->
                eventIdsByName[name]?.let { eventId ->
                    repository.assignRecordsToEvent(listOf(newId), eventId)
                }
            }
            imported++
        }

        var analysesRestored = 0
        backup.savedAnalyses.forEach { analysis ->
            val remapped = analysis.records.map { row ->
                // An unmapped id means that recording was not in this backup. The row is kept — it
                // is a frozen snapshot and still readable — but pointed at nothing rather than at
                // an unrelated recording.
                row.copy(recordId = idMap[row.recordId] ?: -1L)
            }
            repository.restoreSavedAnalysis(analysis.copy(records = remapped))
            analysesRestored++
        }

        val settingsRestored = if (backup.settings.isNotEmpty()) {
            repository.restoreSettings(backup.settings)
        } else 0

        val result = RestoreResult(
            peopleCreated = peopleCreated,
            watchesCreated = watchesCreated,
            recordsImported = imported,
            recordsSkipped = backup.records.size - imported,
            eventsCreated = eventsCreated,
            groupsCreated = groupsCreated,
            analysesRestored = analysesRestored,
            settingsRestored = settingsRestored
        )
        Log.i(tag, "Restored $result")
        result
    } catch (e: Exception) {
        Log.e(tag, "Restore failed", e)
        RestoreResult(failure = e.message ?: e.toString())
    }
}
