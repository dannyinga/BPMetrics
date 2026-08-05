package inga.bpmetrics.ui.watches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.WatchEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Watches section.
 *
 * Naming a watch here changes only what future recordings are stamped with. Correcting recordings
 * that already arrived is a separate, explicit action — see [reattribute].
 */
class WatchesViewModel(private val repository: LibraryRepository) : ViewModel() {

    private val _recordCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot feedback for the UI to show and then clear. */
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<WatchesUiState> = combine(
        repository.getAllWatches(),
        _recordCounts
    ) { watches, counts ->
        WatchesUiState(
            watches = watches.map { watch ->
                WatchRow(watch = watch, recordCount = counts[watch.watchId] ?: 0)
            },
            isEmpty = watches.isEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchesUiState())

    init {
        refreshCounts()
    }

    /** Record counts are a per-watch query, so they are refreshed rather than observed. */
    fun refreshCounts() {
        viewModelScope.launch {
            val watches = uiState.value.watches.map { it.watch.watchId }
            if (watches.isEmpty()) return@launch
            _recordCounts.value = watches.associateWith { repository.countRecordsForWatch(it) }
        }
    }

    /**
     * Updates both names at once, since the dialog edits them together.
     *
     * Only the wearer affects recordings, and only ones that arrive from here on.
     */
    fun save(watchId: String, deviceName: String, wearerName: String) {
        viewModelScope.launch {
            repository.renameWatch(watchId, deviceName)
            repository.setWatchWearer(watchId, wearerName)
            _message.value = if (wearerName.isBlank()) {
                "Wearer cleared. New recordings will arrive unattributed."
            } else {
                "Future recordings from this watch will be attributed to $wearerName."
            }
        }
    }

    /**
     * Registers a watch that has not sent anything yet, so its first recordings are attributed.
     */
    fun addWatch(watchId: String, deviceName: String, wearerName: String) {
        viewModelScope.launch {
            repository.registerWatch(
                watchId = watchId.trim(),
                deviceName = deviceName,
                wearerName = wearerName
            )
            refreshCounts()
            _message.value = "Watch registered."
        }
    }

    /**
     * Applies a name to recordings that already arrived from this watch.
     *
     * The recovery path for a watch that recorded before anyone named it.
     */
    fun reattribute(watchId: String, wearerName: String, fromDate: Long, toDate: Long) {
        viewModelScope.launch {
            val changed = repository.reattributeRecords(watchId, wearerName, fromDate, toDate)
            _message.value = when (changed) {
                0 -> "No recordings in that range."
                1 -> "1 recording re-attributed to $wearerName."
                else -> "$changed recordings re-attributed to $wearerName."
            }
        }
    }

    fun merge(fromWatchId: String, intoWatchId: String) {
        viewModelScope.launch {
            repository.mergeWatches(fromWatchId, intoWatchId)
            refreshCounts()
            _message.value = "Watches merged."
        }
    }

    fun delete(watchId: String) {
        viewModelScope.launch {
            repository.deleteWatch(watchId)
            refreshCounts()
            _message.value = "Watch removed. Existing recordings keep their names."
        }
    }

    fun consumeMessage() { _message.value = null }

    class Factory(private val repository: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return WatchesViewModel(repository) as T
        }
    }
}

/**
 * @property watch The registry entry.
 * @property recordCount How many recordings are attributed to it.
 */
data class WatchRow(
    val watch: WatchEntity,
    val recordCount: Int
)

data class WatchesUiState(
    val watches: List<WatchRow> = emptyList(),
    val isEmpty: Boolean = true
)
