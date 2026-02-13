package inga.bpmetrics

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
import kotlinx.coroutines.delay

sealed class Screens(val route: String) {
    object Permissions : Screens("permissions")
    object Recording : Screens("recording")
    object ExerciseCapabilities : Screens("exercise_capabilities")
}

@Composable
fun PermissionsScreen (
    viewModel: PermissionsViewModel,
    onReady: () -> Unit
) {
    val permissions by viewModel.permissions.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            viewModel.refresh()
        }
    }

    when (permissions) {
        PermissionState.Checking -> {}
        is PermissionState.MissingPermissions
            -> PermissionsUI(launcher,
            (permissions as PermissionState.MissingPermissions).permissions)
        PermissionState.Ready -> LaunchedEffect(Unit) { onReady() }
    }
}
//     Permissions UI
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
                onClick = { launcher.launch(missingPermissions.toTypedArray())
                Log.d("Activity", "Missing permissions = $missingPermissions")}
            ) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
fun ExerciseCapabilitiesScreen(
    viewModel: ExerciseCapabilitiesViewModel,
    onReady: () -> Unit
) {
    val exerciseCapabilities by viewModel.exerciseCapabilities.collectAsState()

    when (exerciseCapabilities) {
        ExerciseCapabilitiesState.Checking -> {

        }

        is ExerciseCapabilitiesState.Error -> {

        }
        ExerciseCapabilitiesState.Ready -> {
            LaunchedEffect(Unit) { onReady() }
        }
        ExerciseCapabilitiesState.UnsupportedDevice -> {

        }
    }

}

/**
 * The start stop screen composed of a live BPM indicator and a start/stop button.
 * The button communicates with the controller to start/stop recording.
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

@Composable
fun RecordingContent(
    state: RecordingUIState,
    onStart: () -> Unit,
    onStop: () -> Unit) {

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val recordingStartTime = state.recordingStartTime
        val recordingDuration by produceState(0L, recordingStartTime) {
            if (recordingStartTime == 0L) return@produceState

            while (true) {
                value = SystemClock.elapsedRealtime() - state.recordingStartTime
                delay(1000)
            }
        }

        val seconds = recordingDuration / 1000 % 60
        val minutes = recordingDuration / (1000 * 60) % 60

        val minutesF = if (minutes > 0) "${minutes}m " else ""
        val formattedTimeStamp = "$minutesF${seconds}s"

        Text(
            text = if (state.bpm == null) "--" else state.bpm.toString(),
            style = MaterialTheme.typography.body1,
        )

        Text(
            text = state.statusText
        )

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
        )

        val isRecording = state.serviceState == BpmServiceState.RECORDING

        val buttonEnabled =
            state.serviceState == BpmServiceState.READY
         || state.serviceState == BpmServiceState.RECORDING


        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ){
            Button(
                enabled = buttonEnabled,
                onClick = {
                    if (isRecording) {
                        onStop()
                    } else {
                        onStart()
                    }
                }
            ) {
                Text(if (isRecording) "Stop" else "Start")
            }

            if (state.serviceState == BpmServiceState.RECORDING) {
                Spacer(
                    modifier = Modifier.width(18.dp)
                )

                Text(
                    text = formattedTimeStamp,
                    style = MaterialTheme.typography.body1,
                )
            }
        }
    }

}