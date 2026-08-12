package inga.bpmetrics.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmLow
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.MetricNumerals

/**
 * Choosing what to compare along.
 *
 * One is always selected. Compare is a tab now, and arriving at it is the act of asking to compare
 * — so opening on a row of chips above nothing made the reader tap once before the screen would
 * say anything, and tapping the selected chip to clear it put them straight back there.
 *
 * Only axes the scope can actually be compared along appear; see [AnalysisSplit.axesFor]. An empty
 * row draws nothing rather than a row of nothing.
 *
 * No heading. It sits under the measure control on a tab called Compare, which has already said
 * what these are for twice over.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitAxisPicker(
    axes: List<SplitAxis>,
    selected: SplitAxis?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (axes.isEmpty()) return

    Column(modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            axes.forEach { axis ->
                FilterChip(
                    selected = selected?.key == axis.key,
                    onClick = { onSelect(axis.key) },
                    label = { Text(axis.label) }
                )
            }
        }
    }
}

/**
 * The comparison itself: one lane per value, worst to best.
 *
 * The feature the taxonomy document exists for. "Compare my rate across characters" used to be two
 * filter operations and a memory test; this is the answer laid out side by side.
 *
 * Each lane shows its own summary rather than a share of a total, because a share invites the
 * question of what it is a share *of* — and the honest answer varies by metric. A peak is not
 * divisible; time is.
 */
@Composable
fun SplitLanes(
    lanes: List<SplitLane>,
    /** Rate or time-in-band. Two questions about one set of lanes — see [CompareMeasure]. */
    measure: CompareMeasure = CompareMeasure.RATE,
    /** Which rate, when the measure is a rate. */
    metric: AnalysisViewModel.MetricType = AnalysisViewModel.MetricType.HIGH,
    /**
     * Opens a lane that is a single recording.
     *
     * A lane holding one recording *is* that recording, whatever axis produced it, so tapping it
     * goes there. This is what is left of the Recordings tab, which was a list of recordings ranked
     * by a metric — that is to say, a comparison, drawn a second way.
     */
    onOpenRecord: ((Long) -> (() -> Unit)?)? = null,
    modifier: Modifier = Modifier
) {
    if (lanes.isEmpty()) return

    // Zero to the highest figure in the comparison. Bars used to be scaled between the *lowest* and
    // the highest lane, on the reasoning that nobody's heart rate starts at zero so a zero-based bar
    // would make every lane look alike. That is true and it was the wrong trade: with two lanes the
    // lower one is pinned at the floor and the higher at the ceiling by construction, so 151 against
    // 152 drew as nothing against everything. A bar that says "a dead heat is a landslide" is worse
    // than one that says two close numbers look close.
    //
    // So a bar is a proportion of the largest, which is a thing that can be read off the screen
    // without knowing what the other end of the scale happens to be.
    val ceiling = lanes.mapNotNull { it.valueFor(metric) }.maxOrNull() ?: 0.0

    Column(modifier.fillMaxWidth()) {
        lanes.forEach { lane ->
            LaneRow(
                lane = lane,
                measure = measure,
                metric = metric,
                ceiling = ceiling,
                // Null where that recording is the page we are already on, so the row does not
                // offer to navigate to itself.
                onOpen = lane.records.singleOrNull()
                    ?.recordId
                    ?.let { id -> onOpenRecord?.invoke(id) }
            )
        }
    }
}

/** The figure this lane contributes for [metric]. */
private fun SplitLane.valueFor(metric: AnalysisViewModel.MetricType): Double? = when (metric) {
    AnalysisViewModel.MetricType.LOW -> minBpm
    AnalysisViewModel.MetricType.AVG -> avgBpm
    AnalysisViewModel.MetricType.HIGH -> maxBpm
}

/**
 * One lane, as a row rather than a card.
 *
 * Cards were the wrong container. A comparison is read down a column — this against that against
 * the next — and a stack of bordered boxes each holding a label, a count, a figure and a bar broke
 * that column into panels, so the eye had to re-find the number in every one. The recordings list
 * had it right: a dot, a name, a figure at a fixed right margin, and the eye runs straight down.
 *
 * The figure carries the whole label. There is no "Peak 186" because the control at the top of the
 * tab already says which figure is on show, and repeating it on every row was the same word thirty
 * times down the screen.
 */
@Composable
private fun LaneRow(
    lane: SplitLane,
    measure: CompareMeasure,
    metric: AnalysisViewModel.MetricType,
    ceiling: Double,
    onOpen: (() -> Unit)?
) {
    val tone = when (metric) {
        AnalysisViewModel.MetricType.LOW -> BpmLow
        AnalysisViewModel.MetricType.AVG -> BpmAvg
        AnalysisViewModel.MetricType.HIGH -> BpmHigh
    }
    // The unlabelled lane is present so the totals are honest, not as a result. Quieter, so it does
    // not read as having won anything.
    val alpha = if (lane.isUnlabelled) 0.5f else 1f

    Column(
        Modifier
            .fillMaxWidth()
            .let { if (onOpen != null) it.clickable(onClick = onOpen) else it }
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    // A circle, said as one. It was `RoundedCornerShape(5.dp)` on a 10dp box —
                    // the same shape, expressed as a number that has to be kept in step with the
                    // size above it, which is how a dot becomes a lozenge when someone resizes it.
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background((lane.colorArgb?.let { Color(it) } ?: tone).copy(alpha = alpha))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    lane.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    lane.detail(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1
                )
            }
            if (measure == CompareMeasure.RATE) {
                Text(
                    lane.valueFor(metric)?.toInt()?.toString() ?: "—",
                    // Tabular figures, as everywhere numbers appear, so a column of lanes lines up
                    // rather than jittering between 98 and 188.
                    style = MaterialTheme.typography.titleMedium
                        .copy(fontFeatureSettings = MetricNumerals),
                    fontWeight = FontWeight.Bold,
                    color = tone.copy(alpha = alpha)
                )
            }
        }

        // One measure at a time. A row carrying three figures *and* a band breakdown said several
        // things at once and no single thing clearly, which is what made comparing hard work rather
        // than a glance.
        when (measure) {
            CompareMeasure.RATE -> lane.valueFor(metric)?.let { value ->
                Spacer(Modifier.height(6.dp))
                LaneBar(value = value, ceiling = ceiling, tone = tone)
            }

            CompareMeasure.ZONES -> {
                Spacer(Modifier.height(6.dp))
                ZoneBreakdown(
                    inga.bpmetrics.library.BpmZones.merge(lane.records.map { it.zoneTimes }),
                    showDurations = false
                )
            }
        }
    }
}

/**
 * The line under a lane's name.
 *
 * A lane of one is that recording, so it says who and where — the same line the recordings list
 * showed — rather than "1 recording", which is a count of something already on screen.
 */
private fun SplitLane.detail(): String {
    val single = records.singleOrNull()
    if (single != null) {
        val context = listOfNotNull(
            single.wearerName.takeIf { it.isNotBlank() },
            single.eventName.takeIf { it.isNotBlank() && it != value }
        ).joinToString(" · ")
        if (context.isNotEmpty()) return context
    }
    return "$count recording${if (count == 1) "" else "s"}"
}

/**
 * How this lane sits against the others: zero on the left, the highest in the comparison on the
 * right.
 *
 * So a bar is a *proportion*, and two lanes a point apart look a point apart. It was scaled between
 * the lowest and the highest lane instead, which makes better use of the width and is a lie about
 * the only case that matters: with two lanes, one is always empty and the other always full, however
 * close they are. 151 against 152 drew as nothing against everything.
 */
@Composable
private fun LaneBar(value: Double, ceiling: Double, tone: Color) {
    val top = ceiling.takeIf { it > 0.5 } ?: 1.0
    val fraction = (value / top).coerceIn(0.0, 1.0).toFloat()

    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                // The colour of the figure it is drawing. It was always the peak colour, so the
                // bar under a column of blue lows was red — three controls saying "low" and one
                // graphic saying otherwise.
                .fillMaxWidth(fraction)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(tone)
        )
    }
}

/**
 * What is in this analysis, and what to leave out.
 *
 * Everything is included until someone says otherwise, and unticking is a fact about *this
 * analysis* rather than about the event — so unless the analysis is saved it is forgotten when the
 * screen closes. An event marked as never counting toward its parent shows as unticked and says so,
 * because otherwise the box looks like it unticked itself.
 *
 * **Drawn as the tree it is.** [ScopeRefinement.entriesFor] has always returned a nested walk with
 * a depth on every row, and this dialog rendered them all flush left — so a festival's days, its
 * sets, and the recordings inside those sets arrived as one undifferentiated column of forty
 * checkboxes with nothing saying what belonged to what. The indent is the whole point of the sheet:
 * it is where you see that unticking one row takes six others with it.
 */
@Composable
fun ScopeRefinementDialog(
    entries: List<ScopeEntry>,
    onToggle: (ScopeEntry, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's included") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (entries.isEmpty()) {
                    Text(
                        "Nothing to refine — this analysis covers a single set of recordings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entries.forEach { entry -> ScopeEntryRow(entry, onToggle) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Include all") } }
    )
}

@Composable
private fun ScopeEntryRow(entry: ScopeEntry, onToggle: (ScopeEntry, Boolean) -> Unit) {
    // Dimmed rather than hidden. A row that vanished when you excluded it could never be put back,
    // and a whole subtree vanishing would make the sheet look like it had deleted something.
    val alpha = if (entry.excludedByAncestor) 0.45f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One step per level. A recording three levels down is three steps in, which is the
            // only thing on the row that says which set it belongs to.
            .padding(start = (entry.depth * 20).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = entry.isIncluded,
            // Out because its parent is out. Ticking it alone would claim a scope that holds a set
            // whose day is excluded, which the numbers would not honour — so the way back is the
            // parent's box, and this one says so by being unavailable rather than by springing back.
            enabled = !entry.excludedByAncestor,
            onCheckedChange = { onToggle(entry, it) }
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            val detail = when {
                entry.excludedByAncestor -> "Left out with what it's in"
                entry.recordId != null -> "1 recording"
                else -> buildString {
                    append("${entry.recordCount} recording")
                    if (entry.recordCount != 1) append("s")
                    if (entry.excludedByFlag) append("  ·  always excluded from roll-ups")
                }
            }
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
    }
}

/** "Low", "Average", "Peak" — the one wording, shared by the control and the lanes it drives. */
internal fun metricLabelFor(metric: AnalysisViewModel.MetricType): String = when (metric) {
    AnalysisViewModel.MetricType.LOW -> "Low"
    AnalysisViewModel.MetricType.AVG -> "Average"
    AnalysisViewModel.MetricType.HIGH -> "Peak"
}
