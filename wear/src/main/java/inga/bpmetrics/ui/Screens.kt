package inga.bpmetrics.ui

import android.os.SystemClock
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import inga.bpmetrics.R
import inga.bpmetrics.recording.RecordingState
import inga.bpmetrics.theme.BpmAccent
import inga.bpmetrics.theme.HeartRed
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
        PermissionState.Checking -> {
            LoadingScreen(label = "Checking permissions...")
        }
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
            Icon(
                painter = painterResource(id = R.drawable.ic_heart_plus),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = HeartRed
            )
            Spacer(modifier = Modifier.height(12.dp))
            BoxWithConstraints (
                modifier = Modifier.fillMaxWidth(0.7f),
            ) {
                val boxWidth : Dp = this.maxWidth
                val fontSize = (boxWidth.value / 11).sp
                Text(
                    text = "BPMetrics needs sensor access to track your heart rate.",
                    fontSize = fontSize,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                modifier = Modifier.fillMaxWidth(0.8f),
                onClick = { 
                    launcher.launch(missingPermissions.toTypedArray())
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = BpmAccent)
            ) {
                Text("Grant Access", color = Color.Black)
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
            LoadingScreen(label = "Checking device support...")
        }

        is ExerciseCapabilitiesState.Error -> {
            // Option to display an error or retry message
        }
        
        ExerciseCapabilitiesState.Ready -> {
            LaunchedEffect(Unit) { onReady() }
        }
        
        ExerciseCapabilitiesState.UnsupportedDevice -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This device does not support heart rate tracking.", textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * A consistent loading screen used during permission and capability checks.
 */
@Composable
private fun LoadingScreen(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            indicatorColor = BpmAccent
        )
        Spacer(Modifier.height(12.dp))
        Text(text = label, style = MaterialTheme.typography.caption2)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.ic_heart_plus),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = HeartRed
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.bpm == null) "--" else state.bpm.toInt().toString(),
                style = MaterialTheme.typography.display2,
                fontWeight = FontWeight.Bold
            )
        }

        // Status text (e.g., "Acquiring...", "Ready")
        Text(
            text = state.statusText,
            style = MaterialTheme.typography.caption2
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        val isRecording = state.serviceState == RecordingState.RECORDING

        // Button is only enabled when we have a signal lock or are currently recording
        val buttonEnabled = state.serviceState == RecordingState.READY ||
                           state.serviceState == RecordingState.RECORDING

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = buttonEnabled,
                onClick = {
                    if (isRecording) onStop() else onStart()
                },
                modifier = Modifier.size(56.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isRecording) Color(0xFFD32F2F) else BpmAccent
                )
            ) {
                Icon(
                    painter = if (isRecording) {
                        painterResource(id = android.R.drawable.ic_delete)
                    } else {
                        painterResource(id = R.drawable.ic_heart_plus)
                    },
                    contentDescription = if (isRecording) "Stop" else "Start",
                    modifier = Modifier.size(32.dp),
                    tint = if (isRecording) Color.White else Color.Black
                )
            }

            // Show timer only when recording
            if (state.serviceState == RecordingState.RECORDING) {
                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = formattedTimeStamp,
                    style = MaterialTheme.typography.body2,
                )
            }
        }
    }
}
