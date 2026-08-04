package inga.bpmetrics.health

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearUpdateCallback
import androidx.health.services.client.data.*
import androidx.health.services.client.endExercise
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.health.services.client.data.ExerciseTrackedStatus.Companion.OWNED_EXERCISE_IN_PROGRESS

/**
 * Manages the direct interaction with Android Health Services ExerciseClient.
 * 
 * It acts as a bridge between the system sensors and the repository, emitting
 * raw updates without enforcing high-level application state.
 */
class ExerciseClientManager(context: Context) {
    private val tag = "ExerciseClientManager"
    private val healthClient = HealthServices.getClient(context)
    private val exerciseClient = healthClient.exerciseClient

    private val _exerciseUpdate = MutableStateFlow<ExerciseUpdate?>(null)
    /** The most recent update received from Health Services. */
    val exerciseUpdate = _exerciseUpdate.asStateFlow()

    private val _availability = MutableStateFlow<Availability?>(null)
    /** The current availability status of the heart rate sensor. */
    val availability = _availability.asStateFlow()

    /**
     * Configuration for a recording session.
     *
     * [DataType.HEART_RATE_BPM] delivers individual samples, each carrying its own boot-time
     * stamp, at the platform's continuous rate — the highest fidelity the exercise API offers.
     *
     * No `batchingModeOverrides` is set on purpose. Batching controls only how often Health
     * Services *delivers* samples, not how often it *takes* them, and every sample carries the
     * instant it was measured. Forcing more frequent delivery would wake the app more often for
     * data that is identical once recorded — it would cost battery and buy no precision. The
     * only thing default batching affects is how promptly the on-watch number refreshes while
     * the screen is off, which nobody is looking at.
     *
     * Auto-pause is off so a still wearer never silently creates a gap in the recording.
     */
    private val exerciseConfig = ExerciseConfig(
        exerciseType = ExerciseType.WORKOUT,
        dataTypes = setOf(DataType.HEART_RATE_BPM),
        isAutoPauseAndResumeEnabled = false,
        isGpsEnabled = false,
    )

    private val warmUpConfig = WarmUpConfig(
        ExerciseType.WORKOUT,
        setOf(DataType.HEART_RATE_BPM)
    )

    private val exerciseCallback = object : ExerciseUpdateCallback {
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            _availability.value = availability
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            _exerciseUpdate.value = update
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(tag, "Exercise callback registration failed", throwable)
        }
    }

    init {
        exerciseClient.setUpdateCallback(exerciseCallback)
    }

    suspend fun prepareExercise() {
        try {
            exerciseClient.prepareExerciseAsync(warmUpConfig).await()
        } catch (e: Exception) {
            Log.e(tag, "Failed to prepare exercise", e)
        }
    }

    suspend fun startExercise() {
        try {
            val info = exerciseClient.getCurrentExerciseInfoAsync().await()
            if (info.exerciseTrackedStatus != OWNED_EXERCISE_IN_PROGRESS) {
                exerciseClient.startExerciseAsync(exerciseConfig).await()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error starting exercise", e)
        }
    }

    suspend fun pauseExercise() = try { exerciseClient.pauseExerciseAsync().await() } catch (e: Exception) {}

    suspend fun endExercise() {
        try {
            val info = exerciseClient.getCurrentExerciseInfoAsync().await()
            if (info.exerciseTrackedStatus == OWNED_EXERCISE_IN_PROGRESS) {
                exerciseClient.endExercise()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error ending exercise", e)
        }
    }

    /**
     * Powers the sensor down after a warm-up that will not become a recording.
     *
     * [prepareExercise] lights the optical sensor and keeps it lit until something ends it, so a
     * warm-up left behind when the app closes drains the battery indefinitely. Unlike
     * [endExercise] this does not check for an owned exercise first, because a warm-up is not
     * reported as one — it simply asks Health Services to stop and ignores the failure that
     * results when there was nothing running.
     */
    suspend fun endWarmUp() {
        try {
            exerciseClient.endExercise()
        } catch (e: Exception) {
            Log.d(tag, "No warm-up to end: ${e.message}")
        }
    }

    suspend fun getCurrentExerciseInfo(): ExerciseInfo = exerciseClient.getCurrentExerciseInfoAsync().await()
}
