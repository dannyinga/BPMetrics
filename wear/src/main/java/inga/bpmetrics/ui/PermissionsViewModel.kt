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

    /**
     * What recording can't happen without. Notifications aren't on it: they show the ongoing
     * recording, but a refusal used to leave the app stuck on this screen with no way to record.
     */
    val requiredPermissions = listOf(
        heartRatePermissionAdjusted,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_HEALTH
    )

    /** Asked for alongside, and fine to refuse. */
    private val optionalPermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyList()

    /** What the Grant button asks for: whatever is still missing, required or not. */
    fun permissionsToRequest(): Array<String> = (requiredPermissions + optionalPermissions)
        .filter { ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()

    /**
     * The answer to the system prompt. A refusal is said plainly, with a way to Settings: it used
     * to leave the same "needs sensor access" screen up, and once the system stops showing its
     * prompt after a second refusal, Grant did nothing at all (Audit #13).
     */
    fun onRequestResult() {
        checkPermissions()
        val state = _permissions.value
        if (state is PermissionState.MissingPermissions) {
            _permissions.value = state.copy(refused = true)
        }
    }

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
            // A refusal stays a refusal across a re-check (coming back from Settings unchanged).
            val refused = (_permissions.value as? PermissionState.MissingPermissions)?.refused ?: false
            _permissions.value = PermissionState.MissingPermissions(missingPermissions, refused)
        } else {
            // The repository hears about it once the capability check has passed too, since that
            // starts the health service, and a watch without the sensor shouldn't start it.
            Log.d("PermVM", "All permissions granted.")
            _permissions.value = PermissionState.Ready
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
     * @property refused Whether the wearer has already said no to the system prompt.
     */
    data class MissingPermissions(
        val permissions: List<String>,
        val refused: Boolean = false
    ) : PermissionState
}
