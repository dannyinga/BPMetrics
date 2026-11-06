package inga.bpmetrics.ui

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import inga.bpmetrics.R
import inga.bpmetrics.recording.RecordingState
import inga.bpmetrics.recording.SaveOutcome
import inga.bpmetrics.recording.SignalState
import inga.bpmetrics.theme.BpmAccent
import inga.bpmetrics.theme.HeartRed
import inga.bpmetrics.theme.RecordingRed
import inga.bpmetrics.theme.WarningAmber
import inga.bpmetrics.theme.WearOnSurfaceVariant
import inga.bpmetrics.theme.WearSurfaceVariant
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
    // Chips in a list that scrolls with the crown: the button used to run off the edge of a small
    // round screen (Audit §6 Watch).
    WearScreen(centerItem = 1) {
        item {
            Icon(
                painter = painterResource(id = R.drawable.ic_heart),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = HeartRed
            )
        }
        item {
            Text(
                text = if (refused) {
                    "Heart rate access is off, so BPMetrics can't record. Turn it on in Settings."
                } else {
                    "BPMetrics needs heart rate access to record."
                },
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        if (refused) {
            item { WideChip("Open Settings", onClick = onOpenSettings, primary = true) }
            // The prompt may still show after one refusal; after two, the system won't.
            item { WideChip("Ask again", onClick = onGrant) }
        } else {
            item { WideChip("Grant access", onClick = onGrant, primary = true) }
        }
    }
}

/** A full-width chip whose label wraps rather than cutting words off (Audit §3 Watch). */
@Composable
private fun WideChip(label: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                label,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        colors = if (primary) {
            ChipDefaults.primaryChipColors(backgroundColor = BpmAccent, contentColor = Color.Black)
        } else {
            ChipDefaults.secondaryChipColors()
        },
        modifier = Modifier.fillMaxWidth()
    )
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

/** A message on its own screen, with an optional action under it. */
@Composable
private fun GateMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    WearScreen(centerItem = 1) {
        item {
            Icon(
                painter = painterResource(id = R.drawable.ic_heart),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colors.error
            )
        }
        item {
            Text(
                text = text,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        if (action != null) {
            item { WideChip(action, onClick = onAction) }
        }
    }
}

/**
 * A consistent loading screen used during permission and capability checks.
 */
@Composable
fun LoadingScreen(label: String) {
    WearScreen(centerItem = 0) {
        item {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                indicatorColor = BpmAccent
            )
        }
        item {
            Text(text = label, style = MaterialTheme.typography.caption2)
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
        onStop = viewModel::onStopClicked,
        onSendNow = viewModel::onSendNowClicked,
        onDismissSave = viewModel::onSaveMessageDismissed,
        onDismissSendResult = viewModel::onSendResultDismissed
    )
}

/**
 * What the reading needs the wearer to know, or null. Off the wrist and no signal mean the number
 * on screen isn't a heart rate right now.
 */
private fun signalWarning(signal: SignalState): String? = when (signal) {
    SignalState.OFF_BODY -> "Watch off wrist"
    SignalState.UNAVAILABLE -> "No signal"
    SignalState.ACQUIRING -> "Finding pulse"
    else -> null
}

/**
 * The recording screen.
 *
 * Built so the two states can't be confused at a glance (Audit §3 Watch): recording has a red REC
 * line with the timer and the reading in full white; idle has the status in that line and the
 * reading dimmed. The control sits in the same place, at the same size, in both. Warnings get a
 * line of their own, in amber and larger type, whose space is kept even when empty, so nothing
 * moves when one appears.
 *
 * @param state The current UI state containing heart rate, status, and duration info.
 * @param onStart Callback to trigger when the start button is clicked.
 * @param onStop Callback to trigger once stopping has been confirmed.
 * @param onSendNow Callback to offer the phone whatever the watch is still holding.
 * @param onDismissSave Clears the "Saved" or "Not saved" line.
 * @param onDismissSendResult Clears a failed send.
 */
@Composable
fun RecordingContent(
    state: RecordingUIState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSendNow: () -> Unit = {},
    onDismissSave: () -> Unit = {},
    onDismissSendResult: () -> Unit = {}
) {
    // Stopping asks first (Audit #11). One tap on a wrist, from a sleeve or a jostle in a crowd,
    // ended a set, and the button sat right under "Send now".
    var confirmStop by remember { mutableStateOf(false) }
    val isRecording = state.serviceState == RecordingState.RECORDING

    val recordingStartTime = state.recordingStartTime
    val recordingDuration by produceState(0L, recordingStartTime) {
        if (recordingStartTime == 0L) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = SystemClock.elapsedRealtime() - recordingStartTime
            delay(1000)
        }
    }

    val warning = signalWarning(state.signalState)
        ?.takeIf { isRecording || state.serviceState == RecordingState.UNAVAILABLE }
    val lastSave = state.lastSave?.takeIf { !isRecording && state.serviceState != RecordingState.ENDING }

    // Centred on the warning line, so the status and reading above it and the control below it are
    // all on screen when it opens; anything about syncing sits under the control, a turn of the crown away.
    WearScreen(centerItem = 2) {
        // 1. What's happening: a red REC and the timer, or the status while idle.
        item {
            if (isRecording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_record),
                        contentDescription = null,
                        tint = RecordingRed,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "REC  ${Elapsed.format(recordingDuration)}",
                        style = MaterialTheme.typography.caption1,
                        fontWeight = FontWeight.Bold,
                        color = RecordingRed
                    )
                }
            } else {
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.caption1,
                    color = WearOnSurfaceVariant
                )
            }
        }

        // 2. The reading: full and white while recording, dimmed while it's only a preview.
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_heart),
                    contentDescription = null,
                    modifier = Modifier.size(if (isRecording) 24.dp else 18.dp),
                    tint = if (isRecording) HeartRed else WearOnSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.bpm?.toInt()?.toString() ?: "--",
                    style = if (isRecording) MaterialTheme.typography.display2 else MaterialTheme.typography.display3,
                    fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal,
                    color = if (isRecording) Color.White else WearOnSurfaceVariant
                )
            }
        }

        // 3. Warnings and the last save, in a line whose height is kept when it's empty.
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    warning != null -> Text(
                        text = warning,
                        style = MaterialTheme.typography.title3,
                        color = WarningAmber,
                        textAlign = TextAlign.Center
                    )
                    lastSave != null -> Text(
                        text = lastSave.message,
                        style = MaterialTheme.typography.caption2,
                        color = if (lastSave is SaveOutcome.Saved) BpmAccent else MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clickable(onClickLabel = "Dismiss", onClick = onDismissSave)
                    )
                }
            }
        }

        // 4. The control: same place, same size, whatever the state.
        item {
            Button(
                enabled = state.isControlEnabled,
                onClick = { if (isRecording) confirmStop = true else onStart() },
                modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isRecording) RecordingRed else WearSurfaceVariant
                )
            ) {
                Icon(
                    painter = painterResource(id = if (isRecording) R.drawable.ic_stop else R.drawable.ic_record),
                    contentDescription = if (isRecording) "Stop" else "Start recording",
                    modifier = Modifier.size(if (isRecording) 28.dp else 24.dp),
                    tint = if (isRecording) Color.White else RecordingRed
                )
            }
        }

        // 5. What the phone hasn't got yet. A recording stays counted here until the phone has
        // saved it, so this is what is genuinely still only on the wrist.
        if (state.pendingRecordCount > 0) {
            item {
                Text(
                    text = if (state.pendingRecordCount == 1) {
                        "1 recording not on phone yet"
                    } else {
                        "${state.pendingRecordCount} recordings not on phone yet"
                    },
                    style = MaterialTheme.typography.caption2,
                    color = BpmAccent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp)
                )
            }
        }

        // 6. Sending. Not while recording (it sat just above Stop, and sending carries on by
        // itself anyway). A failure stays with Retry and Dismiss; it used to vanish after four
        // seconds, cut off mid-word.
        if (!isRecording) {
            if (state.sendFailed && state.sendResult != null) {
                item {
                    Text(
                        text = state.sendResult,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactChip(onClick = onSendNow, label = { Text("Retry") })
                        CompactChip(
                            onClick = onDismissSendResult,
                            label = { Text("Dismiss") },
                            colors = ChipDefaults.secondaryChipColors()
                        )
                    }
                }
            } else if (state.pendingRecordCount > 0 || state.sendResult != null || state.isSending) {
                item {
                    // Sending is automatic; this is for when it visibly is not working. Nothing
                    // is deleted by it, so pressing it repeatedly is harmless.
                    WideChip(
                        label = state.sendResult ?: if (state.isSending) "Sending…" else "Send now",
                        onClick = onSendNow,
                        enabled = !state.isSending && state.pendingRecordCount > 0
                    )
                }
            }
        }
    }

    // Closes by itself if the recording stops some other way while it's open.
    androidx.wear.compose.material.dialog.Dialog(
        showDialog = confirmStop && isRecording,
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
