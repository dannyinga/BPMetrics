package inga.bpmetrics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionsViewModel(
    private val appContext: Context
) : ViewModel() {

    private val repository = BPMetricsRepository.instance

    private val heartRatePermissionAdjusted =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) HealthPermissions.READ_HEART_RATE
        else Manifest.permission.BODY_SENSORS

    val requiredPermissions = listOf(
        heartRatePermissionAdjusted,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_HEALTH,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val _permissions = MutableStateFlow<PermissionState>(PermissionState.Checking)
    val permissions = _permissions.asStateFlow()

    init {
        if (repository.hasAllPrerequisites.value)
                _permissions.value = PermissionState.Ready
        else {
            refresh()
        }
    }

    fun refresh() {
        checkPermissions()
    }

    private fun checkPermissions() {
        Log.d("PermVM", "Checking permissions")
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(
                appContext,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            _permissions.value = PermissionState.MissingPermissions(missingPermissions)
        }

        else {
            _permissions.value = PermissionState.Ready
        }
    }

}

sealed interface PermissionState {
    object Checking : PermissionState
    object Ready : PermissionState
    data class MissingPermissions(val permissions: List<String>) : PermissionState
}