package inga.bpmetrics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import android.util.Log
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PermissionsViewModel(
    private val appContext: Context
) : ViewModel() {

    private val exerciseClient by lazy {
        HealthServices.getClient(appContext).exerciseClient
    }

    private val heartRatePermissionAdjusted =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) HealthPermissions.READ_HEART_RATE
        else Manifest.permission.BODY_SENSORS

    val requiredPermissions = listOf(
        heartRatePermissionAdjusted,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_HEALTH,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val _readiness = MutableStateFlow<AppReadiness>(AppReadiness.Checking)
    val readiness = _readiness.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            checkAll()
        }


    }

    private suspend fun checkAll() {
        Log.d("PermVM", "Checking all permissions and exercise capabilities")
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(
                appContext,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            _readiness.value = AppReadiness.MissingPermissions(missingPermissions)
            return
        }

//         Permissions granted → now check capabilities
        try {
            val capabilities = exerciseClient.getCapabilitiesAsync().await()

            val supportsWorkout = ExerciseType.WORKOUT in capabilities.supportedExerciseTypes

            val workoutCapabilities = capabilities.getExerciseTypeCapabilities(ExerciseType.WORKOUT)

            val supportsBpm = DataType.HEART_RATE_BPM in workoutCapabilities.supportedDataTypes

            if (supportsWorkout && supportsBpm) {
                Log.d("PermVM", "All permissions and exercise capabilities checked")
                _readiness.value = AppReadiness.Ready
            } else {
                _readiness.value = AppReadiness.UnsupportedDevice
            }

        } catch (e: Exception) {
            _readiness.value = AppReadiness.Error(e.message ?: "Unknown error")
        }
    }

}

sealed interface AppReadiness {
    object Checking : AppReadiness
    object Ready : AppReadiness
    data class MissingPermissions(val permissions: List<String>) : AppReadiness
    object UnsupportedDevice : AppReadiness
    data class Error(val message: String) : AppReadiness
}