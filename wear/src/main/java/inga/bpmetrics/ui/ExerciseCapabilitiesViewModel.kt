package inga.bpmetrics.ui

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import inga.bpmetrics.recording.RecordingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExerciseCapabilitiesViewModel (
    private val appContext: Context
) : ViewModel() {

    private val repository = RecordingRepository.Companion.getInstance(appContext)

    private val exerciseClient by lazy {
        HealthServices.getClient(appContext).exerciseClient
    }

    private val _exerciseCapabilities = MutableStateFlow<ExerciseCapabilitiesState>(
        ExerciseCapabilitiesState.Checking)
    val exerciseCapabilities = _exerciseCapabilities.asStateFlow()

    init {
        viewModelScope.launch {
            if (repository.hasAllPrerequisites.value)
                _exerciseCapabilities.value = ExerciseCapabilitiesState.Ready
            else {
                checkCapabilities()
            }
        }
    }

    private suspend fun checkCapabilities() {
        Log.d("ExCapVM", "Checking capabilities")
        try {
            val capabilities = exerciseClient.getCapabilitiesAsync().await()

            // Asked *before* the workout capabilities are fetched, not after. `capabilities` throws
            // `IllegalArgumentException` when asked about an exercise type the watch does not
            // support — so on a watch that genuinely cannot do workouts, the old order threw, the
            // catch below caught it, and the wearer was shown a raw exception message where the
            // screen already had a proper "this watch is not supported" state to offer.
            val supportsWorkout = ExerciseType.WORKOUT in capabilities.supportedExerciseTypes
            if (!supportsWorkout) {
                _exerciseCapabilities.value = ExerciseCapabilitiesState.UnsupportedDevice
                return
            }

            val workoutCapabilities = capabilities.getExerciseTypeCapabilities(ExerciseType.WORKOUT)
            val supportsBpm = DataType.HEART_RATE_BPM in workoutCapabilities.supportedDataTypes

            if (supportsBpm) {
                Log.d("ExCapVM", "All permissions and exercise capabilities checked")
                _exerciseCapabilities.value = ExerciseCapabilitiesState.Ready
            } else {
                _exerciseCapabilities.value = ExerciseCapabilitiesState.UnsupportedDevice
            }

        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // `Throwable`: this runs in `viewModelScope`, which carries no exception handler, so
            // anything not caught here takes the app down before the wearer has seen a screen.
            // Health Services is a Play-Services-backed API and can fail in ways that are not
            // `Exception`s at all when the services package is mid-update.
            Log.e("ExCapVM", "Capability check failed", t)
            _exerciseCapabilities.value =
                ExerciseCapabilitiesState.Error(t.message ?: "Unknown error")
        }
    }
}

sealed interface ExerciseCapabilitiesState {
    object Checking : ExerciseCapabilitiesState
    object Ready : ExerciseCapabilitiesState

    object UnsupportedDevice : ExerciseCapabilitiesState
    data class Error(val errorMessage: String) : ExerciseCapabilitiesState
}