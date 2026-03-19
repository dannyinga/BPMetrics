package inga.bpmetrics.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import inga.bpmetrics.recording.RecordingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel responsible for checking and managing the state of required system permissions.
 *
 * It provides a list of required permissions based on the device's Android version
 * and exposes the current [PermissionState] to the UI.
 *
 * @property appContext The application context used for checking permission status.
 */
class PermissionsViewModel(
    private val appContext: Context
) : ViewModel() {

    private val repository = RecordingRepository.getInstance(appContext)

    /** The specific permission string for heart rate access, adjusted for newer API levels. */
    private val heartRatePermissionAdjusted =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) HealthPermissions.READ_HEART_RATE
        else Manifest.permission.BODY_SENSORS

    /** The comprehensive list of all permissions required for the app to record and sync data. */
    val requiredPermissions = listOf(
        heartRatePermissionAdjusted,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_HEALTH,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val _permissions = MutableStateFlow<PermissionState>(PermissionState.Checking)
    /**
     * A [StateFlow] representing the current permission status (Checking, Ready, or Missing).
     */
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    init {
        refresh()
    }

    /**
     * Manually triggers a re-check of the system permission status.
     */
    fun refresh() {
        checkPermissions()
    }

    /**
     * Checks all required permissions against the [PackageManager] and updates the UI state.
     * Also notifies the [RecordingRepository] once all prerequisites are met.
     */
    private fun checkPermissions() {
        Log.d("PermVM", "Checking permissions")

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            _permissions.value = PermissionState.MissingPermissions(missingPermissions)
        } else {
            Log.d("PermVM", "All permissions granted. Notifying repository.")
            _permissions.value = PermissionState.Ready
            repository.grantAllPrerequisites()
        }
    }
}

/**
 * Sealed interface representing the possible states of the permission check process.
 */
sealed interface PermissionState {
    /** The app is currently querying the system for permission status. */
    object Checking : PermissionState
    /** All required permissions have been granted by the user. */
    object Ready : PermissionState
    /**
     * One or more permissions are missing and must be requested.
     * @property permissions The list of missing permission strings.
     */
    data class MissingPermissions(val permissions: List<String>) : PermissionState
}
