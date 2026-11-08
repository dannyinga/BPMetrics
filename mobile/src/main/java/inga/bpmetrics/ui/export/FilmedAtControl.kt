package inga.bpmetrics.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getDurationString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString

/**
 * When the clip being framed was filmed, and the way to say so.
 *
 * Beside the preview rather than in Contents, because the preview is how you know you have it right:
 * set 3:19:08 PM and watch whether the curve climbs as the crowd does. Per clip, and nowhere near the
 * preset — see [ClipRetiming].
 *
 * The length is shown and not editable. It is the file's, and a clip is only ever wrong about where
 * it starts.
 */
@Composable
fun FilmedAtControl(
    clip: ClipSelection,
    /** The recordings' clock — the venue's. The time is read and written in it. */
    zone: java.time.ZoneId,
    /** Where the recordings in scope begin, for lining a clip up with the start of the set. */
    recordingStartMs: Long?,
    /** Whether anyone on the clip was recording while it was filmed. */
    drawsSomething: Boolean,
    onSet: (Long) -> Unit,
    onReset: () -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    val startedAt = clip.clip.startedAtMs
    val corrected = clip.originalClip != null

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Filmed at",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))

        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Schedule, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                // With seconds. Lining a curve up with footage is a matter of seconds, and a field
                // that showed only the minute would hide the one digit being corrected.
                getDateString(startedAt, zone) + ", " + getTimeString(startedAt, zone, withSeconds = true)
            )
        }

        Text(
            buildString {
                append("Length ${getDurationString(clip.clip.durationMs)}, from the file")
                // Which clock this is in, because it is the venue's and not necessarily the phone's:
                // a Chicago set is set in Chicago time, whoever is holding the phone and wherever.
                append(" · ${zone.id}")
                if (corrected) append(" · set by hand")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (recordingStartMs != null && recordingStartMs != startedAt) {
                TextButton(onClick = { onSet(recordingStartMs) }) { Text("Start of the recording") }
            }
            if (corrected) {
                TextButton(onClick = onReset) { Text("Use the file's time") }
            }
        }

        // The fine adjustment, here beside the time it adjusts. It was a look-wide "sync offset" at
        // the bottom of the last section, while this was per clip at the top (UX Audit E7). Both
        // answer "when was this filmed", for this clip.
        Spacer(Modifier.height(8.dp))
        // How far the heart rate is shifted against the footage. Positive puts the clip later on the
        // recordings, so the curve shows what's coming: the heart rate leads the picture.
        val offset = clip.originalClip?.let { original ->
            (startedAt - original.startedAtMs).takeIf { kotlin.math.abs(it) < 60_000L }
        } ?: 0L
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "BPM offset",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // Only while it's a small shift: a clip set to another evening isn't "0.3 s off".
            Text(
                "%+.1f s".format(offset / 1000f),
                style = MaterialTheme.typography.labelLarge,
                color = if (offset == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
        }
        Text(
            "Heart rate changing before the footage does? Use −. After it? Use +.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(-1_000L to "−1 s", -100L to "−0.1 s", 100L to "+0.1 s", 1_000L to "+1 s").forEach { (step, label) ->
                OutlinedButton(onClick = { onSet(startedAt + step) }, modifier = Modifier.weight(1f)) {
                    Text(label, maxLines = 1)
                }
            }
        }

        // Only where nothing lines up. A clip that overlaps no recording has no one to draw, and the
        // preview above would show an empty graph with no hint that the time is what is wrong.
        if (!drawsSomething) {
            Text(
                "Nobody was recording at this time. Set when the video was really filmed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (picking) {
        inga.bpmetrics.ui.library.DateTimePicker(
            initial = startedAt,
            zone = java.util.TimeZone.getTimeZone(zone),
            onDismiss = { picking = false },
            onPicked = { chosen ->
                picking = false
                onSet(chosen)
            }
        )
    }
}
