package inga.bpmetrics.ui.graph

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow
import kotlin.math.abs

/**
 * A non-interactive preview of the heart rate graph.
 * Clicking this should navigate to the detailed graph view.
 */
@Composable
fun BpmGraphPreview(
    record: BpmRecord,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (record.dataPoints.isEmpty() || record.maxDataPoint == null || record.minDataPoint == null) return

    val state = rememberGraphState(totalDuration = record.metadata.durationMs)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GraphRenderer(
            record = record,
            state = state,
            modifier = Modifier.height(300.dp),
            isInteractive = false
        )
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

        Spacer(Modifier.height(32.dp))

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
