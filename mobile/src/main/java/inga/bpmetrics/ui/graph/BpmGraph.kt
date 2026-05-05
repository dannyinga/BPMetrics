package inga.bpmetrics.ui.graph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow
import kotlin.math.abs

/**
 * A non-interactive preview of the heart rate graph.
 * Clicking this should navigate to the detailed graph view.
 * 
 * Styled as a clickable card to indicate to the user that they can tap for more details.
 */
@Composable
fun BpmGraphPreview(
    record: BpmRecord,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (record.dataPoints.isEmpty() || record.maxDataPoint == null || record.minDataPoint == null) return

    val state = rememberGraphState(totalDuration = record.metadata.durationMs)

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GraphRenderer(
                record = record,
                state = state,
                modifier = Modifier.height(230.dp), // Reduced height to ensure footer fits in constrained parents
                isInteractive = false
            )
            
            Spacer(Modifier.height(16.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Graph Details & Export",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * The full interactive BPM Graph feature. 
 * 
 * This component coordinates between the [GraphState] (logic), [GraphRenderer] (visuals),
 * and [GraphManualControls] (user input) to provide an interactive heart rate visualization.
 *
 * @param record The [BpmRecord] to be visualized.
 * @param modifier The modifier to be applied to the outer layout.
 * @param highlightTimestamp Optional timestamp (ms) to highlight on the graph.
 * @param state The state holder for transformations and selection.
 */
@Composable
fun BpmGraph(
    record: BpmRecord,
    modifier: Modifier = Modifier,
    highlightTimestamp: Long? = null,
    state: GraphState = rememberGraphState(totalDuration = record.metadata.durationMs)
) {
    if (record.dataPoints.isEmpty() || record.maxDataPoint == null || record.minDataPoint == null) return

    // React to external highlight requests
    LaunchedEffect(highlightTimestamp) {
        highlightTimestamp?.let { state.highlightTimestamp(it) }
    }

    // Calculate derived data for the inspection readout
    val currentInspectedBpm = remember(state.inspectedTimeMs) {
        state.inspectedTimeMs?.let { ms ->
            record.dataPoints.minByOrNull { abs(it.timestamp - ms) }?.bpm
        }
    }
    
    val currentInspectedTimeText = remember(state.inspectedTimeMs) {
        state.inspectedTimeMs?.let { TimeUtils.formatMs(it) }
    }

    val isSelecting = state.selectionStartMs != null && state.selectionEndMs != null

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Render the interactive Canvas
        GraphRenderer(
            record = record,
            state = state,
            modifier = Modifier.height(350.dp),
            isInteractive = true
        )

        // Zoom Slider - Placed above Timeline to prevent jitter when Timeline appears
        val currentDuration = state.zoomRange.last - state.zoomRange.first
        Column(modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()) {
            Text(
                "Zoom Level",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Logarithmic-like zoom feel: slider 0..1 maps to duration totalDuration..2s
            val currentZoomLevel = 1f - (currentDuration.toFloat() / state.totalDuration)
            Slider(
                value = currentZoomLevel,
                onValueChange = { zoomFactor ->
                    // zoomFactor 0 -> duration = totalDuration
                    // zoomFactor 1 -> duration = 2000ms
                    val newDuration = (state.totalDuration - (zoomFactor * (state.totalDuration - 2000L))).toLong()
                    val center = (state.zoomRange.first + state.zoomRange.last) / 2
                    var newStart = center - (newDuration / 2)
                    var newEnd = newStart + newDuration

                    // Clamp to bounds
                    if (newStart < 0) {
                        newStart = 0
                        newEnd = newDuration
                    }
                    if (newEnd > state.totalDuration) {
                        newEnd = state.totalDuration
                        newStart = newEnd - newDuration
                    }
                    state.zoomRange = newStart..newEnd
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Timeline Slider for Panning - Animated appearance
        AnimatedVisibility(
            visible = currentDuration < state.totalDuration,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()) {
                Text(
                    "Timeline Position",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.zoomRange.first.toFloat(),
                    onValueChange = { newStart ->
                        val start = newStart.toLong()
                        state.zoomRange = start..(start + currentDuration).coerceAtMost(state.totalDuration)
                    },
                    valueRange = 0f..(state.totalDuration - currentDuration).toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Display the inspection summary (BPM at current pointer position)
        InspectionSummary(
            timeText = currentInspectedTimeText,
            bpm = currentInspectedBpm,
            avgBpm = record.metadata.avg ?: 100.0,
            highBpmColor = BpmHigh,
            lowBpmColor = BpmLow
        )

        Spacer(Modifier.height(24.dp))

        // Manual H:M:S input controls
        // If selecting, control the selection bounds. Otherwise, control the zoom window.
        GraphManualControls(
            initialStart = if (isSelecting) TimeUtils.formatMs(state.selectionStartMs!!) else TimeUtils.formatMs(state.zoomRange.first),
            initialEnd = if (isSelecting) TimeUtils.formatMs(state.selectionEndMs!!) else TimeUtils.formatMs(state.zoomRange.last),
            labelPrefix = if (isSelecting) "Select" else "Zoom",
            onApply = { start, end -> 
                if (isSelecting) {
                    state.setSelection(start, end)
                } else {
                    state.updateZoom(start, end)
                }
            }
        )
    }
}
