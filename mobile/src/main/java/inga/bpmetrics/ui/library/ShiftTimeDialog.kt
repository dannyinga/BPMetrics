package inga.bpmetrics.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.clock
import inga.bpmetrics.ui.util.StringFormatHelpers
import java.time.ZoneId
import kotlin.math.abs

/**
 * Moves several recordings in time together, for a watch whose clock was wrong.
 *
 * Asks for one thing — when the first of them really started — and moves the rest by the same
 * amount. That is the shape of the fault: a watch that booted with a stale clock is off by a
 * constant, so its recordings are right relative to each other and wrong only as a group. Asking
 * for each start separately would invite them drifting out of step.
 */
@Composable
fun ShiftTimeDialog(
    records: List<BpmRecordEntity>,
    onDismiss: () -> Unit,
    onShift: (deltaMs: Long) -> Unit
) {
    val first = records.minByOrNull { it.startTime } ?: return
    val zone = first.clock
    var newStart by remember { mutableStateOf(first.startTime) }
    var picking by remember { mutableStateOf(false) }
    val delta = newStart - first.startTime

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct the time") },
        text = {
            Column {
                Text(
                    if (records.size == 1) {
                        "For a recording made while the watch's clock was wrong."
                    } else {
                        "For recordings made while a watch's clock was wrong. All " +
                            "${records.size} move by the same amount, so they stay in step " +
                            "with each other."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    if (records.size == 1) "Really started" else "First one really started",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(startText(newStart, zone), textAlign = TextAlign.Start)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (delta == 0L) "Was ${startText(first.startTime, zone)}"
                    else describeShift(delta),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = delta != 0L, onClick = { onShift(delta) }) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (picking) {
        DateTimePicker(
            initial = newStart,
            zone = java.util.TimeZone.getTimeZone(zone),
            onDismiss = { picking = false },
            onPicked = { newStart = it; picking = false }
        )
    }
}

/** A start time with its date and seconds, in the recording's own clock. */
internal fun startText(atMs: Long, zone: ZoneId): String =
    StringFormatHelpers.getDateString(atMs, zone) + ", " +
        StringFormatHelpers.getTimeString(atMs, zone, withSeconds = true)

/**
 * How far a move goes, in words: "Moves 74 days, 3 h 12 min later".
 *
 * Days rather than months, because a month is not a fixed length and this describes an exact
 * amount of time.
 */
internal fun describeShift(deltaMs: Long): String {
    val totalSeconds = abs(deltaMs) / 1000
    val days = totalSeconds / 86_400
    val hours = totalSeconds % 86_400 / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    val parts = buildList {
        if (days > 0) add("$days day" + if (days == 1L) "" else "s")
        if (hours > 0) add("$hours h")
        if (minutes > 0) add("$minutes min")
        if (seconds > 0 && days == 0L) add("$seconds s")
    }.ifEmpty { listOf("under a second") }
    return "Moves ${parts.joinToString(", ")} " + if (deltaMs > 0) "later" else "earlier"
}
