package inga.bpmetrics.ui.graph

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Displays the current heart rate and time at the inspection pointer.
 */
@Composable
fun InspectionSummary(
    timeText: String?,
    bpm: Double?,
    avgBpm: Double,
    highBpmColor: Color,
    lowBpmColor: Color
) {
    if (timeText != null && bpm != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (bpm > avgBpm) highBpmColor else lowBpmColor
            Icon(Icons.Default.Favorite, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "$timeText  |  %.1f BPM".format(bpm),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        Text(
            "Pinch to zoom • Drag anywhere to pan • Tap to place pointer",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Manual H:M:S input controls for zooming the graph.
 */
@Composable
fun GraphManualControls(
    initialStart: String,
    initialEnd: String,
    onApply: (Long, Long) -> Unit
) {
    var startInput by remember(initialStart) { mutableStateOf(initialStart) }
    var endInput by remember(initialEnd) { mutableStateOf(initialEnd) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        OutlinedTextField(
            value = startInput,
            onValueChange = { startInput = it },
            label = { Text("Start (H:M:S)", fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = endInput,
            onValueChange = { endInput = it },
            label = { Text("End (H:M:S)", fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                val start = TimeUtils.parseToMs(startInput) ?: 0L
                val end = TimeUtils.parseToMs(endInput) ?: Long.MAX_VALUE
                onApply(start, end)
            },
            shape = MaterialTheme.shapes.medium
        ) {
            Text("Apply")
        }
    }
}
