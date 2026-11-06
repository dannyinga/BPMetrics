package inga.bpmetrics.ui

import android.os.SystemClock
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import inga.bpmetrics.R
import inga.bpmetrics.recording.RecordingState
import inga.bpmetrics.theme.BpmAccent
import inga.bpmetrics.theme.HeartRed
import inga.bpmetrics.theme.RecordingRed
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
 * Shows [PermissionsUI] while heart rate access is missing, and triggers [onReady] once it's
 * granted. Re-checks whenever the app comes back to the front, so turning access on in Settings
 * carries on by itself.
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
    val context = androidx.compose.ui.platform.LocalContext.current

    // Activity result launcher for handling the system permission request dialog
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.onRequestResult() }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val state = permissions) {
        PermissionState.Checking -> {
            LoadingScreen(label = "Checking permissions...")
        }
        is PermissionState.MissingPermissions -> {
            PermissionsUI(
                refused = state.refused,
                onGrant = { launcher.launch(viewModel.permissionsToRequest()) },
                onOpenSettings = {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        }
        PermissionState.Ready -> LaunchedEffect(Unit) { onReady() }
    }
}

/**
 * Asking for heart rate access, and what to do once it's been refused.
 *
 * @param refused Whether the wearer has said no to the system prompt. The system stops showing
 *   it after a second refusal, so from then on Settings is the only way to grant it.
 */
@Composable
fun PermissionsUI(
    refused: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Not after a refusal: the explanation and both ways forward need the room on a
            // round screen.
            if (!refused) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_heart_plus),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = HeartRed
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (refused) {
                Text(
                    text = "Heart rate access is off, so BPMetrics can't record. Turn it on " +
                        "in Settings.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.72f)
                )
            } else BoxWithConstraints (
                modifier = Modifier.fillMaxWidth(0.75f),
            ) {
                val boxWidth : Dp = this.maxWidth
                val fontSize = (boxWidth.value / 11).sp
                Text(
                    text = "BPMetrics needs sensor access to track your heart rate.",
                    fontSize = fontSize,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(if (refused) 8.dp else 16.dp))

            if (!refused) {
                Button(
                    modifier = Modifier.fillMaxWidth(0.7f),
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BpmAccent)
                ) {
                    Text("Grant Access", color = Color.Black)
                }
            } else {
                // Compact, both of them: two full buttons and the explanation don't fit a round
                // screen.
                CompactChip(
                    onClick = onOpenSettings,
                    label = { Text("Open Settings", color = Color.Black) },
                    colors = androidx.wear.compose.material.ChipDefaults.primaryChipColors(
                        backgroundColor = BpmAccent
                    )
                )
                // The prompt may still show after one refusal; after two, the system won't.
                CompactChip(
                    onClick = onGrant,
                    label = { Text("Ask again", style = MaterialTheme.typography.caption2) },
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/**
 * Composable that checks if the device supports the required Health Services heart rate capabilities.
 *
 * Automatically triggers [onReady] if the device is compatible. A device that isn't, or a check
 * that fails, says so; the failure used to show a black screen (Audit #14).
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

    when (val state = exerciseCapabilities) {
        ExerciseCapabilitiesState.Checking -> {
            LoadingScreen(label = "Checking device support...")
        }

        is ExerciseCapabilitiesState.Error -> {
            GateMessage(
                text = "Couldn't check this watch's heart rate sensor. ${state.errorMessage}",
                action = "Try again",
                onAction = viewModel::retry
            )
        }

        ExerciseCapabilitiesState.Ready -> {
            LaunchedEffect(Unit) { onReady() }
        }

        ExerciseCapabilitiesState.UnsupportedDevice -> {
            GateMessage(
                text = "This watch can't measure heart rate during a workout, which BPMetrics " +
                    "needs to record."
            )
        }
    }
}

/** A message in the middle of the round screen, with an optional action under it. */
@Composable
private fun GateMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_heart_plus),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colors.error
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(0.78f)
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            CompactChip(
                onClick = onAction,
                label = { Text(action, style = MaterialTheme.typography.caption2) }
            )
        }
    }
}

/**
 * A consistent loading screen used during permission and capability checks.
 */
@Composable
fun LoadingScreen(label: String) {
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
        onStop = viewModel::onStopClicked,
        onSendNow = viewModel::onSendNowClicked,
        onDismissSave = viewModel::onSaveMessageDismissed
    )
}

/**
 * The internal layout and logic for displaying recording content.
 *
 * @param state The current UI state containing heart rate, status, and duration info.
 * @param onStart Callback to trigger when the start button is clicked.
 * @param onStop Callback to trigger once stopping has been confirmed.
 * @param onSendNow Callback to offer the phone whatever the watch is still holding.
 * @param onDismissSave Clears the "Saved" or "Not saved" line.
 */
@Composable
fun RecordingContent(
    state: RecordingUIState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSendNow: () -> Unit = {},
    onDismissSave: () -> Unit = {}
) {
    // Stopping asks first (Audit #11). One tap on a wrist, from a sleeve or a jostle in a crowd,
    // ended a set, and the button sat right under "Send now".
    var confirmStop by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val isRecording = state.serviceState == RecordingState.RECORDING

        // The watch model is deliberately not shown. It is the least useful line on a round
        // screen with the least room — the phone names each watch and stamps records as they
        // arrive, so the model told the wearer nothing they could act on while pushing the
        // things they can act on towards the crop.

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

        // How the last recording ended, in place of the status line until it's tapped away or the
        // next one starts. Stopping used to end in silence whether or not anything was kept.
        val lastSave = state.lastSave
        if (lastSave != null && !isRecording && state.serviceState != RecordingState.ENDING) {
            Text(
                text = lastSave.message,
                style = MaterialTheme.typography.caption2,
                color = if (lastSave is inga.bpmetrics.recording.SaveOutcome.Saved) BpmAccent
                    else MaterialTheme.colors.error,
                textAlign = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .clickable(onClickLabel = "Dismiss", onClick = onDismissSave)
            )
        } else {
            // Status text (e.g., "Acquiring...", "Ready")
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.caption2
            )
        }

        // What the phone has not got yet. A recording stays counted here until the phone has
        // saved it, so this is what is genuinely still only on the wrist.
        if (state.pendingRecordCount > 0) {
            Text(
                text = if (state.pendingRecordCount == 1) {
                    "⇅ 1 recording not on phone yet"
                } else {
                    "⇅ ${state.pendingRecordCount} recordings not on phone yet"
                },
                style = MaterialTheme.typography.caption2,
                color = BpmAccent,
                textAlign = TextAlign.Center
            )
        }

        // Outside the count, deliberately. The whole block used to sit inside it, which meant the
        // one press most worth giving an answer to gave none: press Send now on a recording the
        // phone already has, the reconcile confirms it, the count drops to zero, and the chip —
        // along with the "Phone has everything" it was about to display — is removed from the
        // composition mid-press. The wearer sees the row vanish and has no idea whether that was
        // success or the app falling over.
        //
        // So the chip stays for as long as there is either something outstanding or something to
        // say about the last press. Not while recording: it sat just above Stop, and sending
        // carries on by itself anyway.
        if (!isRecording && (state.pendingRecordCount > 0 || state.sendResult != null || state.isSending)) {
            // Sending is automatic; this is for when it visibly is not working. Nothing is
            // deleted by it, so pressing it repeatedly is harmless.
            CompactChip(
                onClick = onSendNow,
                enabled = !state.isSending && state.pendingRecordCount > 0,
                label = {
                    Text(
                        text = state.sendResult ?: if (state.isSending) "Sending…" else "Send now",
                        style = MaterialTheme.typography.caption2,
                        maxLines = 1
                    )
                },
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        // Recording can begin from any state — waiting on a signal lock is no longer required.
        val buttonEnabled = state.isControlEnabled

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = buttonEnabled,
                onClick = {
                    if (isRecording) confirmStop = true else onStart()
                },
                modifier = Modifier.size(56.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isRecording) RecordingRed else BpmAccent
                )
            ) {
                Icon(
                    painter = if (isRecording) {
                        painterResource(id = R.drawable.ic_stop)
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

    // Closes by itself if the recording stops some other way while it's open.
    androidx.wear.compose.material.dialog.Dialog(
        showDialog = confirmStop && state.serviceState == RecordingState.RECORDING,
        onDismissRequest = { confirmStop = false }
    ) {
        androidx.wear.compose.material.dialog.Alert(
            title = { Text("Stop recording?", textAlign = TextAlign.Center) },
            negativeButton = {
                Button(
                    onClick = { confirmStop = false },
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = "Keep recording"
                    )
                }
            },
            positiveButton = {
                Button(
                    onClick = {
                        confirmStop = false
                        onStop()
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = RecordingRed)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_stop),
                        contentDescription = "Stop and save",
                        tint = Color.White
                    )
                }
            }
        ) {
            Text(
                "It's saved and sent to your phone.",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center
            )
        }
    }
}
