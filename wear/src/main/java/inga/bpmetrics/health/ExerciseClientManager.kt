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

    suspend fun getCurrentExerciseInfo(): ExerciseInfo = exerciseClient.getCurrentExerciseInfoAsync().await()
}
