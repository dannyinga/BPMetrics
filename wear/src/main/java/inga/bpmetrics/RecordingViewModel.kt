package inga.bpmetrics

import android.view.Window
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Gets the state from the repository for the UI
 */
class RecordingViewModel() : ViewModel() {
    private val repository = BPMetricsRepository.instance
    private val tag = "RecordingViewModel"

    val uiState = combine(
        repository.liveBpm,
        repository.exerciseDuration,
        repository.serviceState,
    ) { bpm, exerciseDuration, serviceState ->
        RecordingUIState(
            bpm = bpm,
            exerciseDuration = exerciseDuration,
            serviceState = serviceState,
            statusText =
                when (serviceState) {
                    BpmServiceState.RECORDING -> "Recording..."
                    BpmServiceState.PREPARING -> "Acquiring heart rate..."
                    BpmServiceState.READY -> "Ready to record"
                    BpmServiceState.ENDING -> "Saving record"
                    BpmServiceState.ASLEEP -> "Inactive"
                }
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        RecordingUIState(),
    )

    init {
        if (repository.serviceState.value != BpmServiceState.RECORDING)
            repository.resetService()
    }

    fun onStartClicked() {
        repository.startRecording()
    }

    fun onStopClicked() {
        repository.stopRecording()
    }
}

data class RecordingUIState(
    val bpm: Double? = null,
    val exerciseDuration: Long = 0,
    val serviceState: BpmServiceState = BpmServiceState.ASLEEP,
    val statusText: String = ""
)