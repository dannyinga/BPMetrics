package inga.bpmetrics.ui.detail

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.graph.TimeUtils

/**
 * A dialog allowing the user to configure image export parameters.
 * 
 * @param record The record to export.
 * @param onDismiss Callback to dismiss the dialog.
 * @param onSave Callback to save the generated bitmap locally.
 */
@Composable
fun ImageExportDialog(
    record: BpmRecord,
    onDismiss: () -> Unit,
    onSave: (Bitmap, String) -> Unit
) {
    val context = LocalContext.current
    
    // 1. Image Size (px)
    var widthPx by remember { mutableStateOf("1920") }
    var heightPx by remember { mutableStateOf("1080") }

    // 2. Time Window
    var startInput by remember { mutableStateOf(TimeUtils.formatMs(0L)) }
    var endInput by remember { mutableStateOf(TimeUtils.formatMs(record.metadata.durationMs)) }

    // 3. Background Opacity
    var opacity by remember { mutableFloatStateOf(100f) }

    // 4. Detail Toggles
    var showAxes by remember { mutableStateOf(true) }
    var showLabels by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(true) }
    var showTitle by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Graph as Image", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Resolution Section
                Text("Resolution (Pixels)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthPx,
                        onValueChange = { widthPx = it },
                        label = { Text("Width", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = heightPx,
                        onValueChange = { heightPx = it },
                        label = { Text("Height", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                // Time Window Section
                Text("Time Window", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startInput,
                        onValueChange = { startInput = it },
                        label = { Text("Start (H:M:S)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endInput,
                        onValueChange = { endInput = it },
                        label = { Text("End (H:M:S)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Toggles
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportToggle("Show Title", showTitle) { showTitle = it }
                    ExportToggle("Show Axes", showAxes) { showAxes = it }
                    ExportToggle("Show Labels", showLabels) { showLabels = it }
                    ExportToggle("Show Grid", showGrid) { showGrid = it }
                }

                // Opacity Section
                Column {
                    Text("Background Opacity: ${opacity.toInt()}%", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it },
                        valueRange = 0f..100f
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val config = createConfig(
                        widthPx, heightPx, startInput, endInput, record, 
                        opacity, showAxes, showLabels, showGrid, showTitle
                    )
                    val bitmap = ExportHelpers.renderGraphToBitmap(record, config)
                    onSave(bitmap, record.metadata.title)
                    onDismiss()
                }) {
                    Text("Save")
                }
                
                TextButton(onClick = {
                    val config = createConfig(
                        widthPx, heightPx, startInput, endInput, record, 
                        opacity, showAxes, showLabels, showGrid, showTitle
                    )
                    val bitmap = ExportHelpers.renderGraphToBitmap(record, config)
                    ExportHelpers.shareBitmap(context, bitmap, record.metadata.title)
                    onDismiss()
                }) {
                    Text("Share")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ExportToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun createConfig(
    widthPx: String,
    heightPx: String,
    startInput: String,
    endInput: String,
    record: BpmRecord,
    opacity: Float,
    showAxes: Boolean,
    showLabels: Boolean,
    showGrid: Boolean,
    showTitle: Boolean
): ExportHelpers.ImageExportConfig {
    val w = widthPx.toIntOrNull() ?: 1920
    val h = heightPx.toIntOrNull() ?: 1080
    val startTime = TimeUtils.parseToMs(startInput) ?: 0L
    val endTime = TimeUtils.parseToMs(endInput) ?: record.metadata.durationMs
    
    return ExportHelpers.ImageExportConfig(
        width = w,
        height = h,
        timeRange = startTime..endTime,
        backgroundOpacity = opacity.toInt(),
        showAxes = showAxes,
        showLabels = showLabels,
        showGrid = showGrid,
        showTitle = showTitle
    )
}
