package inga.bpmetrics.ui.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.library.LibraryRepository
import inga.bpmetrics.library.LocationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The venue registry.
 *
 * Modelled on People for the same reason the entity is: a location is made once and pointed at,
 * so renaming it or correcting its clock reaches every event at that venue rather than needing to
 * be repeated. That is also what makes "the Gorge against Showbox" a comparison of identities.
 */
class LocationsViewModel(private val repository: LibraryRepository) : ViewModel() {

    private val _eventCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<LocationsUiState> = combine(
        repository.getAllLocations(),
        _eventCounts
    ) { locations, counts ->
        LocationsUiState(
            locations = locations.map {
                LocationRow(location = it, eventCount = counts[it.locationId] ?: 0)
            },
            isEmpty = locations.isEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationsUiState())

    init {
        refreshCounts()
    }

    /** A per-location query, so refreshed rather than observed — as with People. */
    fun refreshCounts() {
        viewModelScope.launch {
            val ids = uiState.value.locations.map { it.location.locationId }
            if (ids.isEmpty()) return@launch
            _eventCounts.value = ids.associateWith { repository.countEventsAt(it) }
        }
    }

    fun addLocation(name: String, timeZoneId: String?) {
        viewModelScope.launch {
            repository.createLocation(name, timeZoneId)
            refreshCounts()
            _message.value = "${name.trim()} added."
        }
    }

    fun rename(locationId: Long, name: String) {
        viewModelScope.launch { repository.renameLocation(locationId, name) }
    }

    /**
     * Changes the clock a venue keeps.
     *
     * Every recording at it re-reads: the repository reconciles, because the zone a recording is
     * shown in is inherited rather than stored per recording by hand.
     */
    fun setZone(locationId: Long, timeZoneId: String?) {
        viewModelScope.launch {
            repository.setLocationZone(locationId, timeZoneId)
            _message.value = "Clock updated. Recordings there now read in it."
        }
    }

    fun setCoordinates(locationId: Long, latitude: Double?, longitude: Double?) {
        viewModelScope.launch {
            repository.setLocationCoordinates(locationId, latitude, longitude)
            _message.value = if (latitude == null) "Coordinates cleared." else "Coordinates saved."
        }
    }

    fun delete(context: android.content.Context, locationId: Long) {
        viewModelScope.launch {
            repository.deleteLocation(context, locationId)
            refreshCounts()
            _message.value = "Deleted. The events that were there keep their recordings."
        }
    }

    fun clearMessage() { _message.value = null }

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
