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
import inga.bpmetrics.core.BpmPalette
import inga.bpmetrics.library.ZoneTime
import inga.bpmetrics.ui.components.FlowRow
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
    // Coloured *before* filtering, and by position in the scheme rather than by name. A band's
    // colour is a property of the band — the fourth of four is the hot end whatever else happened —
    // and colouring the filtered list would have made Peak a different colour on an evening where
    // nobody entered the Light band. Position also means a custom set of bands simply works, where
    // the previous `when` on the name matched four literals and sent anything else to a default.
    val zones = zoneTimes
        .mapIndexed { index, time -> time to Color(BpmPalette.zone(index, zoneTimes.size)) }
        .filter { (time, _) -> time.durationMs > 0L }
    if (zones.isEmpty()) return

    androidx.compose.foundation.layout.Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (showDurations) 14.dp else 8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
        ) {
            zones.forEach { (zone, colour) ->
                Box(
                    Modifier
                        // A band with a sliver of time still has to be visible, or the bar implies
                        // it was never entered at all.
                        .weight(zone.share.coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(colour.copy(alpha = alpha))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            zones.forEach { (zone, colour) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colour.copy(alpha = alpha))
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

// `zoneColour(name)` lived here: a `when` over four string literals, with `BpmHigh` at 70% alpha
// standing in for the third band and an `else` quietly catching anything it did not recognise. The
// scheme is defined in [inga.bpmetrics.core.BpmPalette.zone] now, keyed on where a band sits rather
// than on what it is called — §5 asks for zones to be deliberate, and a name match is the opposite:
// rename a band, or add a fifth, and the colours silently stop meaning anything.
