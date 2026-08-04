package inga.bpmetrics.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.recording.RecordingRepository
import inga.bpmetrics.recording.RecordingState
import inga.bpmetrics.recording.SignalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the [RecordingScreen].
 * 
 * Provides UI state derived from the [RecordingRepository] and handles user interactions.
 *
 * @param repository The repository that manages the heart rate data and Health Services state.
 */
class RecordingViewModel(private val repository: RecordingRepository) : ViewModel() {

    val wearerNameState = MutableStateFlow(repository.getWearerName())
    val deviceIdState = MutableStateFlow(repository.getDeviceId())

    fun updateWearerName(name: String?) {
        repository.setWearerName(name)
        wearerNameState.value = repository.getWearerName()
    }

    fun updateDeviceId(id: String) {
        repository.setDeviceId(id)
        deviceIdState.value = repository.getDeviceId()
    }

    /**
     * UI state representing the current recording session.
     */
    val uiState: StateFlow<RecordingUIState> = combine(
        repository.liveBpm,
        repository.recordingStartTime,
        repository.recordingState,
        repository.signalState,
        wearerNameState
    ) { bpm, startTime, state, signal, wearer ->
        RecordingUIState(
            bpm = bpm,
            recordingStartTime = startTime,
            serviceState = state,
            signalState = signal,
            wearerName = wearer,
            deviceId = deviceIdState.value,
            statusText = statusTextFor(state, signal)
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

    private companion object {
        /**
         * Describes the session and the sensor together.
         *
         * While recording, the sensor's state is what the wearer needs to know — a recording
         * keeps running through a dropout, and saying only "Recording..." would hide the fact
         * that nothing is being measured.
         */
        fun statusTextFor(state: RecordingState, signal: SignalState): String = when (state) {
            RecordingState.RECORDING -> when (signal) {
                SignalState.AVAILABLE -> "Recording..."
                SignalState.ACQUIRING -> "Recording · finding pulse"
                SignalState.OFF_BODY -> "Recording · watch off wrist"
                SignalState.UNAVAILABLE -> "Recording · no signal"
                SignalState.UNKNOWN -> "Recording · starting sensor"
            }
            RecordingState.PAUSED -> "Paused"
            RecordingState.ENDING -> "Saving record..."
            RecordingState.PREPARING -> "Warming up sensor..."
            RecordingState.ACQUIRING -> "Acquiring heart rate..."
            RecordingState.READY -> "Ready to record"
            RecordingState.UNAVAILABLE -> when (signal) {
                SignalState.OFF_BODY -> "Watch not on wrist"
                else -> "Heart rate unavailable"
            }
            RecordingState.INACTIVE -> "Tap to start recording"
        }
    }
}

/**
 * Data class representing the UI state of the recording screen.
 *
 * @property bpm The most recent heart rate reading.
 * @property recordingStartTime The start timestamp of the current session.
 * @property serviceState The current [RecordingState] of the monitor.
 * @property statusText A human-readable description of the current state.
 * @property wearerName The configured wearer name.
 * @property deviceId The configured device ID.
 */
data class RecordingUIState(
    val bpm: Double? = null,
    val recordingStartTime: Long = 0L,
    val serviceState: RecordingState = RecordingState.INACTIVE,
    val signalState: SignalState = SignalState.UNKNOWN,
    val statusText: String = "Initializing...",
    val wearerName: String? = null,
    val deviceId: String = "Watch"
) {
    /**
     * Whether the start/stop control accepts a press.
     *
     * Recording no longer waits on the sensor: a session can begin from any state and captures
     * whatever the sensor delivers once it acquires. Only finalizing blocks input, because the
     * previous record is still being written.
     */
    val isControlEnabled: Boolean get() = serviceState != RecordingState.ENDING
}
