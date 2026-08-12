package inga.bpmetrics.ui.settings

import inga.bpmetrics.util.launchGuarded

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.datasync.IncomingRecordManager
import inga.bpmetrics.datasync.isActive
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, applied as they are touched.
 *
 * There is no staged copy of anything here and no save. Every setter writes straight through, which
 * is what the platform does everywhere else and what the previous screen conspicuously did not:
 * it held every value in local state, compared them against the stored ones to decide whether it
 * was "dirty", and asked "Would you like to save your changes before leaving?" on the way out. That
 * dialog is the only reason that screen needed a back handler at all.
 */
class SettingsViewModel(
    private val repository: LibraryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // --- Library ---

    val defaultNamingCategoryId: StateFlow<Long?> = settingsRepository.defaultNamingCategoryId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allCategories: Flow<List<CategoryEntity>> = repository.getAllCategories()

    fun setDefaultNamingCategory(categoryId: Long?) {
        launchGuarded {
            if (categoryId == null) {
                settingsRepository.clearDefaultNamingCategory()
            } else {
                settingsRepository.setDefaultNamingCategory(categoryId)
            }
        }
    }

    val defaultSort: StateFlow<String?> = settingsRepository.defaultSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setDefaultSort(sort: String) {
        launchGuarded { settingsRepository.setDefaultSort(sort) }
    }

    // --- Appearance ---

    val dynamicColour: StateFlow<Boolean> = settingsRepository.dynamicColour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setDynamicColour(enabled: Boolean) {
        launchGuarded { settingsRepository.setDynamicColour(enabled) }
    }

    val use24Hour: StateFlow<Boolean> = settingsRepository.use24Hour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setUse24Hour(enabled: Boolean) {
        launchGuarded { settingsRepository.setUse24Hour(enabled) }
    }

    val dateFormat: StateFlow<String> = settingsRepository.dateFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DateFormats.DEFAULT)

    fun setDateFormat(pattern: String) {
        launchGuarded { settingsRepository.setDateFormat(pattern) }
    }

    // --- Heart rate ---

    val restingBpm: StateFlow<Int> = settingsRepository.restingBpm
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsRepository.DEFAULT_RESTING_BPM
        )

    fun setRestingBpm(bpm: Int) {
        launchGuarded { settingsRepository.setRestingBpm(bpm) }
    }

    val maxBpm: StateFlow<Int> = settingsRepository.maxBpm
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsRepository.DEFAULT_MAX_BPM
        )

    fun setMaxBpm(bpm: Int) {
        launchGuarded { settingsRepository.setMaxBpm(bpm) }
    }

    // --- Sync ---

    val syncRetryMinutes: StateFlow<Int> = settingsRepository.syncRetryMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)

    fun setSyncRetryMinutes(minutes: Int) {
        launchGuarded { settingsRepository.setSyncRetryMinutes(minutes) }
    }

    /** How many recordings are still on their way from a watch. */
    val incomingCount: StateFlow<Int> = IncomingRecordManager.incoming
        .map { incoming -> incoming.count { it.status.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val defaultTimeZone: StateFlow<String> = settingsRepository.defaultTimeZone
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            java.time.ZoneId.systemDefault().id
        )

    fun setDefaultTimeZone(zoneId: String) {
        launchGuarded { settingsRepository.setDefaultTimeZone(zoneId) }
    }

    // --- Export ---

    val presets: StateFlow<List<inga.bpmetrics.library.ExportPresetEntity>> =
        repository.getExportPresets()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setDefaultPreset(preset: inga.bpmetrics.library.ExportPresetEntity) {
        launchGuarded { repository.setDefaultExportPreset(preset.presetId) }
    }

    fun deletePreset(preset: inga.bpmetrics.library.ExportPresetEntity) {
        launchGuarded { repository.deleteExportPreset(preset.presetId) }
    }

    /**
     * What the preset editor draws on: one person, and several.
     *
     * Both, because they look different — a lone curve is a blue-to-red gradient and several take
     * a colour each, so a preset that reads well for one can be unreadable for the other.
     *
     * Real recordings when there are any, since judging a look against your own data is the point.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val previewSubjects: StateFlow<Pair<
        List<inga.bpmetrics.library.BpmRecordWithPoints>,
        List<inga.bpmetrics.library.BpmRecordWithPoints>
    >> =
        repository.records
            .mapLatest { library ->
                // A preview is a drawing, so it needs readings — but only for the handful it
                // picks. Which recordings is decided from the rows first, and the readings are
                // loaded for those alone.
                val subjects = repository.recordsWithPoints(
                    (PreviewSubjects.onePerson(library) + PreviewSubjects.severalPeople(library))
                        .map { it.metadata.recordId }
                ).associateBy { it.metadata.recordId }

                fun hydrate(chosen: List<inga.bpmetrics.library.BpmRecord>) =
                    chosen.mapNotNull { subjects[it.metadata.recordId] }

                // Made-up curves only when the library genuinely has nothing to show, which is a
                // fresh install. Judging a look against your own data is the point of this.
                hydrate(PreviewSubjects.onePerson(library))
                    .ifEmpty { PreviewSubjects.syntheticOne() } to
                    hydrate(PreviewSubjects.severalPeople(library))
                        .ifEmpty { PreviewSubjects.syntheticSeveral() }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList<inga.bpmetrics.library.BpmRecordWithPoints>() to emptyList()
            )

    val peopleById: StateFlow<Map<Long, inga.bpmetrics.library.PersonEntity>> =
        repository.getAllPeople()
            .map { people -> people.associateBy { it.personId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun createPreset(name: String, preset: inga.bpmetrics.export.ExportPreset) {
        launchGuarded {
            repository.saveExportPreset(name, preset.copy(name = name).toJson())
            _message.emit("Saved $name")
        }
    }

    /**
     * Rewrites a preset in place, keeping its identity.
     *
     * The name goes into the row *and* into the stored JSON: the row is what the list shows, and
     * the JSON is what a shared preset file carries to whoever opens it.
     */
    fun updatePreset(
        entity: inga.bpmetrics.library.ExportPresetEntity,
        name: String,
        preset: inga.bpmetrics.export.ExportPreset
    ) {
        launchGuarded {
            repository.updateExportPreset(entity.presetId, name, preset.copy(name = name).toJson())
            _message.emit("Saved $name")
        }
    }

    /**
     * The preset a file picker is about to be opened for.
     *
     * The picker returns a destination, not a subject, so which preset is being written has to be
     * remembered across the launch.
     */
    private var pendingPresetExport: inga.bpmetrics.library.ExportPresetEntity? = null

    fun stagePresetForExport(preset: inga.bpmetrics.library.ExportPresetEntity) {
        pendingPresetExport = preset
    }

    fun writePendingPreset(context: Context, uri: android.net.Uri) {
        val preset = pendingPresetExport ?: return
        pendingPresetExport = null
        launchGuarded {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(preset.configJson.toByteArray())
                    }
                }.isSuccess
            }
            _message.emit(if (ok) "Saved ${preset.name}" else "Could not write that file")
        }
    }

    /**
     * Reads a preset file back.
     *
     * Refused rather than half-applied when the payload is malformed or written by a newer build:
     * a preset that looks nothing like the one it was shared from is worse than a refusal that
     * says why.
     */
    fun importPreset(context: Context, uri: android.net.Uri) {
        launchGuarded {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val preset = json?.let { inga.bpmetrics.export.ExportPreset.fromJson(it) }
            if (preset == null) {
                _message.emit("That file is not a preset this version can read")
                return@launchGuarded
            }
            val name = preset.name.ifBlank { "Imported preset" }
            repository.saveExportPreset(name, preset.copy(name = name).toJson())
            _message.emit("Imported $name")
        }
    }

    // --- Storage ---

    private val _storage = MutableStateFlow<StorageInspector.Report?>(null)
    val storage: StateFlow<StorageInspector.Report?> = _storage.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val message: SharedFlow<String> = _message.asSharedFlow()

    /** Whether a restore has completed and the app needs relaunching to pick it up. */
    private val _restarting = MutableStateFlow(false)
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

    fun refreshStorage(context: Context) {
        launchGuarded {
            // Walks directories, so never on the main thread.
            _storage.value = withContext(Dispatchers.IO) { StorageInspector.inspect(context) }
        }
    }

    fun clearStagedExports(context: Context) {
        launchGuarded {
            val freed = withContext(Dispatchers.IO) {
                val before = StorageInspector.inspect(context)
                    .items.firstOrNull { it.label == "Staged exports" }?.bytes ?: 0L
                runCatching { inga.bpmetrics.export.ExportUtils.clearStagedExports(context) }
                before
            }
            _message.emit("Reclaimed ${StorageInspector.formatSize(freed)}")
            refreshStorage(context)
        }
    }

    fun deleteBackup(context: Context, backup: StorageInspector.Backup) {
        launchGuarded {
            withContext(Dispatchers.IO) { StorageInspector.deleteBackup(backup) }
            refreshStorage(context)
        }
    }

    /**
     * Replaces the live database with a backup.
     *
     * The app has to restart afterwards, so this reports success by setting [restarting] rather
     * than by returning: Room holds the open connection, and swapping the file underneath it leaves
     * every subsequent query reading a handle to a file that is gone.
     */
    fun restoreBackup(context: Context, backup: StorageInspector.Backup) {
        launchGuarded {
            val failure = withContext(Dispatchers.IO) { StorageInspector.restore(context, backup) }
            if (failure != null) {
                _message.emit(failure)
            } else {
                _restarting.value = true
            }
        }
    }

    class Factory(
        private val repository: LibraryRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository, settingsRepository) as T
    }
}
