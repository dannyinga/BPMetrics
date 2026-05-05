package inga.bpmetrics.ui.graph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
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

    val currentDuration = state.zoomRange.last - state.zoomRange.first

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Render the interactive Canvas
            GraphRenderer(
                record = record,
                state = state,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                isInteractive = true
            )

            // Vertical Zoom Slider - Moved to the right for better UX
            Column(
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Zoom",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Logarithmic-like zoom feel: slider 0..1 maps to duration totalDuration..2s
                val currentZoomLevel = 1f - (currentDuration.toFloat() / state.totalDuration)
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                        modifier = Modifier
                            .graphicsLayer { rotationZ = 270f }
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(
                                    Constraints(
                                        minWidth = 0,
                                        maxWidth = constraints.maxHeight,
                                        minHeight = 0,
                                        maxHeight = constraints.maxWidth
                                    )
                                )
                                layout(placeable.height, placeable.width) {
                                    placeable.placeRelative(
                                        x = -(placeable.width - placeable.height) / 2,
                                        y = (placeable.width - placeable.height) / 2
                                    )
                                }
                            }
                            .fillMaxHeight() // This becomes visual length after rotation logic
                    )
                }
            }
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

        // Manual H:M:S input controls for the Zoom window
        GraphManualControls(
            initialStart = TimeUtils.formatMs(state.zoomRange.first),
            initialEnd = TimeUtils.formatMs(state.zoomRange.last),
            labelPrefix = "Zoom",
            onApply = { start, end -> 
                state.updateZoom(start, end)
            }
        )
    }
}
