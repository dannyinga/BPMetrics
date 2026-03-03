package inga.bpmetrics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseUpdate
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.db.BpmWatchDatabase
import inga.bpmetrics.db.LocalBpmDataPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.sql.Date
import java.time.Duration
import java.time.Instant
import androidx.core.content.edit

/**
 * The core logic repository for the watch application.
 *
 * This class serves as the single source of truth for the recording session. It manages
 * Health Services sensor data, maintains the recording state machine, handles data persistence
 * via Room, and performs clock calibration to ensure precise timestamps across app restarts.
 *
 * @param context The application context used to initialize the database and preferences.
 */
class BPMetricsRepository private constructor(context: Context) {

    private val tag = "BPMetricsRepository"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = BpmWatchDatabase.getInstance(context).bpmWatchDao()
    private val prefs = context.getSharedPreferences("bpm_prefs", Context.MODE_PRIVATE)

    /** Indicates whether all necessary system prerequisites (permissions, capabilities) are met. */
    private val _hasAllPrerequisites = MutableStateFlow(false)
    val hasAllPrerequisites = _hasAllPrerequisites.asStateFlow()

    /** The most recent heart rate value received from the sensors. */
    private val _liveBpm = MutableStateFlow<Double?>(null)
    val liveBpm: StateFlow<Double?> = _liveBpm.asStateFlow()

    /** The current state of the recording service (e.g., RECORDING, READY, ACQUIRING). */
    private val _serviceState = MutableStateFlow(BpmServiceState.INACTIVE)
    val serviceState = _serviceState.asStateFlow()

    /** The final record object created after a session is stopped. */
    private val _currentRecord = MutableStateFlow<BpmWatchRecord?>(null)
    val currentRecord = _currentRecord.asStateFlow()

    /**
     * The boot-time (elapsedRealtime) at which the recording conceptually started.
     * This is used as the base (relative 0) for all data point timestamps.
     */
    private val _recordingStartTime = MutableStateFlow(0L)
    val recordingStartTime = _recordingStartTime.asStateFlow()

    /** The wall-clock start time of the session (UTC), used for the record metadata. */
    private var startTimeWallClock: Long = 0L

    init {
        scope.launch {
            val existingPoints = dao.getAllPoints()
            if (existingPoints.isNotEmpty()) {
                Log.d(tag, "Restored ${existingPoints.size} points from database")
            }
        }
        // Recover state from persistent storage for crash/restart recovery
        startTimeWallClock = prefs.getLong("start_time_ms", 0L)
        _recordingStartTime.value = prefs.getLong("start_time_boot_ms", 0L)
    }

    /**
     * Retrieves the relative timestamp of the very first recorded data point.
     *
     * @return The timestamp in milliseconds, or 0 if no points exist.
     */
    suspend fun getFirstRecordedTimestamp(): Long {
        return dao.getFirstPoint()?.timestamp ?: 0L
    }

    /**
     * Returns the wall-clock start time of the current session.
     */
    fun getPersistedStartTime(): Long = startTimeWallClock

    /**
     * Updates the internal service state based on sensor availability changes.
     *
     * @param availability The new sensor availability from Health Services.
     */
    fun onExerciseAvailabilityChanged(availability: Availability) {
        if (_serviceState.value == BpmServiceState.RECORDING || _serviceState.value == BpmServiceState.ENDING) return

        _serviceState.value = when (availability) {
            DataTypeAvailability.AVAILABLE -> BpmServiceState.READY
            DataTypeAvailability.ACQUIRING -> BpmServiceState.ACQUIRING
            DataTypeAvailability.UNAVAILABLE, DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> BpmServiceState.UNAVAILABLE
            else -> BpmServiceState.PREPARING
        }
    }

    /**
     * Processes updates from the Health Services ExerciseClient.
     *
     * This function handles state transitions (Active, Paused, Ended), performs clock
     * calibration if necessary, and persists incoming heart rate samples to the database.
     *
     * @param update The update containing the latest exercise state and sensor metrics.
     */
    fun onExerciseUpdate(update: ExerciseUpdate) {
        val hsState = update.exerciseStateInfo.state

        val newState = when {
            hsState == ExerciseState.ACTIVE -> BpmServiceState.RECORDING
            hsState.isPaused -> BpmServiceState.PAUSED
            hsState.isEnded -> BpmServiceState.INACTIVE
            else -> _serviceState.value
        }

        // Calibrate the monotonic start time if we don't have a valid one yet
        if (newState == BpmServiceState.RECORDING && _recordingStartTime.value == 0L) {
            update.activeDurationCheckpoint?.let { checkpoint ->
                calibrateClock(checkpoint)
            }
        }

        if (_serviceState.value != newState) {
            Log.d(tag, "Service state updated: $newState (HS State: $hsState)")
            _serviceState.value = newState
        }

        val samples = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
        samples.forEach { point ->
            val bpm = point.value
            _liveBpm.value = if (bpm < 5) null else bpm

            // Only save samples if we are in the RECORDING state and have a valid clock anchor
            if (newState == BpmServiceState.RECORDING && _recordingStartTime.value > 0) {
                if (bpm > 0) {
                    val sampleBootTime = point.timeDurationFromBoot.toMillis()
                    val relativeTimestamp = sampleBootTime - _recordingStartTime.value

                    // Prevent data corruption from negative timestamps due to minor clock jitter
                    if (relativeTimestamp >= 0) {
                        scope.launch {
                            val localPoint = LocalBpmDataPoint(timestamp = relativeTimestamp, bpm = bpm)
                            dao.insert(localPoint)
                        }
                    }
                }
            }
        }
    }

    /**
     * Calibrates the monotonic start time based on the active duration checkpoint.
     *
     * This ensures that sensor boot-time timestamps correctly align with the exercise's
     * active duration, even across app process restarts.
     *
     * @param checkpoint The duration checkpoint from an [ExerciseUpdate].
     */
    private fun calibrateClock(checkpoint: ExerciseUpdate.ActiveDurationCheckpoint) {
        val nowBoot = SystemClock.elapsedRealtime()
        val nowInstant = Instant.now()
        
        // Account for IPC delay between checkpoint capture and repository receipt
        val durationSinceCheckpoint = Duration.between(checkpoint.time, nowInstant)
        val bootTimeOfCheckpoint = nowBoot - durationSinceCheckpoint.toMillis()
        
        val calibratedStart = bootTimeOfCheckpoint - checkpoint.activeDuration.toMillis()
        
        _recordingStartTime.value = calibratedStart
        prefs.edit { putLong("start_time_boot_ms", calibratedStart) }
        
        Log.d(tag, "Clock Calibrated. Start Boot-Time: $calibratedStart")
    }

    /**
     * Prepares the repository for a fresh recording session.
     * Clears previous data and resets start time markers.
     */
    fun startRecording() {
        Log.d(tag, "Requesting startRecording")
        scope.launch {
            dao.deleteAll()
            val nowBoot = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()

            _recordingStartTime.value = nowBoot
            startTimeWallClock = nowWall

            prefs.edit {
                putLong("start_time_ms", nowWall)
                    .putLong("start_time_boot_ms", nowBoot)
            }

            _currentRecord.value = null
            _serviceState.value = BpmServiceState.RECORDING
        }
    }

    /**
     * Resumes a recording session after a process restart.
     * Loads persisted start times and prepares for re-calibration if necessary.
     *
     * @param activeDuration The total duration recorded so far.
     */
    fun resumeRecording(activeDuration: Duration) {
        Log.d(tag, "Resuming recording...")
        
        startTimeWallClock = prefs.getLong("start_time_ms", 0L)
        val persistedBoot = prefs.getLong("start_time_boot_ms", 0L)
        
        // Re-use original boot-time anchor if valid, otherwise trigger calibration
        if (persistedBoot > 0 && persistedBoot <= SystemClock.elapsedRealtime()) {
            _recordingStartTime.value = persistedBoot
        } else {
            _recordingStartTime.value = 0L 
        }

        if (startTimeWallClock == 0L) {
            startTimeWallClock = System.currentTimeMillis() - activeDuration.toMillis()
            prefs.edit().putLong("start_time_ms", startTimeWallClock).apply()
        }
        _serviceState.value = BpmServiceState.RECORDING
    }

    /**
     * Finalizes the current recording session and builds the [BpmWatchRecord].
     */
    fun stopRecording() {
        Log.d(tag, "Requesting stopRecording")
        if (_serviceState.value != BpmServiceState.RECORDING) {
            forceReset()
            return
        }

        _serviceState.value = BpmServiceState.ENDING
        scope.launch {
            val points = dao.getAllPoints().map { BpmDataPoint(it.timestamp, it.bpm) }
            if (points.isNotEmpty()) {
                val endTime = System.currentTimeMillis()
                _currentRecord.value = BpmWatchRecord(
                    date = Date(startTimeWallClock),
                    dataPoints = points.sortedBy { it.timestamp },
                    startTime = startTimeWallClock,
                    endTime = endTime
                )
            }
            dao.deleteAll()
            prefs.edit().remove("start_time_ms").remove("start_time_boot_ms").apply()
            startTimeWallClock = 0L
            _recordingStartTime.value = 0L
        }
    }

    /**
     * Forcefully resets the repository to an inactive state, discarding any unsaved session data.
     */
    fun forceReset() {
        Log.d(tag, "Force resetting repository state")
        _serviceState.value = BpmServiceState.INACTIVE
        _liveBpm.value = null
        _recordingStartTime.value = 0L
        prefs.edit().remove("start_time_ms").remove("start_time_boot_ms").apply()
        startTimeWallClock = 0L
    }

    /**
     * Updates the status to indicate all session prerequisites are granted.
     */
    fun grantAllPrerequisites() {
        _hasAllPrerequisites.value = true
    }

    companion object {
        @Volatile private var INSTANCE: BPMetricsRepository? = null

        /**
         * Returns the singleton instance of [BPMetricsRepository].
         */
        fun getInstance(context: Context): BPMetricsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BPMetricsRepository(context).also { INSTANCE = it }
            }
        }
    }
}

/**
 * Enumeration of the possible states of the heart rate monitoring service.
 */
enum class BpmServiceState {
    /** Service is idle and not monitoring. */
    INACTIVE, 
    /** Initializing sensors and preparing for exercise. */
    PREPARING, 
    /** Device is incapable of heart rate monitoring or sensors are failed. */
    UNAVAILABLE, 
    /** Actively seeking a heart rate signal lock. */
    ACQUIRING, 
    /** Heart rate lock acquired; ready to start recording. */
    READY, 
    /** Actively recording heart rate data. */
    RECORDING, 
    /** Session is paused. */
    PAUSED, 
    /** Session has ended and is being finalized. */
    ENDING
}
