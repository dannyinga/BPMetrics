package inga.bpmetrics.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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

/**
 * A dialog allowing the user to choose between analyzing the currently filtered list or setting a new filter.
 *
 * @param onDismiss Callback to dismiss the dialog.
 * @param onAnalyzeCurrent Callback when "Analyze Current List" is selected.
 * @param onSelectNewFilter Callback when "Select New Filter" is selected.
 */
@Composable
fun AnalysisFilterDialog(
    onDismiss: () -> Unit,
    onAnalyzeCurrent: () -> Unit,
    onSelectNewFilter: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Data to Analyze") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select a preset or custom filter to start your analysis.", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                TextButton(onClick = onAnalyzeCurrent, modifier = Modifier.fillMaxWidth()) {
                    Text("Analyze Current List", style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(onClick = onSelectNewFilter, modifier = Modifier.fillMaxWidth()) {
                    Text("Select New Filter", style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * A selector item for the analysis trio (Min/Avg/Max).
 */
@Composable
fun AnalysisTrioItem(value: Int, color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Icon(imageVector = Icons.Default.Favorite, contentDescription = null, tint = color, modifier = Modifier.size(if (isSelected) 64.dp else 48.dp))
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

/**
 * A horizontal bar representing a ranking in the categorical analysis.
 * 
 * @param label The name of the tag/category.
 * @param progress The progress value (0.0 to 1.0).
 * @param value The actual BPM value to display.
 * @param color The theme color to use for the bar and text.
 * @param onClick Optional callback triggered when the bar is clicked.
 */
@Composable
fun AnalysisBar(
    label: String, 
    progress: Float, 
    value: Int, 
    color: Color = Color.Red,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Replaced LinearProgressIndicator with a custom bar implementation for a more "graphical" feel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(color)
                )
            }
            Text(value.toString(), fontWeight = FontWeight.Bold, color = color)
        }
    }
}
