package inga.bpmetrics.ui.export

import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VideoExportViewModel(
    val record: BpmRecord,
    private val repository: SettingsRepository
) : ViewModel() {

    // --- Persistent States (Collected from DataStore) ---
    val savedWidth = repository.vidWidth.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1280")
    val savedHeight = repository.vidHeight.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "720")
    val savedWindowSize = repository.vidWindowSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "30")
    val savedOpacity = repository.vidOpacity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 40f)
    val savedShowAxes = repository.vidShowAxes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val savedShowLabels = repository.vidShowLabels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val savedShowGrid = repository.vidShowGrid.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val savedShowTitle = repository.vidShowTitle.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val savedShowStats = repository.vidShowStats.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val savedLockAspect = repository.vidLockAspect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val savedSyncOffset = repository.vidSyncOffset.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val savedGraphRect = repository.vidGraphRect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RectF(0f, 0f, 1f, 1f)
    )

    // --- Transient UI States (Modified by the user in the Dialog) ---
    // These would typically be MutableStateFlows if you want to handle all logic here,
    // but for now, we provide the initial values from DataStore.


    /**
     * Persists the current configuration to DataStore.
     * This is called when the user hits 'Export' so their tweaks become
     * the new defaults for the next time they open the app.
     */
    fun saveLastUsedSettings(config: VideoExporter.VideoExportConfig) {
        viewModelScope.launch {
            try {
                repository.setVideoDefaults(config)
            } catch (e: Exception) {
                // Log error or handle failure to save to DataStore
            }
        }
    }

    /**
     * Checks if the recording has enough of a delay at the start to warrant
     * an auto-sync button (e.g., more than 2 seconds).
     */
    fun isAutoSyncAvailable(): Boolean {
        val firstPoint = record.dataPoints.firstOrNull()?.timestamp ?: 0L
        return firstPoint > 2000L
    }

    /**
     * Factory to inject the specific BpmRecord into the ViewModel.
     */
    class Factory(
        private val record: BpmRecord,
        private val repository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(VideoExportViewModel::class.java)) {
                return VideoExportViewModel(record, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
