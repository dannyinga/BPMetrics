package inga.bpmetrics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionsViewModel(
    private val appContext: Context
) : ViewModel() {

    private val heartRatePermissionAdjusted =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) HealthPermissions.READ_HEART_RATE
        else Manifest.permission.BODY_SENSORS

    val requiredPermissions = listOf(
        heartRatePermissionAdjusted,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_HEALTH,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val _readiness = MutableStateFlow(checkPermissions())
    val readiness = _readiness.asStateFlow()

    fun refresh() {
        _readiness.value = checkPermissions()
    }

    private fun checkPermissions(): AppReadiness{
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(
                appContext,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            return AppReadiness.Ready
        } else {
            return AppReadiness.MissingPermissions(missingPermissions)
        }
    }
}

sealed interface AppReadiness {
    object Ready : AppReadiness
    data class MissingPermissions(
        val permissions: List<String>
    ) : AppReadiness
}