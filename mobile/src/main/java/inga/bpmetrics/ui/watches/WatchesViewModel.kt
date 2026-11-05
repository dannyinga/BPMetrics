package inga.bpmetrics.ui.watches

import inga.bpmetrics.util.launchGuarded
import inga.bpmetrics.ui.feedback.Feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.WatchEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    val uiState: StateFlow<WatchesUiState> = combine(
        repository.getAllWatches(),
        repository.getAllPeople(),
        // Observed, so a count follows recordings as they arrive, move and go. These loaded once,
        // before the list had, and so showed 0 until the screen poked them.
        repository.recordCountsByWatch
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
            Feedback.say(
                if (name == null) {
                    "Wearer cleared. New recordings will arrive unattributed."
                } else {
                    "Future recordings from this watch will be attributed to $name."
                }
            )
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
            Feedback.say("Watch registered.")
        }
    }

    /**
     * What "Apply to past" would do, so it can be confirmed with a number before anything changes.
     *
     * [fromPersonId] is who the watch belonged to before this edit. Only that person's recordings
     * from it, and ones nobody was wearing, are offered to [toPersonId].
     */
    fun previewApplyToPast(
        watchId: String,
        fromPersonId: Long?,
        toPersonId: Long,
        onReady: (ApplyToPast) -> Unit
    ) {
        launchGuarded(failure = "Couldn't count this watch's recordings.") {
            onReady(
                ApplyToPast(
                    watchId = watchId,
                    fromPersonId = fromPersonId,
                    toPersonId = toPersonId,
                    toName = repository.getPerson(toPersonId)?.displayName ?: "them",
                    fromName = fromPersonId?.let { repository.getPerson(it)?.displayName },
                    count = repository.countClaimablePastRecords(watchId, fromPersonId, toPersonId)
                )
            )
        }
    }

    /** Carries out a confirmed [ApplyToPast]. */
    fun applyToPast(plan: ApplyToPast) {
        launchGuarded(failure = "Couldn't change who wore those recordings.") {
            val changed = repository.claimPastRecords(plan.watchId, plan.fromPersonId, plan.toPersonId)
            Feedback.say(
                if (changed == 1) "1 earlier recording is now ${plan.toName}'s."
                else "$changed earlier recordings are now ${plan.toName}'s."
            )
        }
    }

    fun merge(fromWatchId: String, intoWatchId: String) {
        launchGuarded {
            repository.mergeWatches(fromWatchId, intoWatchId)
            Feedback.say("Watches merged.")
        }
    }

    fun delete(watchId: String) {
        launchGuarded {
            repository.deleteWatch(watchId)
            Feedback.say("Watch removed. Existing recordings keep their names.")
        }
    }

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

/**
 * "Apply to past", worked out but not yet done: who gets which recordings, and how many.
 *
 * Counted first so the confirm can say exactly what will change. [fromName] is the previous
 * wearer, whose recordings from this watch move along with the unattributed ones.
 */
data class ApplyToPast(
    val watchId: String,
    val fromPersonId: Long?,
    val toPersonId: Long,
    val toName: String,
    val fromName: String?,
    val count: Int
)
