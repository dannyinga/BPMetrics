package inga.bpmetrics.ui.watches

import inga.bpmetrics.util.launchGuarded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
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
        repository.getAllPeople(),
        _recordCounts
    ) { watches, people, counts ->
        val byId = people.associateBy { it.personId }
        WatchesUiState(
            watches = watches.map { watch ->
                WatchRow(
                    watch = watch,
                    recordCount = counts[watch.watchId] ?: 0,
                    // Resolved live, so renaming someone or recolouring them shows up here at once.
                    wearer = watch.currentPersonId?.let { byId[it] }
                )
            },
            people = people,
            isEmpty = watches.isEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchesUiState())

    init {
        refreshCounts()
    }

    /** Record counts are a per-watch query, so they are refreshed rather than observed. */
    fun refreshCounts() {
        launchGuarded {
            val watches = uiState.value.watches.map { it.watch.watchId }
            if (watches.isEmpty()) return@launchGuarded
            _recordCounts.value = watches.associateWith { repository.countRecordsForWatch(it) }
        }
    }

    /**
     * Updates both names at once, since the dialog edits them together.
     *
     * Only the wearer affects recordings, and only ones that arrive from here on.
     */
    fun save(watchId: String, deviceName: String, personId: Long?) {
        launchGuarded {
            repository.renameWatch(watchId, deviceName)
            repository.setWatchPerson(watchId, personId)
            val name = personId?.let { repository.getPerson(it)?.name }
            _message.value = if (name == null) {
                "Wearer cleared. New recordings will arrive unattributed."
            } else {
                "Future recordings from this watch will be attributed to $name."
            }
        }
    }

    /**
     * Registers a watch that has not sent anything yet, so its first recordings are attributed.
     */
    fun addWatch(watchId: String, deviceName: String, personId: Long?) {
        launchGuarded {
            repository.registerWatch(
                watchId = watchId.trim(),
                deviceName = deviceName,
                personId = personId
            )
            refreshCounts()
            _message.value = "Watch registered."
        }
    }

    /**
     * Attributes recordings that already arrived from this watch to someone.
     *
     * The recovery path for a watch that recorded before anyone was assigned to it.
     */
    fun reattribute(watchId: String, personId: Long, fromDate: Long, toDate: Long) {
        launchGuarded {
            val changed = repository.reattributeRecords(watchId, personId, fromDate, toDate)
            val name = repository.getPerson(personId)?.name ?: "them"
            _message.value = when (changed) {
                0 -> "No recordings in that range."
                1 -> "1 recording re-attributed to $name."
                else -> "$changed recordings re-attributed to $name."
            }
        }
    }

    fun merge(fromWatchId: String, intoWatchId: String) {
        launchGuarded {
            repository.mergeWatches(fromWatchId, intoWatchId)
            refreshCounts()
            _message.value = "Watches merged."
        }
    }

    fun delete(watchId: String) {
        launchGuarded {
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
 * @property wearer Who is wearing it now, resolved live so a rename or recolour shows up at once.
 */
data class WatchRow(
    val watch: WatchEntity,
    val recordCount: Int,
    val wearer: PersonEntity? = null
)

data class WatchesUiState(
    val watches: List<WatchRow> = emptyList(),
    /** Everyone available to assign, for the pickers in this screen's dialogs. */
    val people: List<PersonEntity> = emptyList(),
    val isEmpty: Boolean = true
)
