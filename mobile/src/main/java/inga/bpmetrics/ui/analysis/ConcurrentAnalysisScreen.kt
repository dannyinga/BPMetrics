package inga.bpmetrics.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import kotlin.math.roundToInt

/**
 * Everyone's heart rate through one shared stretch of time.
 *
 * The other analysis answers "how did these recordings compare"; this answers "what did we all do
 * at that moment". Scrubbing the chart reads every wearer at one instant, and the moments list
 * points at the times the group reacted together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcurrentAnalysisScreen(
    analysis: ConcurrentAnalysis,
    title: String,
    records: List<BpmRecord> = emptyList(),
    /**
     * Heading for an exported video. A saved analysis passes its name; an unsaved one has none
     * to give, so the export falls back to its generic label.
     */
    graphTitle: String? = null,
    /** Null when viewing an analysis that has already been saved. */
    onSave: ((String) -> Unit)? = null,
    onExportVideo: ((List<BpmRecord>, String?) -> Unit)? = null,
    onOpenDrawer: () -> Unit
) {
    var scrubbedMs by remember { mutableStateOf<Long?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    val viewWindow = rememberConcurrentViewWindow(analysis)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                    }
                },
                actions = {
                    if (!analysis.isEmpty && onExportVideo != null && records.isNotEmpty()) {
                        IconButton(onClick = { onExportVideo(records, graphTitle) }) {
                            Icon(Icons.Default.Movie, contentDescription = "Export as video")
                        }
                    }
                    if (!analysis.isEmpty && onSave != null) {
                        IconButton(onClick = { showSaveDialog = true }) {
                            Icon(Icons.Default.Save, contentDescription = "Save this analysis")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (analysis.isEmpty) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No recordings in this window.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(32.dp)
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "${getTimeString(analysis.windowStartMs)} – ${getTimeString(analysis.windowEndMs)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!analysis.hasOverlap) {
                    Text(
                        "These recordings never ran at the same time, so there is nothing shared " +
                            "to compare. The curves are shown as they happened.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            item {
                ConcurrentChart(
                    analysis = analysis,
                    window = viewWindow,
                    scrubbedMs = scrubbedMs,
                    onScrub = { scrubbedMs = it }
                )
            }

            // Panning belongs on its own control rather than on the chart, where a drag already
            // means "read this moment".
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (viewWindow.isZoomed) {
                                "Showing ${getTimeString(viewWindow.startMs)} – ${getTimeString(viewWindow.endMs)}"
                            } else {
                                "Pinch the chart to zoom"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (viewWindow.isZoomed) {
                            TextButton(onClick = { viewWindow.reset() }) { Text("Show all") }
                        }
                    }

                    if (viewWindow.isZoomed) {
                        Slider(
                            value = viewWindow.scrollFraction,
                            onValueChange = { viewWindow.scrollTo(it) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Reading everyone at one instant is the whole point of overlaying them, so the
            // legend doubles as a live readout while scrubbing.
            item {
                ReadoutLegend(analysis = analysis, at = scrubbedMs)
            }

            if (analysis.peaks.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Moments you reacted together",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Ranked by how far each person was into their own range, so one person " +
                            "being fitter than another does not decide the result.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                items(analysis.peaks) { moment ->
                    MomentRow(
                        moment = moment,
                        isSelected = scrubbedMs == moment.wallClockMs,
                        onClick = { scrubbedMs = moment.wallClockMs }
                    )
                }
            }
        }
    }

    if (showSaveDialog && onSave != null) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Keep this as an event") },
            text = {
                Column {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Event name") },
                        placeholder = { Text("Subtronics 2026") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Files these recordings under an event in your Library. The event page " +
                            "shows one lane per person rather than one per recording, so a watch " +
                            "that dropped out and restarted stays a single curve.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The name is also used as the heading when you export it as a video.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = saveName.isNotBlank(),
                    onClick = {
                        onSave(saveName)
                        saveName = ""
                        showSaveDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Who is in the analysis, and what each of them was doing at the scrubbed instant.
 */
@Composable
private fun ReadoutLegend(analysis: ConcurrentAnalysis, at: Long?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = at?.let { "At ${getTimeString(it)}" } ?: "Tap or drag the chart to read a moment",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            analysis.series.forEach { series ->
                val bpm = at?.let { series.bpmAt(it) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(series.colorArgb))
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = series.label,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // Which watch, so identical hardware or a repeated name still resolves to
                        // one person's curve.
                        series.watchLabel?.let { watch ->
                            Text(
                                text = "⌚ $watch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = when {
                            at == null -> "${series.minBpm.roundToInt()}–${series.maxBpm.roundToInt()}"
                            bpm == null -> "—"
                            else -> "${bpm.roundToInt()} bpm"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun MomentRow(moment: GroupMoment, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (isSelected) {
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        } else {
            androidx.compose.material3.CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = getTimeString(moment.wallClockMs),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${moment.participants} wearing watches",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "${moment.intensityPercent}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
