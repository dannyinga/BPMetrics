package inga.bpmetrics

import android.util.Log
import android.view.Window
import android.view.WindowManager
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
    private val window: Window,
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
        stopRecordingAndSendRecord()
        startExerciseService()
        stopSpotMeasurement()
    }

    fun onStopClicked() {
        stopRecordingAndSendRecord()
        stopExerciseService()
        startSpotMeasurement()
    }

    private fun startSpotMeasurement() {
        spotMeasureManager.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopRecordingAndSendRecord() {
        val record = repository.stopRecording()
        if (record != null)
            viewModelScope.launch {
                syncManager.sendRecordToPhone(record)
            }
    }

    private fun startExerciseService() {
        serviceManager.startAndBind()
        repository.startRecording()
    }

    private fun stopExerciseService() {
        serviceManager.unbindAndStop()
    }

    private fun stopSpotMeasurement() {
        viewModelScope.launch {
            spotMeasureManager.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun onActivityStop() {
        viewModelScope.launch {
            spotMeasureManager.stop()
            Log.d(tag, "Stopping spot measurement")
        }
    }

    fun onActivityResume() {
        viewModelScope.launch {
            if (repository.serviceMode.value == BpmServiceMode.IDLE) {
                startSpotMeasurement()
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