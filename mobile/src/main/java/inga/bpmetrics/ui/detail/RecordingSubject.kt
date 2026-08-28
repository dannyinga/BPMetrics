package inga.bpmetrics.ui.detail

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import inga.bpmetrics.export.CsvExporter
import inga.bpmetrics.ui.graph.TimeUtils
import inga.bpmetrics.library.clock
import inga.bpmetrics.library.clockDiffersFromReader
import inga.bpmetrics.library.displayName
import inga.bpmetrics.ui.analysis.ConcurrentAnalysis
import inga.bpmetrics.ui.analysis.ConcurrentChart
import inga.bpmetrics.ui.analysis.RecordInsights
import inga.bpmetrics.ui.analysis.rememberConcurrentViewWindow
import inga.bpmetrics.ui.analysis.shortDuration
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import inga.bpmetrics.ui.tags.EffectiveTagChip
import inga.bpmetrics.ui.tags.TagSelectionDialog
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.components.BpmCard
import inga.bpmetrics.ui.components.BpmSectionHeader
import inga.bpmetrics.ui.components.BpmSpacing
import inga.bpmetrics.ui.components.DeleteConfirmDialog
import inga.bpmetrics.ui.components.PersonPicker
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import inga.bpmetrics.ui.record.watchLabel
import inga.bpmetrics.ui.record.RecordPlacement
import inga.bpmetrics.ui.record.BpmRecordViewModel

/**
 * The parts of a recording's page that are about the recording itself.
 *
 * Lifted wholesale out of `BpmRecordScreen` when Sprint 5 folded that screen into the shared detail
 * page. Nothing here changed: the breadcrumb, the venue row, the split dialog and the standing
 * against a person's other recordings were all correct, they were simply living inside a screen
 * that also reimplemented the analysis. They are the *subject* half.
 */
@Composable
internal fun Breadcrumb(
    placement: RecordPlacement,
    onOpenEvent: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit
) {
    if (placement.event == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        placement.group?.let { group ->
            Text(
                text = group.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onOpenGroup(group.eventId) }
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            )
            Text(
                " > ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = placement.event.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { onOpenEvent(placement.event.eventId) }
                .padding(vertical = 4.dp, horizontal = 2.dp)
        )
    }
}

/**
 * Where this recording stands among that person's others.
 *
 * The one piece of a recording's page that is not a scope question — "their third highest" is a
 * fact about a recording relative to a set it is not in. It goes into the Summary through
 * [inga.bpmetrics.ui.analysis.AnalysisScreen]'s extra slot rather than into the header, which has
 * to stay out of the chart's way.
 */
@Composable
internal fun RecordInsightsSection(
    insights: RecordInsights,
    clock: java.time.ZoneId,
    onSelectMoment: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        insights.comparison?.takeIf { it.isMeaningful }?.let { comparison ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        ordinal(comparison.peakRank) + " highest of their " +
                            "${comparison.totalRecordings} recordings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            comparison.percentVsAverage > 0 ->
                                "${comparison.percentVsAverage}% above their usual average"
                            comparison.percentVsAverage < 0 ->
                                "${-comparison.percentVsAverage}% below their usual average"
                            else -> "About their usual average"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            InsightStat("Measured", shortDuration(insights.activeDurationMs), Modifier.weight(1f))
            insights.longestClimb?.let { climb ->
                InsightStat(
                    "Biggest climb",
                    "+${climb.riseBpm.roundToInt()} in ${shortDuration(climb.durationMs)}",
                    Modifier.weight(1.4f)
                )
            }
        }

        // Said plainly rather than drawn as an unbroken line. A recording with a dropout is not a
        // recording of a very smooth heart rate.
        if (insights.gaps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${insights.gaps.size} gap${if (insights.gaps.size == 1) "" else "s"}, " +
                    "${shortDuration(insights.missingMs)} not measured",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        val zones = insights.zoneTimes.filter { it.durationMs > 0L }
        if (zones.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Time in range",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            zones.forEach { zone ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${zone.zone.name} (${zone.zone.lowerBpm}" +
                            (zone.zone.upperBpm?.let { "-$it" } ?: "+") + ")",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${shortDuration(zone.durationMs)}  ${(zone.share * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (insights.peaks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Moments",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Above this recording's own average, so a flat recording reports none.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            insights.peaks.forEach { peak ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectMoment(peak.wallClockMs) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        getTimeString(peak.wallClockMs, clock),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "${peak.bpm.roundToInt()} bpm",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
internal fun InsightStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** "1st", "2nd", "23rd" — the ranking reads as a sentence rather than as a number. */
internal fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}


/**
 * Cutting a shorter recording out of a longer one.
 *
 * **The one split dialog.** There were two: this, reached from the overflow menu, and a second one
 * on the chart that opened with the visible window in it. They looked different, worded their
 * refusals differently, and disagreed about what a valid range was — the chart's would not bound
 * the range to the recording and could not be typed in elapsed time; this one could not be seeded
 * from what the chart was showing. Two dialogs for one act is two things to keep in step, and they
 * had already drifted.
 *
 * So it is one dialog with an opening range. The chart hands it the stretch on screen — pinch and
 * pan *is* the coarse selection, done against the curve — and the menu hands it the whole
 * recording. Everything after that is the same either way.
 *
 * Laid out down the page with full-size fields. The first version reused the graph screen's zoom
 * controls, which put two fields side by side with 10sp labels inside an already-narrow dialog —
 * legible on a wide screen with a mouse, and unusable on a phone with a thumb.
 *
 * Times are read and written in **the recording's own clock**, not the reader's, so a set watched
 * at the Gorge is entered in the times it happened at — the same clock the header above it prints.
 *
 * @param initialFromMs offset from the recording's start, or null for the whole thing.
 * @param initialToMs offset from the recording's start, or null for the whole thing.
 * @param onSplit offsets from the recording's start; see [inga.bpmetrics.library.RecordSplit].
 */
@Composable
internal fun SplitRecordDialog(
    record: inga.bpmetrics.library.BpmRecordWithPoints,
    initialFromMs: Long? = null,
    initialToMs: Long? = null,
    onDismiss: () -> Unit,
    onSplit: (fromMs: Long, toMs: Long) -> Unit
) {
    val durationMs = record.metadata.durationMs
    val clock = record.clock
    // Clamped, because the chart's window is free to show air either side of a short recording and
    // a dialog that opens already refusing itself reads as broken.
    val openFrom = (initialFromMs ?: 0L).coerceIn(0L, durationMs)
    val openTo = (initialToMs ?: durationMs).coerceIn(openFrom, durationMs)

    // Clock when the caller had instants in mind — the chart — and elapsed otherwise. Someone who
    // just pinched to a stretch is thinking "that bit, there", which is a wall-clock thought.
    var useClock by remember { mutableStateOf(initialFromMs != null) }

    fun render(offsetMs: Long): String = if (useClock) {
        TimeUtils.formatClockTime(record.metadata.startTime + offsetMs, clock)
    } else {
        TimeUtils.formatMs(offsetMs)
    }

    var startText by remember { mutableStateOf(render(openFrom)) }
    var endText by remember { mutableStateOf(render(openTo)) }

    fun parse(text: String): Long? = if (useClock) {
        // Anchored on the recording's start, so a time after midnight resolves to the small hours
        // of the night the recording was made rather than to that morning.
        TimeUtils.parseClockTimeToRelativeMs(text, record.metadata.startTime, clock)
    } else {
        TimeUtils.parseToMs(text)
    }

    val startMs = parse(startText)
    val endMs = parse(endText)
    // The example is this recording's own start, not a made-up time: someone whose times are being
    // refused needs to see the shape the field wants, and the shape depends on the clock the
    // library is set to read in.
    val example = if (useClock) {
        TimeUtils.formatClockTime(record.metadata.startTime, clock)
    } else {
        TimeUtils.formatMs(durationMs)
    }
    val refusal = if (startMs == null || endMs == null) {
        "Times read like $example."
    } else {
        inga.bpmetrics.library.RecordSplit.refusal(record, startMs, endMs)
    }
    val kept = if (startMs != null && endMs != null) {
        inga.bpmetrics.library.RecordSplit.readingsIn(record, startMs, endMs)
    } else 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split this recording") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Makes a new recording from part of this one. The original is left alone, and " +
                        "the copy keeps its watch, its wearer and its tags.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enter times as", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    // Two ways to say the same instant. Elapsed is what the graph shows; clock is
                    // what someone remembers about when a set started. Switching rewrites both
                    // fields from the offsets they currently mean, so nothing is lost in the swap —
                    // and falls back to the ends of the recording where a field is unreadable.
                    androidx.compose.material3.FilterChip(
                        selected = !useClock,
                        onClick = {
                            if (useClock) {
                                val from = startMs ?: 0L
                                val to = endMs ?: durationMs
                                useClock = false
                                startText = TimeUtils.formatMs(from)
                                endText = TimeUtils.formatMs(to)
                            }
                        },
                        label = { Text("Elapsed") }
                    )
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.FilterChip(
                        selected = useClock,
                        onClick = {
                            if (!useClock) {
                                val base = record.metadata.startTime
                                val from = startMs ?: 0L
                                val to = endMs ?: durationMs
                                useClock = true
                                startText = TimeUtils.formatClockTime(base + from, clock)
                                endText = TimeUtils.formatClockTime(base + to, clock)
                            }
                        },
                        label = { Text("Clock") }
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("From") },
                    singleLine = true,
                    isError = startMs == null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("To") },
                    singleLine = true,
                    isError = endMs == null,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    // Says what the split will actually contain before it happens, rather than
                    // leaving someone to find out from the library that they cut an empty range.
                    refusal ?: "$kept reading${if (kept == 1) "" else "s"}, " +
                        shortDuration(endMs!! - startMs!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (refusal == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSplit(startMs!!, endMs!!) },
                enabled = refusal == null
            ) { Text("Create recording") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Title, description and who wore it.
 *
 * The old screen edited these inline: tapping Edit turned the app bar title into a text field and
 * grew the page underneath it. That app bar now belongs to the analysis as much as to the subject,
 * and a field sprouting from it made the whole page jump. A dialog says what it is doing.
 */
@Composable
internal fun RecordEditDialog(
    record: inga.bpmetrics.library.BpmRecordWithPoints,
    people: List<inga.bpmetrics.library.PersonEntity>,
    locations: List<inga.bpmetrics.library.LocationEntity>,
    /** The venue it would inherit, named so "use the event's" is a visible choice. */
    inheritedPlaceName: String?,
    /** How many tags it carries, and the way through to changing them. */
    tagCount: Int = 0,
    onEditTags: (() -> Unit)? = null,
    /** Its *own* picture, not an inherited one — removing what you did not set makes no sense. */
    /** See [inga.bpmetrics.ui.components.CoverEditor]. */
    coverEditor: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        description: String,
        deviceId: String,
        personId: Long?,
        locationId: Long?
    ) -> Unit
) {
    var title by remember { mutableStateOf(record.metadata.title) }
    var description by remember { mutableStateOf(record.metadata.description) }
    var personId by remember { mutableStateOf(record.metadata.personId) }
    var locationId by remember { mutableStateOf(record.metadata.locationId) }
    val deviceId = record.metadata.deviceId

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit recording") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Text("Who wore it", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                // Everyone listed rather than hidden behind a dropdown: with a handful of friends
                // the whole set fits, and choosing becomes one tap.
                inga.bpmetrics.ui.components.PersonPicker(
                    people = people,
                    selectedId = personId,
                    onSelect = { personId = it }
                )

                Spacer(Modifier.height(16.dp))
                Text("Where it was", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                // In here rather than behind its own menu row. A venue is part of what a
                // recording *is*, and a menu that grows an entry per property is a menu that
                // eventually holds the whole editor one field at a time.
                PlaceChoices(
                    locations = locations,
                    selectedId = locationId,
                    inheritedName = inheritedPlaceName,
                    onSelect = { locationId = it }
                )

                coverEditor?.let { editor ->
                    Spacer(Modifier.height(16.dp))
                    Text("Photo", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    editor()
                }

                onEditTags?.let { edit ->
                    Spacer(Modifier.height(16.dp))
                    Text("Tags", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    // A door rather than the picker itself: choosing tags is its own dialog with
                    // its own axes, and nesting that inside this one would be a dialog in a
                    // dialog. It belongs on this list because a tag is part of what a recording
                    // is, which is what this editor is for.
                    androidx.compose.material3.OutlinedButton(onClick = edit) {
                        Text(
                            if (tagCount == 0) "Add tags"
                            else "$tagCount tag" + if (tagCount == 1) "" else "s"
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(title, description, deviceId, personId, locationId)
            }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The way up from a recording, as the header's trail wants it.
 *
 * Outermost first, so it reads left to right the way the breadcrumb above an event does. Empty when
 * the recording is unfiled, which draws nothing rather than a row of empty chips.
 */
internal fun RecordPlacement.trail(): List<Pair<Long, String>> = buildList {
    group?.let { add(it.eventId to it.displayName) }
    event?.let { add(it.eventId to it.displayName) }
}

/**
 * Choosing a venue, as a row of chips inside an editor.
 *
 * The dialog this replaced was reachable only from a menu, which put one field behind two taps and
 * its own screen. Chips because the list is short — venues are a registry someone curates, not a
 * search — and because "inherit" has to be visibly one of the choices rather than the absence of
 * one.
 */
@Composable
internal fun PlaceChoices(
    locations: List<inga.bpmetrics.library.LocationEntity>,
    selectedId: Long?,
    inheritedName: String?,
    onSelect: (Long?) -> Unit
) {
    inga.bpmetrics.ui.components.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.material3.FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text(inheritedName?.let { "Inherit — $it" } ?: "Not set") }
        )
        locations.forEach { place ->
            androidx.compose.material3.FilterChip(
                selected = selectedId == place.locationId,
                onClick = { onSelect(place.locationId) },
                label = { Text(place.displayName) }
            )
        }
    }
    if (locations.isEmpty()) {
        Text(
            "No venues yet. Make one under Locations in the menu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
