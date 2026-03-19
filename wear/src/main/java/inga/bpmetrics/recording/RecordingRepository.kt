package inga.bpmetrics.recording

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.SampleDataPoint
import com.google.gson.Gson
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.db.RecordingDB
import inga.bpmetrics.db.LocalBpmDataPoint
import inga.bpmetrics.db.PendingRecordEntity
import inga.bpmetrics.health.ExerciseClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.sql.Date
import java.time.Duration

/**
 * The high-level orchestrator for the BPMetrics application.
 *
 * This repository coordinates between Health Services (via [ExerciseClientManager]),
 * the database, and the UI. It handles session lifecycle, persistence of heart rate samples,
 * and reliable synchronization of completed records.
 *
 * It uses a Single Source of Truth (SSoT) pattern to manage the [RecordingState]
 * by combining raw sensor data with user intent.
 */
class RecordingRepository private constructor(context: Context) {

    private val tag = "RecordingRepository"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = RecordingDB.getInstance(context).bpmWatchDao()
    private val prefs = context.getSharedPreferences("bpm_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val exerciseClientManager = ExerciseClientManager(context)

    // Tracks explicit user intents that haven't been reflected by Health Services yet (e.g., stopping)
    private val userIntentState = MutableStateFlow<RecordingState?>(null)

    /**
     * The Single Source of Truth for the current recording state.
     * Derived by combining Health Services state, sensor availability, and user intent.
     *
     * Exposed as a [StateFlow] for UI observation and foreground service lifecycle management.
     */
    val recordingState: StateFlow<RecordingState> = combine(
        exerciseClientManager.exerciseUpdate,
        exerciseClientManager.availability,
        userIntentState
    ) { update, availability, intent ->
        // 1. Explicit user intent (like ENDING) takes priority during transitions
        if (intent == RecordingState.ENDING) return@combine RecordingState.ENDING

        val hsState = update?.exerciseStateInfo?.state
        
        // Resilience: Check if we actually have data in the latest update.
        // This acts as a fallback if the system availability signal is delayed or stuck.
        val heartRateSamples = update?.latestMetrics?.getData(DataType.HEART_RATE_BPM)
        val hasRecentData = heartRateSamples?.any { it.value > 5 } == true

        when {
            hsState == ExerciseState.ACTIVE -> RecordingState.RECORDING
            hsState?.isPaused == true -> RecordingState.PAUSED
            hsState?.isEnded == true -> RecordingState.INACTIVE

            // If we are receiving valid data, the sensor is READY regardless of the availability flag.
            hasRecentData || availability == DataTypeAvailability.AVAILABLE -> RecordingState.READY
            
            availability == DataTypeAvailability.ACQUIRING -> RecordingState.ACQUIRING
            
            availability == DataTypeAvailability.UNAVAILABLE ||
                    availability == DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> RecordingState.UNAVAILABLE

            // We default to INACTIVE instead of PREPARING to avoid the "stuck" race condition.
            else -> RecordingState.INACTIVE
        }
    }.stateIn(scope, SharingStarted.Eagerly, RecordingState.INACTIVE)

    private val _liveBpm = MutableStateFlow<Double?>(null)
    /**
     * The most recent valid heart rate value received from sensors.
     * Values below 5 BPM are considered invalid and emitted as null.
     */
    val liveBpm: StateFlow<Double?> = _liveBpm.asStateFlow()

    private val _hasAllPrerequisites = MutableStateFlow(false)
    /**
     * Indicates whether all system prerequisites (permissions, capabilities) are met.
     */
    val hasAllPrerequisites = _hasAllPrerequisites.asStateFlow()

    private val _recordingStartTimeBoot = MutableStateFlow(0L)
    /**
     * The monotonic boot-time (elapsedRealtime) anchor representing the exact moment 
     * recording started according to the user's intent. 
     *
     * This anchor is used to calculate relative timestamps for all data points, 
     * ensuring perfect synchronization with external media (like video).
     */
    val recordingStartTime = _recordingStartTimeBoot.asStateFlow()

    private var startTimeWallClock: Long = 0L

    init {
        // Restore state from persistent storage for recovery after process death
        startTimeWallClock = prefs.getLong("start_time_ms", 0L)
        _recordingStartTimeBoot.value = prefs.getLong("start_time_boot_ms", 0L)

        observeExerciseData()
    }

    /**
     * Internal method to subscribe to flows from [ExerciseClientManager].
     * Coordinates heart rate persistence and session finalization.
     */
    private fun observeExerciseData() {
        exerciseClientManager.exerciseUpdate
            .filterNotNull()
            .onEach { update ->
                val hsState = update.exerciseStateInfo.state

                // 1. Process samples (even in ENDING state to catch final buffer)
                val samples = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
                processSamples(samples)

                // 2. Check for session termination to finalize the record
                // This ensures Health Services is the source of truth for the end of the session.
                if (hsState.isEnded && startTimeWallClock != 0L) {
                    finalizeAndCleanup()
                }

            }
            .launchIn(scope)
    }

    /**
     * Processes raw heart rate samples and persists them if a recording is active.
     * Timestamps are calculated relative to the user-intent anchor.
     * 
     * @param samples The list of heart rate samples from Health Services.
     */
    private suspend fun processSamples(samples: List<SampleDataPoint<Double>>) {
        val currentState = recordingState.value
        val anchor = _recordingStartTimeBoot.value
        
        // Save samples if recording is active or transitioning to ending
        val shouldSave = currentState == RecordingState.RECORDING || currentState == RecordingState.ENDING

        // Update live display only if this batch contains data.
        // This prevents the UI from flickering to null during empty batch updates or start-up.
        samples.lastOrNull()?.value?.let { lastValue ->
            _liveBpm.value = if (lastValue > 5) lastValue else null
        }

        samples.forEach { point ->
            val bpm = point.value

            if (shouldSave && bpm > 0 && anchor > 0) {
                val sampleBootTime = point.timeDurationFromBoot.toMillis()
                
                // Calculate timestamp relative to the user-intent anchor.
                // Points captured BEFORE the start command (buffered) will have negative timestamps,
                // so we coerce to 0 to align with the start of the video.
                val relativeTimestamp = (sampleBootTime - anchor).coerceAtLeast(0L)
                dao.insert(LocalBpmDataPoint(timestamp = relativeTimestamp, bpm = bpm))
            }
        }
    }

    /**
     * Begins a new recording session. 
     * 
     * Clears previous temporary data and sets the absolute start anchor at this exact moment.
     * This anchor is used for video-sync precision.
     */
    fun startRecording() {
        Log.d(tag, "startRecording requested")
        userIntentState.value = null
        scope.launch {
            dao.deleteAll()
            
            // CAPTURE ANCHORS IMMEDIATELY
            val nowBoot = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()

            _recordingStartTimeBoot.value = nowBoot
            startTimeWallClock = nowWall

            prefs.edit {
                putLong("start_time_ms", nowWall)
                putLong("start_time_boot_ms", nowBoot)
            }
            
            Log.d(tag, "Recording started by user at Boot:$nowBoot Wall:$nowWall")
            exerciseClientManager.startExercise()
        }
    }

    /**
     * Restores internal timing markers for an ongoing session after process restart.
     * 
     * @param activeDuration The total duration recorded so far according to Health Services.
     */
    fun resumeRecording(activeDuration: Duration) {
        Log.d(tag, "Resuming recording state")
        startTimeWallClock = prefs.getLong("start_time_ms", 0L)
        val persistedBoot = prefs.getLong("start_time_boot_ms", 0L)
        
        if (persistedBoot > 0) {
            _recordingStartTimeBoot.value = persistedBoot
        }
        
        if (startTimeWallClock == 0L) {
            startTimeWallClock = System.currentTimeMillis() - activeDuration.toMillis()
            prefs.edit { putLong("start_time_ms", startTimeWallClock) }
        }
    }

    /**
     * Requests termination of the recording session. 
     * 
     * Transitions to [RecordingState.ENDING] immediately and tells Health Services to stop.
     * Record finalization is handled asynchronously once Health Services confirms the end.
     */
    fun stopRecording() {
        Log.d(tag, "stopRecording requested")
        if (startTimeWallClock == 0L) {
            forceReset()
            return
        }

        // Set intent to ENDING so recordingState updates immediately for UI/Service
        userIntentState.value = RecordingState.ENDING

        scope.launch {
            // Signal Health Services to end. Observer handles the "isEnded" transition.
            exerciseClientManager.endExercise()
        }
    }

    /**
     * Aggregates collected data points into a final record, saves it to the sync outbox, 
     * and resets transient state for the next session.
     */
    private fun finalizeAndCleanup() {
        scope.launch {
            val wallClock = startTimeWallClock
            if (wallClock == 0L) return@launch

            // Clear markers immediately to prevent duplicate finalization
            startTimeWallClock = 0L

            val points = dao.getAllPoints().map { BpmDataPoint(it.timestamp, it.bpm) }
            if (points.isNotEmpty()) {
                val endTime = System.currentTimeMillis()
                val record = BpmWatchRecord(
                    date = Date(wallClock),
                    dataPoints = points.sortedBy { it.timestamp },
                    startTime = wallClock,
                    endTime = endTime
                )
                // Save to persistent "outbox" for reliable synchronization to the phone
                dao.insertPendingRecord(PendingRecordEntity(recordJson = gson.toJson(record)))
                Log.d(tag, "Record finalized with ${points.size} points starting at $wallClock")
            }
            
            cleanupSession()
            
            // Per requirement: Re-warm sensors for the next session
            prepareExercise()
        }
    }

    /**
     * Internal cleanup of session-specific transient state and preferences.
     */
    private suspend fun cleanupSession() {
        dao.deleteAll()
        prefs.edit { remove("start_time_ms").remove("remove_start_time_boot_ms") }
        startTimeWallClock = 0L
        _recordingStartTimeBoot.value = 0L
        _liveBpm.value = null
        userIntentState.value = null
    }

    /**
     * Forcefully ends any active session and wipes transient data.
     */
    fun forceReset() {
        Log.d(tag, "forceReset requested")
        scope.launch {
            exerciseClientManager.endExercise()
            cleanupSession()
            prepareExercise()
        }
    }

    /**
     * Signals the [ExerciseClientManager] to begin sensor warm-up.
     */
    fun prepareExercise() {
        Log.d(tag, "prepareExercise() called")
        scope.launch { exerciseClientManager.prepareExercise() }
    }

    /**
     * Requests the [ExerciseClientManager] to pause the active exercise session.
     */
    fun pauseExercise() {
        Log.d(tag, "pauseExercise requested")
        scope.launch { exerciseClientManager.pauseExercise() }
    }

    /**
     * Queries current exercise info from Health Services.
     * Used for session recovery after a process restart.
     */
    suspend fun getCurrentExerciseInfo() = exerciseClientManager.getCurrentExerciseInfo()

    /**
     * Signals that all prerequisites are ready.
     */
    fun grantAllPrerequisites() {
        _hasAllPrerequisites.value = true
    }

    /**
     * Gets the wall clock start time of the current session.
     */
    fun getPersistedStartTime(): Long = prefs.getLong("start_time_ms", 0L)

    /**
     * Provides a flow of all records currently waiting to be synchronized with the phone.
     */
    fun getPendingRecordsFlow(): Flow<List<PendingRecordEntity>> = dao.getAllPendingRecordsFlow()

    /**
     * Removes a record from the persistent outbox once synchronization is confirmed.
     */
    suspend fun removePendingRecord(entity: PendingRecordEntity) = dao.deletePendingRecord(entity)

    companion object {
        @Volatile
        private var instance: RecordingRepository? = null

        fun getInstance(context: Context): RecordingRepository {
            return instance ?: synchronized(this) {
                instance ?: RecordingRepository(context).also { instance = it }
            }
        }
    }
}
