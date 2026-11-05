package inga.bpmetrics.ui.locations

import inga.bpmetrics.util.launchGuarded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.LocationEntity
import inga.bpmetrics.ui.feedback.Feedback
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The venue registry.
 *
 * Modelled on People for the same reason the entity is: a location is made once and pointed at,
 * so renaming it or correcting its clock reaches every event at that venue rather than needing to
 * be repeated. That is also what makes "the Gorge against Showbox" a comparison of identities.
 */
class LocationsViewModel(private val repository: LibraryRepository) : ViewModel() {

    val uiState: StateFlow<LocationsUiState> = combine(
        repository.getAllLocations(),
        // Observed. These loaded once, before the list had, so every venue showed "0 events" and
        // delete said nothing was using a venue that was in use.
        repository.eventCountsByLocation
    ) { locations, counts ->
        LocationsUiState(
            locations = locations.map {
                LocationRow(location = it, eventCount = counts[it.locationId] ?: 0)
            },
            isEmpty = locations.isEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationsUiState())

    fun addLocation(name: String, timeZoneId: String?) {
        launchGuarded {
            repository.createLocation(name, timeZoneId)
            Feedback.say("${name.trim()} added.")
        }
    }

    fun rename(locationId: Long, name: String) {
        launchGuarded(failure = "Couldn't rename that location.") {
            repository.renameLocation(locationId, name)
        }
    }

    /**
     * Changes the clock a venue keeps.
     *
     * Every recording at it re-reads: the repository reconciles, because the zone a recording is
     * shown in is inherited rather than stored per recording by hand.
     */
    fun setZone(locationId: Long, timeZoneId: String?) {
        launchGuarded {
            repository.setLocationZone(locationId, timeZoneId)
            Feedback.say("Clock updated. Recordings there now read in it.")
        }
    }

    fun setCoordinates(locationId: Long, latitude: Double?, longitude: Double?) {
        launchGuarded {
            repository.setLocationCoordinates(locationId, latitude, longitude)
            Feedback.say(if (latitude == null) "Coordinates cleared." else "Coordinates saved.")
        }
    }

    /**
     * Deletes a venue, offering Undo.
     *
     * The application context, not the screen's: the Undo can run after the screen has gone.
     */
    fun delete(context: android.content.Context, locationId: Long) {
        val app = context.applicationContext
        launchGuarded(failure = "Couldn't delete that location.") {
            val deleted = repository.deleteLocation(app, locationId) ?: return@launchGuarded
            Feedback.undoable(
                "${deleted.location.displayName} deleted. Its events keep their recordings."
            ) {
                repository.restoreLocation(app, deleted)
            }
        }
    }

    class Factory(private val repository: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LocationsViewModel(repository) as T
    }
}

data class LocationsUiState(
    val locations: List<LocationRow> = emptyList(),
    val isEmpty: Boolean = true
)

/**
 * A venue and how much of the library is at it.
 *
 * The count is events rather than recordings: a venue is where an occasion happened, and "4 events"
 * is what tells you whether deleting it matters.
 */
data class LocationRow(
    val location: LocationEntity,
    val eventCount: Int
)
