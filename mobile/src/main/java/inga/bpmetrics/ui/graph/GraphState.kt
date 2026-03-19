package inga.bpmetrics.ui.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import inga.bpmetrics.library.BpmRecord
import kotlin.math.abs

/**
 * Encapsulates the state and transformation logic for the BPM graph.
 * 
 * @param totalDuration The total duration of the record in milliseconds.
 */
class GraphState(val totalDuration: Long) {
    /** The currently visible time range in milliseconds. */
    var zoomRange by mutableStateOf(0L..totalDuration)

    /** Ratio (0..1) relative to graph width where the inspection pointer is locked. */
    var inspectedRatio by mutableStateOf<Float?>(null)

    /** Derived: Actual time in ms currently under the screen pointer. */
    val inspectedTimeMs by derivedStateOf {
        inspectedRatio?.let { ratio ->
            val duration = zoomRange.last - zoomRange.first
            zoomRange.first + (ratio * duration).toLong()
        }
    }

    /**
     * Updates the zoom range based on gesture input.
     */
    fun transform(centroidX: Float, graphWidth: Float, panX: Float, zoom: Float) {
        if (graphWidth <= 0) return

        val currentWidth = zoomRange.last - zoomRange.first
        val newWidth = (currentWidth / zoom).toLong().coerceIn(2000L, totalDuration)

        val relativeCentroidX = (centroidX / graphWidth).coerceIn(0f, 1f)
        val timeAtCentroid = zoomRange.first + (relativeCentroidX * currentWidth)
        
        var newStart = (timeAtCentroid - (relativeCentroidX * newWidth)).toLong()
        var newEnd = newStart + newWidth

        // Apply Pan
        val timePan = -(panX / graphWidth * newWidth).toLong()
        newStart += timePan
        newEnd += timePan

        // Snap to bounds
        if (newStart < 0) {
            newStart = 0
            newEnd = newWidth
        }
        if (newEnd > totalDuration) {
            newEnd = totalDuration
            newStart = newEnd - newWidth
        }

        zoomRange = newStart..newEnd
    }

    /**
     * Updates the zoom range manually from H:M:S inputs.
     */
    fun updateZoom(startMs: Long, endMs: Long) {
        zoomRange = startMs.coerceAtLeast(0L)..endMs.coerceAtMost(totalDuration)
    }

    /**
     * Highlights a specific timestamp by moving the inspection pointer to it.
     * If the timestamp is outside the current zoom range, it expands the range to include it.
     */
    fun highlightTimestamp(timestamp: Long) {
        if (timestamp < zoomRange.first || timestamp > zoomRange.last) {
            // Expand zoom to show everything if we're jumping to a point outside
            zoomRange = 0L..totalDuration
        }

        val duration = zoomRange.last - zoomRange.first
        if (duration > 0) {
            inspectedRatio = (timestamp - zoomRange.first).toFloat() / duration
        }
    }
}

/**
 * Creates and remembers a [GraphState] instance.
 */
@Composable
fun rememberGraphState(totalDuration: Long): GraphState {
    return remember(totalDuration) { GraphState(totalDuration) }
}
