package inga.bpmetrics

import android.Manifest
import android.content.Context
import android.health.connect.HealthPermissions
import android.os.Build
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExerciseCapabilitiesViewModel (
    private val appContext: Context
) : ViewModel() {

    private val repository = BPMetricsRepository.instance
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

            val supportsWorkout = ExerciseType.WORKOUT in capabilities.supportedExerciseTypes

            val workoutCapabilities = capabilities.getExerciseTypeCapabilities(ExerciseType.WORKOUT)

            val supportsBpm = DataType.HEART_RATE_BPM in workoutCapabilities.supportedDataTypes

            if (supportsWorkout && supportsBpm) {
                Log.d("PermVM", "All permissions and exercise capabilities checked")
                _exerciseCapabilities.value = ExerciseCapabilitiesState.Ready
            } else {
                _exerciseCapabilities.value = ExerciseCapabilitiesState.UnsupportedDevice
            }

        } catch (e: Exception) {
            _exerciseCapabilities.value = ExerciseCapabilitiesState.Error(e.message ?: "Unknown error")
        }
    }
}

sealed interface ExerciseCapabilitiesState {
    object Checking : ExerciseCapabilitiesState
    object Ready : ExerciseCapabilitiesState

    object UnsupportedDevice : ExerciseCapabilitiesState
    data class Error(val errorMessage: String) : ExerciseCapabilitiesState
}