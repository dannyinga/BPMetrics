package inga.bpmetrics

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

sealed class Screens(val route: String) {
    object Permissions : Screens("permissions")
    object Recording : Screens("recording")
}

@Composable
fun PermissionsScreen (
    viewModel: PermissionsViewModel,
    onReady: () -> Unit
) {
    val readiness by viewModel.readiness.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            viewModel.refresh()
        }
    }

    when (readiness) {
        AppReadiness.Ready ->
            LaunchedEffect(Unit) { onReady() }
        is AppReadiness.MissingPermissions ->
            PermissionsUI(
                launcher,
                (readiness as AppReadiness.MissingPermissions).permissions)
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

/**
 * The start stop screen composed of a live BPM indicator and a start/stop button.
 * The button communicates with the controller to start/stop recording.
 */
@Composable
fun RecordingScreen(viewModel: RecordingViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BpmContent(
        state = uiState,
        onStart = viewModel::onStartClicked,
        onStop = viewModel::onStopClicked
    )
}

@Composable
fun BpmContent(
    state: RecordingUIState,
    onStart: () -> Unit,
    onStop: () -> Unit) {

    val context = LocalContext.current
    val window = context.findActivity()?.window

    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {

        Text(
            text = if (state.bpm == null) "--" else state.bpm.toString(),
            style = MaterialTheme.typography.body1,
        )

        Text(
            text = state.statusText
        )

        Spacer(
            modifier = Modifier.fillMaxWidth().height(18.dp)
        )

        val isRecording = state.serviceMode != BpmServiceMode.IDLE

        val buttonEnabled =
            state.serviceMode != BpmServiceMode.IDLE ||
            state.spotMeasureState == BpmSpotMeasureState.AVAILABLE


        Button(
            enabled = buttonEnabled,
            onClick = {
                if (isRecording) {
                    onStop()
                    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    onStart()
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        ) {
            Text(if (isRecording) "Stop" else "Start")
        }
    }

}

fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }