package inga.bpmetrics.ui.record

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getDurationString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow

/**
 * A standard tile for displaying a BPM record in the library list.
 */
@Composable
fun BpmRecordTile(
    record: BpmRecord,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.metadata.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = getDurationString(record.metadata.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = getDateString(record.metadata.date),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = getTimeString(record.metadata.startTime),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            BpmTrio(
                low = record.minDataPoint?.bpm?.toInt() ?: 0,
                avg = record.metadata.avg?.toInt() ?: 0,
                max = record.maxDataPoint?.bpm?.toInt() ?: 0
            )

            if (record.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    record.tags.forEach { tag ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            tonalElevation = 1.dp
                        ) {
                            Text(
                                text = tag.name,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Displays the Low, Avg, and Max BPM with color-coded heart icons.
 * 
 * @param onLowClick Optional callback when the Low metric is clicked.
 * @param onMaxClick Optional callback when the Max metric is clicked.
 * @param iconSize The size of the heart icon.
 * @param fontSize The size of the BPM text.
 */
@Composable
fun BpmTrio(
    low: Int, 
    avg: Int, 
    max: Int,
    onLowClick: (() -> Unit)? = null,
    onMaxClick: (() -> Unit)? = null,
    iconSize: Dp = 16.dp,
    fontSize: TextUnit = 14.sp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BpmMetric(
            value = low, 
            color = BpmLow, 
            label = "Low", 
            iconSize = iconSize, 
            fontSize = fontSize,
            onClick = onLowClick
        )
        
        if (onLowClick == null && onMaxClick == null) {
            Spacer(Modifier.width(16.dp))
        }

        BpmMetric(
            value = avg, 
            color = BpmAvg, 
            label = "Avg", 
            iconSize = iconSize, 
            fontSize = fontSize
        )

        if (onLowClick == null && onMaxClick == null) {
            Spacer(Modifier.width(16.dp))
        }

        BpmMetric(
            value = max, 
            color = BpmHigh, 
            label = "Max", 
            iconSize = iconSize, 
            fontSize = fontSize,
            onClick = onMaxClick
        )
    }
}

@Composable
private fun BpmMetric(
    value: Int, 
    color: Color, 
    label: String,
    iconSize: Dp,
    fontSize: TextUnit,
    onClick: (() -> Unit)? = null
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            color = color.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.2f)),
            modifier = Modifier.padding(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier.size(iconSize)
                )
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize),
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                    Text(
                        text = "View $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(iconSize)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize),
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
