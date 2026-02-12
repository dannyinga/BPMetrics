package inga.bpmetrics

import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.data.Availability
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
class BPMetricsRepository {

    private val tag = "Bpm Repository"

    private val _hasExerciseCapabilities = MutableStateFlow<Boolean>(false)
    val hasExerciseCapabilities = _hasExerciseCapabilities.asStateFlow()
    private val _liveBpm = MutableStateFlow<Double?>(null)
    val liveBpm: StateFlow<Double?> = _liveBpm.asStateFlow()
    private val _exerciseDuration = MutableStateFlow<Long>(0)
    val exerciseDuration: StateFlow<Long> = _exerciseDuration.asStateFlow()

    private val _serviceState = MutableStateFlow(
        BpmServiceState.ASLEEP
    )
    val serviceState = _serviceState.asStateFlow()

    private val _currentRecord = MutableStateFlow<BpmWatchRecord?>(null)
    val currentRecord = _currentRecord.asStateFlow()


    /**
     * Record creation data
     */

    private val dataPoints = mutableListOf<BpmDataPoint>()
    private var startTime: Long = 0L
    private var startTimeFromBoot: Long = 0L

    fun onExerciseAvailabilityChanged(availability: Availability) {
        _serviceState.value =
            when (availability) {
                DataTypeAvailability.AVAILABLE -> BpmServiceState.READY
                DataTypeAvailability.ACQUIRING -> BpmServiceState.PREPARING
                else -> BpmServiceState.ASLEEP
            }
        Log.d(tag, "Service state changed to ${_serviceState.value}")
    }

    fun onExerciseUpdate(update: ExerciseUpdate) {
        if (update.exerciseStateInfo.state == ExerciseState.USER_STARTING) {
            resetDataForNewRecord()
        }


        if (update.exerciseStateInfo.state.isEnded) {
            Log.d(tag, "Service state changing to ASLEEP")
            _serviceState.value = BpmServiceState.ASLEEP
        }

        val samples = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
        samples.forEach { point ->
            _liveBpm.value = point.value
            val timestamp = point.timeDurationFromBoot.toMillis() - startTimeFromBoot
            if (_serviceState.value == BpmServiceState.RECORDING && timestamp > 0) {
                _exerciseDuration.value = timestamp

                val dataPoint = BpmDataPoint(
                    timestamp = timestamp,
                    bpm = point.value
                )
                dataPoints.add(dataPoint)
                Log.d(tag, "Exercise added point. current sample size = ${dataPoints.size}")
            }
        }
    }

    fun startRecording() {
        Log.d(tag, "Service state changing to RECORDING")
        _serviceState.value = BpmServiceState.RECORDING
    }

    fun stopRecording()  {
        Log.d(tag, "Service state changing to ENDING")
        _serviceState.value = BpmServiceState.ENDING
        generateRecord()
    }

    private fun resetDataForNewRecord() {
        dataPoints.clear()
        _exerciseDuration.value = 0
        startTime = System.currentTimeMillis()
        startTimeFromBoot = SystemClock.elapsedRealtime()
    }

    private fun generateRecord(){
        _currentRecord.value =
            if (dataPoints.isEmpty())
                null
            else
                BpmWatchRecord(
            date = Date(startTime),
            dataPoints = dataPoints.toList().sorted(),
            startTime = startTime,
            endTime = System.currentTimeMillis()
        )
    }

    companion object {
        val instance by lazy { BPMetricsRepository() }
    }
}

enum class BpmServiceState {
    ASLEEP, PREPARING, READY, RECORDING, ENDING
}