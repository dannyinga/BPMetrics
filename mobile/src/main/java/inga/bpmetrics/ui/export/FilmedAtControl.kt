package inga.bpmetrics.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getDurationString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString

/**
 * When the clip being framed was filmed, and how far its heart rate is shifted against it.
 *
 * The Sync tab, under the preview rather than in Contents, because the preview is how you know
 * you have it right: set 3:19:08 PM and watch whether the curve climbs as the crowd does. Per clip,
 * and nowhere near the preset — see [ClipRetiming].
 *
 * The length is shown and not editable. It is the file's, and a clip is only ever wrong about where
 * it starts.
 */
@Composable
fun FilmedAtControl(
    clip: ClipSelection,
    /** The recordings' clock — the venue's. The time is read and written in it. */
    zone: java.time.ZoneId,
    /** Whether anyone on the clip was recording while it was filmed. */
    drawsSomething: Boolean,
    /** A filming time picked by hand. The offset stays on top of it. */
    onSet: (Long) -> Unit,
    /** The BPM offset, in milliseconds. */
    onOffset: (Long) -> Unit,
    /** Filmed at the file's time again. The offset stays. */
    onReset: () -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    val filmedAt = ClipRetiming.filmedAt(clip)
    val synced = ClipRetiming.onFileTime(clip)
    // With seconds. Lining a curve up with footage is a matter of seconds, and a field that showed
    // only the minute would hide the one digit being corrected.
    fun stamp(ms: Long) = getDateString(ms, zone) + ", " + getTimeString(ms, zone, withSeconds = true)

    Column(Modifier.fillMaxWidth()) {
        // What the file says, always, so a time set by hand can be compared with it. Text, not a
        // control: it's the file's, and the button below is where the time is changed.
        // One line, shrinking to fit rather than wrapping: a long month name and seconds don't fit
        // a dialog's width at full size.
        androidx.compose.foundation.text.BasicText(
            "File start time: ${stamp(ClipRetiming.fileTime(clip))}",
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                minFontSize = 10.sp,
                maxFontSize = MaterialTheme.typography.bodyMedium.fontSize
            )
        )
        Spacer(Modifier.height(12.dp))

        Text("Recording started at", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))

        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Schedule, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stamp(filmedAt))
        }

        Text(
            // Which clock this is in, because it is the venue's and not necessarily the phone's: a
            // Chicago set is set in Chicago time, whoever is holding the phone and wherever.
            "Length ${getDurationString(clip.clip.durationMs)}, from the file · ${zone.id}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Whether the clip is filmed when its file says. That is the usual case and needs no
        // action, so it's a line saying so; once the time changes, it's the way back. The offset
        // is on top of either, so it doesn't count.
        Spacer(Modifier.height(4.dp))
        if (synced) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Video and BPM synced to file time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            TextButton(onClick = onReset) { Text("Sync video and BPM to file time?") }
        }

        // The fine adjustment, here beside the time it adjusts (UX Audit E7).
        Spacer(Modifier.height(8.dp))
        OffsetField(offsetMs = clip.offsetMs, onOffset = onOffset)
        Text(
            "Heart rate changing before the footage does? Enter a negative offset. After it? A positive one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
            initial = filmedAt,
            zone = java.util.TimeZone.getTimeZone(zone),
            onDismiss = { picking = false },
            onPicked = { chosen ->
                picking = false
                onSet(chosen)
            }
        )
    }
}

/**
 * The BPM offset, typed in seconds. Applied as it's typed, so the preview follows; a half-typed
 * value ("-", "1.") waits for the rest.
 */
@Composable
private fun OffsetField(offsetMs: Long, onOffset: (Long) -> Unit) {
    var text by remember { mutableStateOf(OffsetText.format(offsetMs)) }
    // Changed from outside, as by syncing to the file's time: show it, unless it's what's typed.
    LaunchedEffect(offsetMs) {
        if (OffsetText.parse(text) != offsetMs) text = OffsetText.format(offsetMs)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            OffsetText.parse(typed)?.let { if (it != offsetMs) onOffset(it) }
        },
        label = { Text("BPM offset") },
        suffix = { Text("s") },
        singleLine = true,
        isError = text.isNotBlank() && OffsetText.parse(text) == null && !OffsetText.partial(text),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/** The BPM offset as text, in seconds. Pure, so the parsing is tested rather than tried by hand. */
internal object OffsetText {
    /** Further than this is a different filming time, not an offset; that's what the time is for. */
    const val LIMIT_MS = 10 * 60_000L

    /** "0", "+1.5", "-0.25": no trailing zeros, and a sign on anything but zero. */
    fun format(offsetMs: Long): String {
        if (offsetMs == 0L) return "0"
        val seconds = java.math.BigDecimal.valueOf(offsetMs, 3).stripTrailingZeros().toPlainString()
        return if (offsetMs > 0) "+$seconds" else seconds
    }

    /** Milliseconds, or null for text that isn't a whole offset yet. Blank is no offset. */
    fun parse(text: String): Long? {
        val trimmed = text.trim().replace('−', '-').replace(',', '.')
        if (trimmed.isEmpty()) return 0L
        val seconds = trimmed.removePrefix("+").toBigDecimalOrNull() ?: return null
        if (trimmed.endsWith(".")) return null
        val ms = seconds.movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        return ms.takeIf { kotlin.math.abs(it) <= LIMIT_MS }
    }

    /** Text on its way to a number: a sign, or a number ending in its decimal point. */
    fun partial(text: String): Boolean {
        val trimmed = text.trim().replace('−', '-').replace(',', '.')
        return trimmed in setOf("-", "+", ".", "-.", "+.") ||
            (trimmed.endsWith(".") && trimmed.dropLast(1).removePrefix("+").toBigDecimalOrNull() != null)
    }
}
