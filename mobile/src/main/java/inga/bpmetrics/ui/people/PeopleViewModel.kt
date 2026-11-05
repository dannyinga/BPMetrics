package inga.bpmetrics.ui.people

import inga.bpmetrics.util.launchGuarded
import inga.bpmetrics.ui.feedback.Feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.PersonEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the People section.
 *
 * Renaming someone here reaches every recording they have made, because a recording stores who
 * wore the watch rather than a copy of their name. Changing who is wearing a *watch* is the
 * separate action that must not reach backwards, and lives in the Watches section.
 */
class PeopleViewModel(private val repository: LibraryRepository) : ViewModel() {

    val uiState: StateFlow<PeopleUiState> = combine(
        repository.getAllPeople(),
        // Observed, so a count follows recordings as they arrive, move and go. These loaded once,
        // before the list had, and so showed 0 until the screen poked them.
        repository.recordCountsByPerson
    ) { people, counts ->
        PeopleUiState(
            people = people.map { person ->
                PersonRow(person = person, recordCount = counts[person.personId] ?: 0)
            },
            isEmpty = people.isEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleUiState())

    fun addPerson(name: String, colorArgb: Int?) {
        launchGuarded {
            repository.addPerson(name, colorArgb)
            Feedback.say("${name.trim()} added.")
        }
    }

    /**
     * Gives someone a photograph.
     *
     * Applied immediately rather than held until the dialog is saved. By this point the file has
     * already been copied into app storage, so a Cancel that discarded the change would leave the
     * image on disk with nothing pointing at it — a leak nobody could find to clean up.
     */
    fun setPhoto(
        context: android.content.Context,
        personId: Long,
        source: android.net.Uri,
        onResult: (Boolean) -> Unit
    ) {
        launchGuarded {
            val name = repository.getPerson(personId)?.displayName ?: "person"
            onResult(repository.setPersonPhoto(context, personId, source, name))
        }
    }

    /** Re-frames the photograph they already have, leaving the file alone. */
    fun setPhotoCrop(personId: Long, photo: inga.bpmetrics.library.Cover) {
        launchGuarded { repository.setPersonPhotoCrop(personId, photo) }
    }

    fun clearPhoto(context: android.content.Context, personId: Long) {
        launchGuarded { repository.clearPersonPhoto(context, personId) }
    }

    fun save(personId: Long, name: String, colorArgb: Int, restingBpm: Int?, maxBpm: Int?) {
        launchGuarded {
            repository.renamePerson(personId, name)
            repository.setPersonColor(personId, colorArgb)
            repository.setPersonZones(personId, restingBpm, maxBpm)
            Feedback.say("Saved.")
        }
    }

    fun delete(personId: Long) {
        launchGuarded {
            val count = repository.countRecordsForPerson(personId)
            repository.deletePerson(personId)
            Feedback.say(
                if (count > 0) {
                    "Removed. Their $count recording${if (count == 1) "" else "s"} keep their name."
                } else {
                    "Removed."
                }
            )
        }
    }

    class Factory(private val repository: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PeopleViewModel(repository) as T
        }
    }
}

/**
 * @property person The profile.
 * @property recordCount How many recordings are attributed to them.
 */
data class PersonRow(
    val person: PersonEntity,
    val recordCount: Int
)

data class PeopleUiState(
    val people: List<PersonRow> = emptyList(),
    val isEmpty: Boolean = true
)
