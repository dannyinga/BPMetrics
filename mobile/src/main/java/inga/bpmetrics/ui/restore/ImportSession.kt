package inga.bpmetrics.ui.restore

import android.content.Context
import android.net.Uri
import android.util.Log
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.export.BackupFile
import inga.bpmetrics.export.CsvExporter
import inga.bpmetrics.export.JsonExporter
import inga.bpmetrics.export.LibraryBackup
import inga.bpmetrics.export.RestorePlanner
import inga.bpmetrics.export.RestorePreview
import inga.bpmetrics.export.RestoreChoice
import inga.bpmetrics.export.RestoreChoices
import inga.bpmetrics.export.RestoreItem
import inga.bpmetrics.export.RestoreKeys
import inga.bpmetrics.export.RestoreKind
import inga.bpmetrics.export.RestoreResult
import inga.bpmetrics.export.restoreBackup
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.ui.feedback.Feedback
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bringing files into the library, one look before anything is written.
 *
 * Both ways in — Settings' "Restore from file" and the library's "Import recordings…" — used to
 * start writing the moment a file was picked. Now each file is read, previewed as a [Plan], and
 * only applied when confirmed. Several picked files are previewed one after another.
 *
 * Owned by a ViewModel and run in its scope, so leaving the screen cancels a preview that hasn't
 * been confirmed rather than leaving it waiting.
 */
class ImportSession(
    private val repository: LibraryRepository,
    private val scope: CoroutineScope,
    /** Told after each backup is restored, for screens that keep the last result. */
    private val onRestored: (RestoreResult) -> Unit = {}
) {
    /** What one file would do, waiting for a yes. */
    sealed interface Plan {
        /** "File 2 of 3" when several were picked. */
        val position: Int
        val of: Int
        /** Every item, and whether it's here already. What the choices are made on. */
        val preview: RestorePreview

        data class Backup(
            val backup: LibraryBackup,
            override val preview: RestorePreview,
            override val position: Int,
            override val of: Int
        ) : Plan

        /** Exported recordings, one per CSV file, keyed as [RestoreKeys.recording]. */
        data class Csv(
            val records: List<BpmWatchRecord>,
            override val preview: RestorePreview,
            override val position: Int,
            override val of: Int
        ) : Plan
    }

    /** Recordings handled so far, while a confirmed plan is being applied. */
    data class Progress(val done: Int, val total: Int)

    private val _plan = MutableStateFlow<Plan?>(null)
    val plan: StateFlow<Plan?> = _plan.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Reading files or applying one. Back up and Restore are both disabled meanwhile. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val waiting = ArrayDeque<Plan>()

    private val guard = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Import failed", t)
        _busy.value = false
        _progress.value = null
        Feedback.failed("Couldn't finish that import. Anything already added stays.")
    }

    /**
     * Reads [uris] and previews the first. Backups are told from CSVs by what's in them, not by
     * name: a file that's been texted or downloaded often has no extension.
     */
    fun open(context: Context, uris: List<Uri>) {
        if (_busy.value || uris.isEmpty()) return
        scope.launch(guard) {
            _busy.value = true
            try {
                val plans = withContext(Dispatchers.IO) { read(context, uris) }
                waiting.clear()
                waiting.addAll(plans)
                showNext()
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun read(context: Context, uris: List<Uri>): List<Plan> {
        val (backupUris, csvUris) = uris.partition { JsonExporter.looksLikeJson(context, it) }
        val library = RestorePlanner.libraryOf(repository)

        val backups = backupUris.mapNotNull { uri ->
            when (val opened = JsonExporter.openBackup(context, uri)) {
                is BackupFile.Read -> opened.backup
                is BackupFile.Unreadable -> {
                    Feedback.failed(opened.reason)
                    null
                }
            }
        }

        val csv = if (csvUris.isEmpty()) null else {
            // One recording per file. The same one twice is one, as in a backup.
            val records = csvUris.mapNotNull { CsvExporter.importFromCsv(context, it) }
                .distinctBy { RestoreKeys.recording(it.startTime, it.endTime, it.deviceId) }
            records to RestorePreview(
                items = records.map { record ->
                    RestoreItem(
                        RestoreKind.RECORDING,
                        RestoreKeys.recording(record.startTime, record.endTime, record.deviceId),
                        label = record.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                        at = record.startTime,
                        matched = Triple(record.startTime, record.endTime, record.deviceId) in library.recordIdentities
                    )
                },
                unreadableRecordings = csvUris.size - records.size
            )
        }

        val of = backups.size + if (csv != null) 1 else 0
        return backups.mapIndexed { i, backup ->
            Plan.Backup(backup, RestorePlanner.preview(backup, library), position = i + 1, of = of)
        } + listOfNotNull(
            csv?.let { (records, preview) -> Plan.Csv(records, preview, position = of, of = of) }
        )
    }

    /** Applies what's showing, as [choices] say, then shows the next file, if any. */
    fun confirm(context: Context, choices: RestoreChoices) {
        val plan = _plan.value ?: return
        if (_busy.value) return
        scope.launch(guard) {
            _busy.value = true
            try {
                when (plan) {
                    is Plan.Backup -> {
                        val result = restoreBackup(
                            plan.backup, repository, context.applicationContext,
                            choices = choices,
                            onProgress = { done, total -> _progress.value = Progress(done, total) }
                        )
                        onRestored(result)
                        if (result.succeeded && result.rulesNeedingAttention.isEmpty()) {
                            Feedback.say(result.summary())
                        } else {
                            Feedback.failed(result.summary())
                        }
                    }
                    is Plan.Csv -> {
                        val here = repository.recordIdsByIdentity()
                        var added = 0
                        var replaced = 0
                        plan.records.forEachIndexed { i, record ->
                            _progress.value = Progress(i + 1, plan.records.size)
                            val identity = Triple(record.startTime, record.endTime, record.deviceId)
                            val existing = here[identity]
                            val key = RestoreKeys.recording(identity.first, identity.second, identity.third)
                            when (choices.of(RestoreKind.RECORDING, key, matched = existing != null)) {
                                RestoreChoice.SKIP -> Unit
                                RestoreChoice.ADD -> {
                                    repository.saveWatchRecordToLibrary(record)
                                    added++
                                }
                                RestoreChoice.REPLACE -> if (existing != null) {
                                    // What a CSV carries beyond the readings: the title, the
                                    // notes and the tags.
                                    record.title?.takeIf { it.isNotBlank() }
                                        ?.let { repository.updateRecordTitle(existing, it) }
                                    record.description?.let { repository.updateRecordDescription(existing, it) }
                                    repository.getTagsForRecord(existing).first()
                                        .forEach { repository.removeTagFromRecord(existing, it.tagId) }
                                    repository.attachTagsToRecord(existing, record.tagNames)
                                    replaced++
                                }
                            }
                        }
                        Feedback.say(
                            listOfNotNull(
                                "Added $added recording${if (added == 1) "" else "s"}",
                                replaced.takeIf { it > 0 }?.let { "replaced $it" }
                            ).joinToString(", ") + "."
                        )
                    }
                }
            } finally {
                _progress.value = null
                _busy.value = false
            }
            showNext()
        }
    }

    /** Skips what's showing without writing anything, and shows the next file, if any. */
    fun dismiss() {
        if (_busy.value) return
        showNext()
    }

    private fun showNext() {
        _plan.value = waiting.removeFirstOrNull()
    }

    private companion object {
        const val TAG = "ImportSession"
    }
}
