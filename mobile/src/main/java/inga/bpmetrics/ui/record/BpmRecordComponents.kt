package inga.bpmetrics.ui.record

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Watch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.displayName
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getDurationString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

/**
 * A standard tile for displaying a BPM record in the library list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BpmRecordTile(
    record: BpmRecord,
    isSelected: Boolean = false,
    /**
     * The watch's given name, resolved from the registry by the caller.
     *
     * Unlike the wearer — frozen onto the record when it arrived — a watch's name describes
     * hardware that still exists, so it is resolved live and renaming a watch updates every
     * recording it made. Null falls back to the model the watch reported.
     */
    watchName: String? = null,
    /**
     * Who this belongs to, resolved live from their profile by the caller.
     *
     * Their name rather than the frozen string, so correcting a spelling reaches every recording
     * they made; their colour so a list can be scanned without reading any of it. Null for
     * recordings made before profiles existed, which fall back to the name they were stamped with.
     */
    wearer: PersonEntity? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onLongClick?.invoke() }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
        } else {
            CardDefaults.cardColors()
        },
        // The border belongs to selection. A wearer's colour goes down the left edge instead, so
        // the two never have to compete for the same outline.
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            wearer?.let { person ->
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .fillMaxHeight()
                        .background(Color(person.colorArgb))
                )
            }
            TileBody(record, watchName, wearer)
        }
    }
}

/** How many tag chips a tile shows before it stops and counts the rest. */
private const val TILE_TAG_LIMIT = 3

/** The tile's contents, split out so the colour stripe can sit alongside them. */
@Composable
private fun TileBody(
    record: BpmRecord,
    watchName: String?,
    wearer: PersonEntity?
) {
    Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // Never a bare "Untitled 4" where a wearer is known — see
                        // RecordNameFormatter. A user's own title always wins.
                        text = record.displayName(wearer?.displayName, watchName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        // Two lines, then ellipsis. Titles are free text and some of them are a
                        // sentence; unbounded, one of those grew the tile to four lines and broke
                        // the even rhythm that makes a long list scannable in the first place.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // The live profile name wins over the frozen one, so a correction shows up
                    // here; the frozen name remains for recordings with no profile behind them.
                    val wearerLabel = wearer?.displayName
                        ?: record.metadata.wearerName.takeIf { it.isNotBlank() }
                    // Who wore it and what they wore, each with its own marker. Icons rather
                    // than the emoji these used to be: same cue, but they tint with the theme and
                    // look the same on every phone.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        wearerLabel?.let {
                            inga.bpmetrics.ui.components.BpmIconLabel(Icons.Default.Person, it)
                        }
                        inga.bpmetrics.ui.components.BpmIconLabel(
                            Icons.Default.Watch,
                            record.watchLabel(watchName)
                        )
                    }
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
                    // Three, then a count. Tags are cheap to add and someone with a tagging habit
                    // had tiles where the chips were taller than everything above them — at which
                    // point the tile is a tag list that happens to mention a heart rate.
                    record.tags.take(TILE_TAG_LIMIT).forEach { tag ->
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
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    val hidden = record.tags.size - TILE_TAG_LIMIT
                    if (hidden > 0) {
                        Text(
                            text = "+$hidden",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

/**
 * What to call the watch a recording came from.
 *
 * Prefers the name given in the Watches section, because that is what the user calls it. Falls
 * back to the model the watch reported, which is all an unregistered or imported record has.
 */
fun BpmRecord.watchLabel(watchName: String?): String =
    watchName?.takeIf { it.isNotBlank() }
        ?: metadata.deviceId.takeIf { it.isNotBlank() }
        ?: "Watch"
