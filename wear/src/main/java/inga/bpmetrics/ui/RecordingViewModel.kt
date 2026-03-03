package inga.bpmetrics.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.BPMetricsRepository
import inga.bpmetrics.BpmServiceState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the [RecordingScreen].
 * 
 * Provides UI state derived from the [inga.bpmetrics.BPMetricsRepository] and handles user interactions.
 *
 * @param repository The repository that manages the heart rate data and Health Services state.
 */
class RecordingViewModel(private val repository: BPMetricsRepository) : ViewModel() {

    /**
     * UI state representing the current recording session.
     */
    val uiState: StateFlow<RecordingUIState> = combine(
        repository.liveBpm,
        repository.recordingStartTime,
        repository.serviceState,
    ) { bpm, startTime, state ->
        RecordingUIState(
            bpm = bpm,
            recordingStartTime = startTime,
            serviceState = state,
            statusText = when (state) {
                BpmServiceState.INACTIVE -> "Inactive"
                BpmServiceState.PREPARING -> "Warming up sensor..."
                BpmServiceState.UNAVAILABLE -> "Heart rate unavailable"
                BpmServiceState.ACQUIRING -> "Acquiring heart rate..."
                BpmServiceState.READY -> "Ready to record"
                BpmServiceState.RECORDING -> "Recording..."
                BpmServiceState.PAUSED -> "Paused"
                BpmServiceState.ENDING -> "Saving record..."
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordingUIState(),
    )

    init {
        // Ensure prerequisites (permissions, etc.) are marked as granted
        repository.grantAllPrerequisites()
    }

    /**
     * Signals the repository to start a new heart rate recording.
     */
    fun onStartClicked() {
        repository.startRecording()
    }

    /**
     * Signals the repository to stop the current recording and finalize the session.
     */
    fun onStopClicked() {
        repository.stopRecording()
    }
}

/**
 * Data class representing the UI state of the recording screen.
 *
 * @property bpm The most recent heart rate reading.
 * @property recordingStartTime The start timestamp of the current session.
 * @property serviceState The current [BpmServiceState] of the monitor.
 * @property statusText A human-readable description of the current state.
 */
data class RecordingUIState(
    val bpm: Double? = null,
    val recordingStartTime: Long = 0L,
    val serviceState: BpmServiceState = BpmServiceState.INACTIVE,
    val statusText: String = ""
)
