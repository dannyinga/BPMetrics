package inga.bpmetrics.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.ZoneTime
import inga.bpmetrics.ui.components.FlowRow
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow
import kotlin.math.roundToInt

/**
 * Time across heart rate bands, as a stacked bar with a legend.
 *
 * One component wherever bands appear — a whole analysis, a person, a ranking row, a lane on the
 * event page — so "40% peak" looks the same and means the same everywhere. The numbers behind it
 * are already unified in [inga.bpmetrics.library.BpmZones]; this is the other half of that.
 *
 * Bands with no time in them are dropped. A legend entry reading zero is noise.
 *
 * @param showDurations Adds the measured time beside each share, for the places with room for it.
 */
@Composable
fun ZoneBreakdown(
    zoneTimes: List<ZoneTime>,
    showDurations: Boolean,
    alpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    val zones = zoneTimes.filter { it.durationMs > 0L }
    if (zones.isEmpty()) return

    androidx.compose.foundation.layout.Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (showDurations) 14.dp else 8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
        ) {
            zones.forEach { zone ->
                Box(
                    Modifier
                        // A band with a sliver of time still has to be visible, or the bar implies
                        // it was never entered at all.
                        .weight(zone.share.coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(zoneColour(zone.zone.name).copy(alpha = alpha))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            zones.forEach { zone ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(zoneColour(zone.zone.name).copy(alpha = alpha))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        buildString {
                            append(zone.zone.name)
                            append(" ")
                            append((zone.share * 100).roundToInt())
                            append("%")
                            if (showDurations) append(" · ${shortDuration(zone.durationMs)}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                    )
                }
            }
        }
    }
}

/**
 * Bands drawn in the app's existing low / average / high palette.
 *
 * Reusing those colours rather than inventing a fourth scheme: they already mean "calm" through
 * "going hard" everywhere else in the app, so the bands read without a key.
 */
@Composable
fun zoneColour(name: String): Color = when (name) {
    "Resting" -> BpmLow
    "Light" -> BpmAvg
    "Elevated" -> BpmHigh.copy(alpha = 0.7f)
    else -> BpmHigh
}
