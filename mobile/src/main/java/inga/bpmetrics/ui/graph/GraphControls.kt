package inga.bpmetrics.ui.graph

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * Manual H:M:S or Clock input controls for zooming or selecting on the graph.
 */
@Composable
fun GraphManualControls(
    initialStart: String,
    initialEnd: String,
    onApply: (Long, Long) -> Unit,
    labelPrefix: String = "Zoom",
    baseEpochMs: Long? = null
) {
    var useClockTime by remember { mutableStateOf(false) }
    
    var startInput by remember(initialStart, useClockTime) { 
        mutableStateOf(if (useClockTime && baseEpochMs != null) {
            val relativeMs = TimeUtils.parseToMs(initialStart) ?: 0L
            TimeUtils.formatClockTime(baseEpochMs + relativeMs)
        } else initialStart) 
    }
    
    var endInput by remember(initialEnd, useClockTime) { 
        mutableStateOf(if (useClockTime && baseEpochMs != null) {
            val relativeMs = TimeUtils.parseToMs(initialEnd) ?: 0L
            TimeUtils.formatClockTime(baseEpochMs + relativeMs)
        } else initialEnd) 
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = startInput,
                onValueChange = { startInput = it },
                label = { Text("$labelPrefix Start (${if (useClockTime) "Clock" else "Relative"})", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall,
                trailingIcon = {
                    if (useClockTime) {
                        Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Default.Timer, null, modifier = Modifier.size(16.dp))
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = endInput,
                onValueChange = { endInput = it },
                label = { Text("$labelPrefix End", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall
            )
            
            if (baseEpochMs != null) {
                IconButton(onClick = { useClockTime = !useClockTime }) {
                    Icon(
                        imageVector = if (useClockTime) Icons.Default.Timer else Icons.Default.Schedule,
                        contentDescription = "Toggle Clock/Relative"
                    )
                }
            }

            Button(
                onClick = {
                    val start = if (useClockTime && baseEpochMs != null) {
                        TimeUtils.parseClockTimeToRelativeMs(startInput, baseEpochMs) ?: 0L
                    } else {
                        TimeUtils.parseToMs(startInput) ?: 0L
                    }
                    
                    val end = if (useClockTime && baseEpochMs != null) {
                        TimeUtils.parseClockTimeToRelativeMs(endInput, baseEpochMs) ?: Long.MAX_VALUE
                    } else {
                        TimeUtils.parseToMs(endInput) ?: Long.MAX_VALUE
                    }
                    onApply(start, end)
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Apply")
            }
        }
    }
}
