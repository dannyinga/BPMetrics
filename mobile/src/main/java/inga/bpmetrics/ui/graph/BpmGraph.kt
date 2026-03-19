package inga.bpmetrics.ui.graph

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
 * The main container for the BPM Graph feature. 
 * 
 * This component coordinates between the [GraphState] (logic), [GraphRenderer] (visuals),
 * and [GraphManualControls] (user input) to provide an interactive heart rate visualization.
 *
 * @param record The [BpmRecord] to be visualized.
 * @param modifier The modifier to be applied to the outer layout.
 * @param highlightTimestamp Optional timestamp (ms) to highlight on the graph.
 */
@Composable
fun BpmGraph(
    record: BpmRecord,
    modifier: Modifier = Modifier,
    highlightTimestamp: Long? = null
) {
    if (record.dataPoints.isEmpty() || record.maxDataPoint == null || record.minDataPoint == null) return

    // 1. Initialize the state holder for transformations and selection
    val state = rememberGraphState(totalDuration = record.metadata.durationMs)

    // 2. React to external highlight requests (Requirement: clickable Trio)
    LaunchedEffect(highlightTimestamp) {
        highlightTimestamp?.let { state.highlightTimestamp(it) }
    }

    // 3. Calculate derived data for the inspection readout
    val currentInspectedBpm = remember(state.inspectedTimeMs) {
        state.inspectedTimeMs?.let { ms ->
            record.dataPoints.minByOrNull { abs(it.timestamp - ms) }?.bpm
        }
    }
    
    val currentInspectedTimeText = remember(state.inspectedTimeMs) {
        state.inspectedTimeMs?.let { TimeUtils.formatMs(it) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 4. Render the interactive Canvas
        GraphRenderer(
            record = record,
            state = state,
            modifier = Modifier.height(300.dp)
        )

        Spacer(Modifier.height(32.dp))

        // 5. Display the inspection summary (BPM at current pointer position)
        InspectionSummary(
            timeText = currentInspectedTimeText,
            bpm = currentInspectedBpm,
            avgBpm = record.metadata.avg ?: 100.0,
            highBpmColor = BpmHigh,
            lowBpmColor = BpmLow
        )

        Spacer(Modifier.height(24.dp))

        // 6. Manual H:M:S input controls for precision zooming
        GraphManualControls(
            initialStart = TimeUtils.formatMs(state.zoomRange.first),
            initialEnd = TimeUtils.formatMs(state.zoomRange.last),
            onApply = { start, end -> state.updateZoom(start, end) }
        )
    }
}
