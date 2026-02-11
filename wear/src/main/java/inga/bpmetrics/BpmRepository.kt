package inga.bpmetrics

import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseUpdate
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import kotlinx.coroutines.flow.*
import java.sql.Date

/**
 * The core logic class. Takes input from the service and compiles it into records
 */
class BpmRepository {

    private val tag = "Bpm Repository"

    private val _liveBpm = MutableStateFlow<Double?>(null)
    val liveBpm: StateFlow<Double?> = _liveBpm.asStateFlow()

    private val _spotMeasureState = MutableStateFlow(
        BpmSpotMeasureState.UNAVAILABLE
    )
    val spotMeasureState = _spotMeasureState.asStateFlow()

    private val _serviceMode = MutableStateFlow(
        BpmServiceMode.IDLE
    )
    val serviceMode = _serviceMode.asStateFlow()

    /**
     * Record creation data
     */

    private val dataPoints = mutableListOf<BpmDataPoint>()
    private var startTime: Long = 0L
    private var startTimeFromBoot: Long = 0L

    fun onMeasureAvailabilityChanged(availability: Availability) {
        _spotMeasureState.value = when(availability) {
            DataTypeAvailability.AVAILABLE -> BpmSpotMeasureState.AVAILABLE
            DataTypeAvailability.ACQUIRING -> BpmSpotMeasureState.ACQUIRING
            else -> BpmSpotMeasureState.UNAVAILABLE
        }
    }

    fun onExerciseStarted() {
        _serviceMode.value = BpmServiceMode.RECORDING
        Log.d(tag, "Service mode changed to ${_serviceMode.value}")
    }



    fun onMeasureUpdate(data: DataPointContainer) {
        data.getData(DataType.HEART_RATE_BPM).forEach { point ->
            _liveBpm.value = point.value
        }
    }

    fun onExerciseUpdate(update: ExerciseUpdate) {
        if (update.exerciseStateInfo.state.isEnded) {
            _serviceMode.value = BpmServiceMode.IDLE
            Log.d(tag, "Exercise ended")
        }

        val samples = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
        samples.forEach { point ->
            val timestamp = point.timeDurationFromBoot.toMillis() - startTimeFromBoot
            if (timestamp > 0) {
                val dataPoint = BpmDataPoint(
                    timestamp = timestamp,
                    bpm = point.value
                )
                _liveBpm.value = dataPoint.bpm
                dataPoints.add(dataPoint)
//                Log.d(tag, "Exercise added point. current sample size = ${dataPoints.size}")
            }
        }
    }

    fun onRecordSent() {
        resetDataForNewRecord()
    }

    fun resetDataForNewRecord() {
        dataPoints.clear()
        startTime = System.currentTimeMillis()
        startTimeFromBoot = SystemClock.elapsedRealtime()
    }

    fun startRecording() {
        resetDataForNewRecord()
        _serviceMode.value = BpmServiceMode.WARMING_UP
        Log.d(tag, "Recording started and service mode changed to Recording")
    }

    fun stopRecording(): BpmWatchRecord? {
        _serviceMode.value = BpmServiceMode.IDLE
        return if (dataPoints.isEmpty()) null
        else BpmWatchRecord(
            date = Date(startTime),
            dataPoints = dataPoints.toList().sorted(),
            startTime = startTime,
            endTime = System.currentTimeMillis()
        )

    }

    companion object {
        val instance by lazy { BpmRepository() }
    }
}

enum class BpmSpotMeasureState {
    AVAILABLE, ACQUIRING, UNAVAILABLE
}

enum class BpmServiceMode {
    IDLE, WARMING_UP, RECORDING
}