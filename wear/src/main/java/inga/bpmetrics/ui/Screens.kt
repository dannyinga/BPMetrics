package inga.bpmetrics.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import inga.bpmetrics.BpmServiceState
import kotlinx.coroutines.delay

/**
 * Sealed class representing the different navigation routes in the watch application.
 * 
 * @property route The string identifier used for navigation.
 */
sealed class Screens(val route: String) {
    /** Route for the initial permission request screen. */
    object Permissions : Screens("permissions")
    /** Route for the main heart rate recording and display screen. */
    object Recording : Screens("recording")
    /** Route for the background hardware capability check screen. */
    object ExerciseCapabilities : Screens("exercise_capabilities")
}

/**
 * Composable that handles the high-level permission state logic.
 *
 * Redirects the user to [PermissionsUI] if requirements are missing, or triggers
 * [onReady] if all permissions are granted.
 *
 * @param viewModel The view model managing permission state and logic.
 * @param onReady Callback triggered once all permissions are successfully granted.
 */
@Composable
fun PermissionsScreen (
    viewModel: PermissionsViewModel,
    onReady: () -> Unit
) {
    val permissions by viewModel.permissions.collectAsState()

    // Activity result launcher for handling the system permission request dialog
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            viewModel.refresh()
        }
    }

    when (permissions) {
        PermissionState.Checking -> {}
        is PermissionState.MissingPermissions -> {
            PermissionsUI(
                launcher = launcher,
                missingPermissions = (permissions as PermissionState.MissingPermissions).permissions
            )
        }
        PermissionState.Ready -> LaunchedEffect(Unit) { onReady() }
    }
}

/**
 * The visual interface for requesting missing permissions.
 *
 * Displays a descriptive message and a button to launch the system permission prompt.
 *
 * @param launcher The result launcher used to initiate the permission request.
 * @param missingPermissions The list of permission strings that need to be requested.
 */
@Composable
fun PermissionsUI(
    launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    missingPermissions: List<String>
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BoxWithConstraints (
                modifier = Modifier.fillMaxWidth(0.6f),
            ) {
                val boxWidth : Dp = this.maxWidth
                val fontSize = (boxWidth.value / 10).sp
                Text(
                    text = "This app requires heart sensor and notification permissions to function",
                    fontSize = fontSize,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                modifier = Modifier.fillMaxWidth(0.8f),
                onClick = { 
                    launcher.launch(missingPermissions.toTypedArray())
                    Log.d("Activity", "Requesting missing permissions: $missingPermissions")
                }
            ) {
                Text("Grant Permission")
            }
        }
    }
}

/**
 * Composable that checks if the device supports the required Health Services heart rate capabilities.
 *
 * Automatically triggers [onReady] if the device is compatible.
 *
 * @param viewModel The view model managing hardware capability detection.
 * @param onReady Callback triggered once the device hardware is confirmed to be compatible.
 */
@Composable
fun ExerciseCapabilitiesScreen(
    viewModel: ExerciseCapabilitiesViewModel,
    onReady: () -> Unit
) {
    val exerciseCapabilities by viewModel.exerciseCapabilities.collectAsState()

    when (exerciseCapabilities) {
        ExerciseCapabilitiesState.Checking -> {
            // Option to add a loading indicator here
        }

        is ExerciseCapabilitiesState.Error -> {
            // Option to display an error or retry message
        }
        
        ExerciseCapabilitiesState.Ready -> {
            LaunchedEffect(Unit) { onReady() }
        }
        
        ExerciseCapabilitiesState.UnsupportedDevice -> {
            // Option to display a "not compatible" message to the user
        }
    }
}

/**
 * The primary interaction screen for the user.
 * 
 * Displays the live heart rate, recording status, and controls for starting/stopping the session.
 *
 * @param viewModel The view model providing UI state and handling interaction logic.
 */
@Composable
fun RecordingScreen(viewModel: RecordingViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RecordingContent(
        state = uiState,
        onStart = viewModel::onStartClicked,
        onStop = viewModel::onStopClicked
    )
}

/**
 * The internal layout and logic for displaying recording content.
 *
 * @param state The current UI state containing heart rate, status, and duration info.
 * @param onStart Callback to trigger when the start button is clicked.
 * @param onStop Callback to trigger when the stop button is clicked.
 */
@Composable
fun RecordingContent(
    state: RecordingUIState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // State-driven timer calculation
        val recordingStartTime = state.recordingStartTime
        val recordingDuration by produceState(0L, recordingStartTime) {
            if (recordingStartTime == 0L) {
                value = 0L
                return@produceState
            }

            while (true) {
                value = SystemClock.elapsedRealtime() - state.recordingStartTime
                delay(1000)
            }
        }

        // Formatting logic for the workout timer
        val seconds = recordingDuration / 1000 % 60
        val minutes = recordingDuration / (1000 * 60) % 60
        val hours = recordingDuration / (1000 * 60 * 60)

        val minutesF = if (minutes > 0) "${minutes}m " else ""
        val hoursF = if (hours > 0) "${hours}h " else ""
        val formattedTimeStamp = "$hoursF$minutesF${seconds}s"

        // Live Heart Rate Display
        Text(
            text = if (state.bpm == null) "--" else state.bpm.toInt().toString(),
            style = MaterialTheme.typography.body1,
        )

        // Status text (e.g., "Acquiring...", "Ready")
        Text(
            text = state.statusText
        )

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
        )

        val isRecording = state.serviceState == BpmServiceState.RECORDING

        // Button is only enabled when we have a signal lock or are currently recording
        val buttonEnabled = state.serviceState == BpmServiceState.READY || 
                           state.serviceState == BpmServiceState.RECORDING

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = buttonEnabled,
                onClick = {
                    if (isRecording) onStop() else onStart()
                }
            ) {
                Text(if (isRecording) "Stop" else "Start")
            }

            // Show timer only when recording
            if (state.serviceState == BpmServiceState.RECORDING) {
                Spacer(modifier = Modifier.width(18.dp))

                Text(
                    text = formattedTimeStamp,
                    style = MaterialTheme.typography.body1,
                )
            }
        }
    }
}
