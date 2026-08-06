package inga.bpmetrics.recording

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus.Companion.OWNED_EXERCISE_IN_PROGRESS
import androidx.health.services.client.data.SampleDataPoint
import inga.bpmetrics.core.BpmDataPoint
import inga.bpmetrics.core.BpmGson
import inga.bpmetrics.core.BpmWatchRecord
import inga.bpmetrics.db.RecordingDB
import inga.bpmetrics.db.LocalBpmDataPoint
import inga.bpmetrics.db.PendingRecordEntity
import inga.bpmetrics.health.ExerciseClientManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    /**
     * Keeps a failure in one background task from taking down the process.
     *
     * Without this, an exception thrown while finalizing a recording propagates to the default
     * uncaught handler and crashes the watch app, losing the session with no diagnostic.
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Unhandled failure in recording scope", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val dao = RecordingDB.getInstance(context).bpmWatchDao()
    private val prefs = context.getSharedPreferences("bpm_prefs", Context.MODE_PRIVATE)

    /**
     * Shared serializer. Must match the one [inga.bpmetrics.sync.PhoneSyncManager] reads the
     * outbox with — a plain [com.google.gson.Gson] writes [java.sql.Date] as a locale-formatted
     * string that the shared deserializer cannot parse, silently replacing every record's date.
     */
    private val gson = BpmGson.instance

    private val exerciseClientManager = ExerciseClientManager(context)

    // Tracks explicit user intents that haven't been reflected by Health Services yet (e.g., stopping)
    private val userIntentState = MutableStateFlow<RecordingState?>(null)

    /**
     * True from the moment the user presses start until the record has been finalized.
     *
     * This — not the Health Services state — decides whether samples are kept. Health Services
     * can take seconds to reach [ExerciseState.ACTIVE], and everything measured in the meantime
     * used to be thrown away, which is what forced the wait for a signal lock before starting.
     */
    private val _sessionActive = MutableStateFlow(false)

    /**
     * Whether a recording is open, as the user understands it: true from the press of start until
     * the record has been written.
     *
     * The foreground service decides whether it may shut down by reading this. It must not read
     * [recordingState] for that: that flow is a `stateIn` whose value is [RecordingState.INACTIVE]
     * until its first emission, so a consumer reading it during startup is told nothing is
     * happening even mid-recording. This is a plain flag, written before anything can suspend.
     */
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    /**
     * How many times Health Services has ended the exercise out from under an open session.
     *
     * Reset once the exercise is running again, so the cap applies to repeated failures rather
     * than to a long recording that was interrupted once an hour.
     */
    private var autoRestartAttempts = 0

    @Volatile
    private var finalizing = false

    /** Whether a record is being written right now, so a watchdog does not reset over the top. */
    val isFinalizing: Boolean get() = finalizing

    /**
     * What the sensor is doing right now.
     *
     * Derived purely from the live availability callback. It deliberately ignores buffered
     * readings: the previous implementation treated any recent sample as proof of a lock, so a
     * stale batch kept the indicator on "Ready" long after the sensor had actually dropped out.
     */
    val signalState: StateFlow<SignalState> = exerciseClientManager.availability
        .map { availability ->
            when (availability) {
                DataTypeAvailability.AVAILABLE -> SignalState.AVAILABLE
                DataTypeAvailability.ACQUIRING -> SignalState.ACQUIRING
                DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> SignalState.OFF_BODY
                DataTypeAvailability.UNAVAILABLE -> SignalState.UNAVAILABLE
                else -> SignalState.UNKNOWN
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, SignalState.UNKNOWN)

    /**
     * The Single Source of Truth for the current recording state.
     *
     * Reports the *session*: whether a recording is running, ending, or idle. What the sensor is
     * doing is reported separately by [signalState], because a recording legitimately continues
     * through a dropout and one value cannot describe both.
     *
     * Exposed as a [StateFlow] for UI observation and foreground service lifecycle management.
     */
    val recordingState: StateFlow<RecordingState> = combine(
        exerciseClientManager.exerciseUpdate,
        signalState,
        userIntentState,
        _sessionActive
    ) { update, signal, intent, sessionActive ->
        val hsState = update?.exerciseStateInfo?.state

        when {
            // Explicit user intent takes priority during transitions.
            intent == RecordingState.ENDING -> RecordingState.ENDING

            // An open session is reported as recording from the instant the user pressed start,
            // because that is when samples start being kept.
            sessionActive && hsState?.isPaused == true -> RecordingState.PAUSED
            sessionActive -> RecordingState.RECORDING

            // A session Health Services owns that we did not open — e.g. recovered after a restart.
            hsState == ExerciseState.ACTIVE -> RecordingState.RECORDING
            hsState?.isPaused == true -> RecordingState.PAUSED

            // Idle: report the sensor, so the indicator tracks it while nothing is recording.
            signal == SignalState.AVAILABLE -> RecordingState.READY
            signal == SignalState.ACQUIRING -> RecordingState.ACQUIRING
            signal == SignalState.UNAVAILABLE || signal == SignalState.OFF_BODY -> RecordingState.UNAVAILABLE

            // Warm-up requested but the sensor has not reported in yet.
            hsState == ExerciseState.PREPARING || hsState == ExerciseState.USER_STARTING ->
                RecordingState.PREPARING

            else -> RecordingState.INACTIVE
        }
    }.stateIn(scope, SharingStarted.Eagerly, RecordingState.INACTIVE)

    private val _liveBpm = MutableStateFlow<Double?>(null)
    /**
     * The most recent valid heart rate value received from sensors.
     * Values below 5 BPM are considered invalid and emitted as null.
     */
    val liveBpm: StateFlow<Double?> = _liveBpm.asStateFlow()

    /**
     * How many finished recordings are waiting to reach the phone.
     *
     * Surfaced so the wearer knows what is still on the wrist — after an event away from the
     * phone, this is the only way to tell how many recordings to expect once they reconnect.
     */
    val pendingRecordCount: StateFlow<Int> = dao.getAllPendingRecordsFlow()
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

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
        if (bootAnchorSurvivedReboot()) {
            startTimeWallClock = prefs.getLong("start_time_ms", 0L)
            _recordingStartTimeBoot.value = prefs.getLong("start_time_boot_ms", 0L)
            _sessionActive.value = startTimeWallClock != 0L
        } else {
            // The anchor is meaningless after a reboot, but the readings already in the table are
            // not — their timestamps were worked out relative to the anchor while it was still
            // valid. Writing them out keeps a session that was interrupted by a restart, rather
            // than discarding someone's evening because the watch rebooted during it.
            startTimeWallClock = prefs.getLong("start_time_ms", 0L)
            if (startTimeWallClock != 0L) {
                Log.w(tag, "Boot anchor predates a reboot; salvaging the interrupted session")
                finalizeAndCleanup()
            } else {
                prefs.edit { remove("start_time_ms").remove("start_time_boot_ms").remove(KEY_BOOT_EPOCH) }
            }
        }

        observeExerciseData()
        observeSignalLoss()
    }

    /**
     * Whether a persisted boot anchor is still meaningful.
     *
     * [SystemClock.elapsedRealtime] restarts at zero when the watch reboots, so an anchor saved
     * before a reboot would place every subsequent sample wildly wrong — and because negative
     * offsets are clamped, the whole recording would collapse onto timestamp zero. Recording the
     * boot epoch alongside the anchor lets a restart tell a reboot apart from ordinary drift.
     */
    private fun bootAnchorSurvivedReboot(): Boolean {
        val persistedBootEpoch = prefs.getLong(KEY_BOOT_EPOCH, 0L)
        if (persistedBootEpoch == 0L) return true
        return kotlin.math.abs(currentBootEpochMs() - persistedBootEpoch) <= BOOT_EPOCH_TOLERANCE_MS
    }

    /** The wall clock instant this device booted, as implied by the two clocks. */
    private fun currentBootEpochMs(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * Blanks the live reading as soon as the sensor stops producing one.
     *
     * Without this the last value measured stays on screen indefinitely after the watch is taken
     * off, which reads as a live heart rate when there is none.
     */
    private fun observeSignalLoss() {
        signalState
            .onEach { signal -> if (!signal.hasSignal) _liveBpm.value = null }
            .launchIn(scope)
    }

    /**
     * Internal method to subscribe to flows from [ExerciseClientManager].
     * Coordinates heart rate persistence and session finalization.
     */
    private fun observeExerciseData() {
        exerciseClientManager.exerciseUpdate
            .filterNotNull()
            .onEach { update ->
                val stateInfo = update.exerciseStateInfo
                val hsState = stateInfo.state

                // 1. Process samples (even in ENDING state to catch final buffer)
                val samples = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
                processSamples(samples)

                if (!hsState.isEnded) {
                    // Running again, so an earlier interruption is water under the bridge.
                    autoRestartAttempts = 0
                    return@onEach
                }

                // 2. An ended exercise only ends the *session* if the user asked for it.
                //
                // Health Services ends an exercise for reasons of its own: another app starting
                // a workout supersedes ours, the callback registration lapses, a permission is
                // withdrawn. Treating any of those as "the recording is over" is what let a
                // session stop on its own — the wearer found out only when they next looked at
                // the watch. A recording now ends when the button is pressed, and at no other
                // time.
                val userAskedToStop = userIntentState.value == RecordingState.ENDING
                if (userAskedToStop || !_sessionActive.value) {
                    if (startTimeWallClock != 0L) finalizeAndCleanup()
                    return@onEach
                }

                restartInterruptedExercise(stateInfo)
            }
            .launchIn(scope)
    }

    /**
     * Puts Health Services back to work after it ended an exercise the user never stopped.
     *
     * The session's anchors are left alone, so the readings either side of the interruption stay
     * on one timeline and the gap reads as exactly what it was — a dropout — rather than splitting
     * the evening into two recordings.
     *
     * Bounded, because some end reasons do not recover: a withdrawn body-sensors permission will
     * refuse every restart, and retrying forever would keep the sensor thrashing and never write
     * the readings already collected. After the cap it saves what it has, which is the outcome the
     * old code produced immediately.
     */
    private suspend fun restartInterruptedExercise(
        stateInfo: androidx.health.services.client.data.ExerciseStateInfo
    ) {
        if (autoRestartAttempts >= MAX_AUTO_RESTARTS) {
            Log.e(tag, "Exercise ended $autoRestartAttempts times (${stateInfo.endReason}); saving what we have")
            if (startTimeWallClock != 0L) finalizeAndCleanup()
            return
        }

        autoRestartAttempts++
        Log.w(
            tag,
            "Health Services ended the exercise (${stateInfo.endReason}) with a session open; " +
                    "restarting, attempt $autoRestartAttempts"
        )
        delay(AUTO_RESTART_BACKOFF_MS * autoRestartAttempts)
        exerciseClientManager.startExercise()
    }

    /**
     * Puts Health Services back to work on a session that outlived the process that opened it.
     *
     * A recording survives in the preferences and the points table, so after the app is killed the
     * session is still open as far as the wearer is concerned — but nothing is measuring. Called
     * by the foreground service once it is up.
     */
    suspend fun resumeInterruptedSessionIfNeeded() {
        if (!_sessionActive.value) return
        val info = exerciseClientManager.getCurrentExerciseInfo()
        if (info.exerciseTrackedStatus != OWNED_EXERCISE_IN_PROGRESS) {
            Log.w(tag, "Session open but Health Services is not tracking it; restarting the exercise")
            exerciseClientManager.startExercise()
        }
    }

    /**
     * Processes raw heart rate samples and persists them if a recording is active.
     * Timestamps are calculated relative to the user-intent anchor.
     * 
     * @param samples The list of heart rate samples from Health Services.
     */
    private suspend fun processSamples(samples: List<SampleDataPoint<Double>>) {
        // Update live display only if this batch contains data.
        // This prevents the UI from flickering to null during empty batch updates or start-up.
        samples.lastOrNull()?.value?.let { lastValue ->
            _liveBpm.value = if (lastValue > 5) lastValue else null
        }

        // Capture is gated on the user having pressed start, not on Health Services reaching
        // ACTIVE. Waiting for the sensor to report a lock is what made starting a recording
        // feel like it needed permission from the watch.
        val anchor = _recordingStartTimeBoot.value
        if (!_sessionActive.value || anchor <= 0L) return

        val points = samples.mapNotNull { point ->
            val bpm = point.value
            if (bpm <= 0) return@mapNotNull null

            // Drop readings the record model would reject. Optical sensors emit implausible
            // spikes during motion; letting one through means the whole session fails to
            // finalize later, when it is far too late to recover it.
            if (!BpmDataPoint.isValidBpm(bpm)) {
                Log.w(tag, "Discarding out-of-range heart rate sample: $bpm")
                return@mapNotNull null
            }

            // Health Services replays samples it buffered during warm-up, which carry stamps from
            // before the user pressed start. Those from the moments just before are genuinely part
            // of this session and are pinned to zero; anything older belongs to a previous one.
            val relativeTimestamp = point.timeDurationFromBoot.toMillis() - anchor
            if (relativeTimestamp < -PRE_START_GRACE_MS) return@mapNotNull null

            LocalBpmDataPoint(timestamp = relativeTimestamp.coerceAtLeast(0L), bpm = bpm)
        }

        // One transaction per batch rather than per sample: Health Services hands over roughly a
        // sample a second, and a 30 minute recording is otherwise ~1800 separate disk writes.
        if (points.isNotEmpty()) dao.insertAll(points)
    }

    /**
     * Begins a new recording session. 
     * 
     * Clears previous temporary data and sets the absolute start anchor at this exact moment.
     * This anchor is used for video-sync precision.
     */
    fun startRecording() {
        Log.d(tag, "startRecording requested")

        // Stamp the anchors on the caller's thread so they mark the press itself, not whenever
        // the background work happens to get scheduled. Everything else can be asynchronous;
        // this cannot, because it is what every sample's timestamp is measured against.
        val nowBoot = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        userIntentState.value = null
        autoRestartAttempts = 0

        // Mark the session open here, before anything can suspend. The foreground service and its
        // manager both decide whether they may shut down by reading this, and while it was set on
        // a background coroutine there was a window — right after the press — in which the watch
        // screen turning off would tear the service down and take the recording with it.
        _sessionActive.value = true

        scope.launch {
            // Clear leftovers before the anchor is set, so a sample can never land in the old
            // data. Capture stays gated on the anchor, so nothing is kept until this is done —
            // and Health Services replays the warm-up buffer anyway, so the opening seconds of
            // the recording survive the gap.
            dao.deleteAll()

            _recordingStartTimeBoot.value = nowBoot
            startTimeWallClock = nowWall

            prefs.edit {
                putLong("start_time_ms", nowWall)
                putLong("start_time_boot_ms", nowBoot)
                putLong(KEY_BOOT_EPOCH, nowWall - nowBoot)
            }

            Log.d(tag, "Recording started by user at Boot:$nowBoot Wall:$nowWall")

            // Starting does not require a completed warm-up; Health Services delivers samples as
            // soon as the sensor acquires, and they are already being kept by then.
            exerciseClientManager.startExercise()
        }
    }

    /**
     * Powers the sensor down if nothing is recording.
     *
     * A warm-up stays lit until something ends it, so one left behind when the app closes drains
     * the battery for as long as the process survives. An open session is never interrupted.
     */
    fun releaseSensorsIfIdle() {
        if (_sessionActive.value) {
            Log.d(tag, "Session active; leaving sensors on")
            return
        }
        Log.d(tag, "Releasing sensors while idle")
        scope.launch { exerciseClientManager.endWarmUp() }
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
            finalizing = true

            try {
                // Filter again on the way out: rows persisted by an earlier build predate the
                // ingest-side check, and a single bad row would otherwise fail the whole session.
                val storedPoints = dao.getAllPoints()
                val points = storedPoints
                    .filter { BpmDataPoint.isValidBpm(it.bpm) }
                    .map { BpmDataPoint(it.timestamp, it.bpm) }

                val discarded = storedPoints.size - points.size
                if (discarded > 0) {
                    Log.w(tag, "Discarded $discarded out-of-range point(s) while finalizing")
                }

                if (points.isNotEmpty()) {
                    // The record model requires endTime > startTime. A backwards clock step mid-session
                    // would otherwise throw here and destroy the recording, so keep the duration
                    // strictly positive instead of failing.
                    val endTime = System.currentTimeMillis().coerceAtLeast(wallClock + 1)
                    val record = BpmWatchRecord(
                        date = Date(wallClock),
                        dataPoints = points.sortedBy { it.timestamp },
                        startTime = wallClock,
                        endTime = endTime,
                        deviceId = getDeviceId(),
                        // The watch no longer names its wearer. The phone owns that, and stamps
                        // the name it holds for this watch at the moment the record arrives.
                        wearerName = null,
                        watchId = getWatchId()
                    )
                    // Save to persistent "outbox" for reliable synchronization to the phone
                    dao.insertPendingRecord(PendingRecordEntity(recordJson = gson.toJson(record)))
                    Log.d(tag, "Record finalized with ${points.size} points starting at $wallClock for device: ${record.deviceId}")
                }
            } catch (e: Exception) {
                // Never leave the watch stuck mid-session: the sensors still need re-warming
                // even when this recording could not be saved.
                Log.e(tag, "Failed to finalize recording started at $wallClock", e)
            } finally {
                finalizing = false
            }

            cleanupSession()
            
            // Per requirement: Re-warm sensors for the next session
            prepareExercise()
        }
    }

    /**
     * Gets the configured device identifier, defaulting to the system model name.
     */
    fun getDeviceId(): String = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    /**
     * Updates the custom device identifier.
     */
    fun setDeviceId(id: String) {
        prefs.edit { putString("device_id", id.trim()) }
    }

    /**
     * This watch's stable identifier, generated once and kept for the life of the install.
     *
     * The device id defaults to the hardware model, so two watches of the same model are
     * indistinguishable to the phone. This is not: it survives re-pairing and app updates, and is
     * what lets the phone attribute a recording to the right watch — and therefore the right
     * person — without anyone configuring anything.
     */
    fun getWatchId(): String {
        prefs.getString(KEY_WATCH_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit { putString(KEY_WATCH_ID, generated) }
        Log.i(tag, "Generated stable watch id $generated")
        return generated
    }

    /**
     * Internal cleanup of session-specific transient state and preferences.
     */
    private suspend fun cleanupSession() {
        dao.deleteAll()
        prefs.edit { remove("start_time_ms").remove("start_time_boot_ms").remove(KEY_BOOT_EPOCH) }
        startTimeWallClock = 0L
        _recordingStartTimeBoot.value = 0L
        _liveBpm.value = null
        userIntentState.value = null
        _sessionActive.value = false
    }

    /**
     * Forcefully ends any active session and wipes transient data.
     */
    fun forceReset() {
        Log.d(tag, "forceReset requested")
        // Declare the intent first: without it the ended exercise looks like one Health Services
        // terminated on its own, and the recovery path would dutifully restart what we are trying
        // to tear down.
        userIntentState.value = RecordingState.ENDING
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

    /** A one-off read of the outbox, for the sync manager's own passes over it. */
    suspend fun getPendingRecords(): List<PendingRecordEntity> = dao.getAllPendingRecords()

    /** Notes that a record reached Play Services, so its delivery can be followed up later. */
    suspend fun markPendingRecordHandedOver(id: Long, path: String) = dao.markHandedOver(id, path)

    /** Drops a record the phone has acknowledged. */
    suspend fun removePendingRecord(id: Long) = dao.deletePendingRecordById(id)

    companion object {
        /** Persisted boot epoch, used to notice that a saved anchor predates a reboot. */
        private const val KEY_BOOT_EPOCH = "boot_epoch_ms"

        /** Persisted stable identifier for this watch. */
        private const val KEY_WATCH_ID = "watch_id"

        /**
         * How far the implied boot epoch may drift before a reboot is assumed.
         *
         * Ordinary clock corrections move it by seconds; a reboot moves it by the whole of the
         * previous uptime, so anything on this scale is unambiguous.
         */
        private const val BOOT_EPOCH_TOLERANCE_MS = 60_000L

        /**
         * How much of the warm-up buffer to keep when a recording starts.
         *
         * Health Services replays samples measured shortly before the press; those belong to this
         * session and are pinned to zero rather than discarded, so a recording begun the instant
         * the app opens still starts with a reading.
         */
        private const val PRE_START_GRACE_MS = 10_000L

        /**
         * How many times to put Health Services back to work before saving and giving up.
         *
         * Generous, because the recoverable causes — another app grabbing the sensor, a callback
         * lapsing — recover on the first retry, and the cost of one more attempt is far lower than
         * the cost of silently ending someone's recording.
         */
        private const val MAX_AUTO_RESTARTS = 5

        /** Multiplied by the attempt number, so a sensor that keeps refusing is not hammered. */
        private const val AUTO_RESTART_BACKOFF_MS = 2_000L

        @Volatile
        private var instance: RecordingRepository? = null

        fun getInstance(context: Context): RecordingRepository {
            return instance ?: synchronized(this) {
                instance ?: RecordingRepository(context).also { instance = it }
            }
        }
    }
}
