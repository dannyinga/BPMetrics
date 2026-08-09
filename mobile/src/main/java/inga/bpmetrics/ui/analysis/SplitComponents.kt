package inga.bpmetrics.ui.analysis

import androidx.compose.foundation.background
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
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.MetricNumerals

/**
 * Choosing what to compare along.
 *
 * Nothing is selected by default. An analysis opens as a single set of totals — which is the right
 * answer to "how was that night" — and comparing is a question you ask on purpose.
 *
 * Only axes the scope can actually be compared along appear; see [AnalysisSplit.axesFor]. An empty
 * row draws nothing rather than a header over no chips, because "compare by" with nothing after it
 * reads as something failing to load.
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
        Text(
            "Compare by",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            axes.forEach { axis ->
                FilterChip(
                    selected = selected?.key == axis.key,
                    // Tapping the selected chip clears it, so getting back to the plain totals is
                    // the same gesture that got you here.
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
    modifier: Modifier = Modifier
) {
    if (lanes.isEmpty()) return

    // Bars are drawn against the hardest lane rather than against a fixed ceiling, so the
    // comparison uses the full width whatever the numbers happen to be. A 186 next to a 182 is a
    // close race and should look like one.
    val ceiling = lanes.mapNotNull { it.maxBpm }.maxOrNull() ?: 0.0
    val floor = lanes.mapNotNull { it.minBpm }.minOrNull() ?: 0.0

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lanes.forEach { lane ->
            LaneCard(lane, ceiling = ceiling, floor = floor)
        }
    }
}

@Composable
private fun LaneCard(lane: SplitLane, ceiling: Double, floor: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // The unlabelled lane is present so the totals are honest, not as a result. Quieter, so
            // it does not read as having won anything.
            containerColor = if (lane.isUnlabelled) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                lane.colorArgb?.let {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(it))
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    lane.value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${lane.count} recording${if (lane.count == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LaneStat("Peak", lane.maxBpm, BpmHigh)
                LaneStat("Avg", lane.avgBpm, BpmAvg)
                LaneStat("Low", lane.minBpm, MaterialTheme.colorScheme.onSurfaceVariant)
            }

            lane.maxBpm?.let { peak ->
                Spacer(Modifier.height(8.dp))
                LaneBar(value = peak, floor = floor, ceiling = ceiling)
            }
        }
    }
}

@Composable
private fun LaneStat(label: String, value: Double?, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            value?.let { "${it.toInt()}" } ?: "—",
            // Tabular figures, as everywhere numbers appear, so a column of lanes lines up rather
            // than jittering between 98 and 188.
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = MetricNumerals),
            color = tone,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * How this lane's peak sits against the others.
 *
 * Scaled between the lowest and highest across the comparison rather than from zero. Nobody's heart
 * rate starts at zero, so a zero-based bar would make every lane look nearly identical and hide the
 * difference the comparison exists to show.
 */
@Composable
private fun LaneBar(value: Double, floor: Double, ceiling: Double) {
    val span = (ceiling - floor).takeIf { it > 0.5 } ?: 1.0
    val fraction = ((value - floor) / span).coerceIn(0.05, 1.0).toFloat()

    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(BpmHigh)
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
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = entry.isIncluded,
                            onCheckedChange = { onToggle(entry, it) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                            val detail = buildString {
                                append("${entry.recordCount} recording")
                                if (entry.recordCount != 1) append("s")
                                if (entry.excludedByFlag) append("  ·  always excluded from roll-ups")
                            }
                            Text(
                                detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Include all") } }
    )
}
