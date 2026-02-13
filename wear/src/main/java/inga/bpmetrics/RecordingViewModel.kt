package inga.bpmetrics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Gets the state from the repository for the UI
 */
class RecordingViewModel() : ViewModel() {
    private val repository = BPMetricsRepository.instance
    private val tag = "RecordingViewModel"

    val uiState = combine(
        repository.liveBpm,
        repository.recordingStartTime,
        repository.serviceState,
    ) { bpm, recordingStartTime, serviceState ->
        RecordingUIState(
            bpm = bpm,
            recordingStartTime = recordingStartTime,
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
        repository.grantAllPrerequisites()
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
    val recordingStartTime: Long = 0L,
    val serviceState: BpmServiceState = BpmServiceState.ASLEEP,
    val statusText: String = ""
)