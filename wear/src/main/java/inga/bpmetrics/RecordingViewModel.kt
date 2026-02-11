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
class RecordingViewModel(
    private val spotMeasureManager: BpmSpotMeasureManager,
    private val serviceManager: BpmServiceManager,
    private val syncManager: BpmSyncManager
) : ViewModel() {
    private val repository = BpmRepository.instance
    private val tag = "RecordingViewModel"

    val uiState = combine(
        repository.liveBpm,
        repository.spotMeasureState,
        repository.serviceMode,
    ) { bpm, spotMeasureState, serviceMode ->
        RecordingUIState(
            bpm = bpm,
            spotMeasureState = spotMeasureState,
            serviceMode = serviceMode,
            statusText =
                when (serviceMode) {
                    BpmServiceMode.IDLE -> when (spotMeasureState) {
                        BpmSpotMeasureState.AVAILABLE -> "Idle"
                        BpmSpotMeasureState.ACQUIRING -> "Acquiring"
                        BpmSpotMeasureState.UNAVAILABLE -> "Unavailable"
                    }

                    BpmServiceMode.WARMING_UP -> "Initializing recording..."
                    BpmServiceMode.RECORDING -> "Recording in progress..."
                }
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        RecordingUIState()
    )

    fun onStartClicked() {
        viewModelScope.launch {
            spotMeasureManager.stop()
        }
        serviceManager.startAndBind()
        repository.startRecording()
    }

    fun onStopClicked() {
        val record = repository.stopRecording()
        if (record != null)
            viewModelScope.launch {
                syncManager.sendRecordToPhone(record)
            }
        serviceManager.unbindAndStop()
        spotMeasureManager.start()
    }

    fun onStop() {
        viewModelScope.launch {
//            if (repository.serviceMode.value == BpmServiceMode.IDLE) {
                spotMeasureManager.stop()
                Log.d(tag, "Stopping spot measurement")
//            }
        }
    }

    fun onResume() {
        viewModelScope.launch {
            if (repository.serviceMode.value == BpmServiceMode.IDLE) {
                spotMeasureManager.start()

                Log.d(tag, "Starting spot measurement")
            }
        }
    }
}

data class RecordingUIState(
    val bpm: Double? = null,
    val spotMeasureState: BpmSpotMeasureState = BpmSpotMeasureState.UNAVAILABLE,
    val serviceMode: BpmServiceMode = BpmServiceMode.IDLE,
    val statusText: String = ""
)