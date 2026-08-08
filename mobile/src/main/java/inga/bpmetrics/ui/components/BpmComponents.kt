package inga.bpmetrics.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.theme.MetricLarge
import inga.bpmetrics.ui.theme.MetricMedium
import inga.bpmetrics.ui.theme.MetricSmall

/**
 * The pieces every screen was building for itself.
 *
 * Six hand-written empty states with different wording and spacing, three section headers, and the
 * min/avg/max trio laid out four separate ways. None of those were wrong individually; together
 * they meant the app looked like it had been assembled rather than designed.
 *
 * One definition each, so a change to how the app presents something is a change in one place.
 */

/**
 * Nothing here yet, and what to do about it.
 *
 * Always says what *belongs* here rather than only that there is none of it, and offers the action
 * that creates the first one where there is one to offer. An empty screen that only apologises
 * leaves someone to guess what they were supposed to have done.
 */
@Composable
fun BpmEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(18.dp))
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * A section of a list with nothing in it, where the rest of the list has something.
 *
 * Distinct from [BpmEmptyState] and deliberately so: that one owns the screen and is centred in it,
 * this one is a row among other rows. Forcing a single component to be both would give whichever
 * case lost the argument the wrong alignment and the wrong weight.
 */
@Composable
fun BpmEmptySection(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = BpmSpacing.Small)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(BpmSpacing.Tiny))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A heading over a run of rows, with how many of them there are.
 *
 * The count is part of the header rather than a separate line because "Events 12" is one fact and
 * splitting it across two lines made three screens lay it out three ways.
 */
@Composable
fun BpmSectionHeader(
    title: String,
    count: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        count?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** How prominent a reading is, which decides its size and whether it carries colour. */
enum class BpmStatSize { LARGE, MEDIUM, SMALL }

/**
 * One reading, with what it is a reading of.
 *
 * Tabular figures, always. Proportional digits are different widths, so a column of these fails to
 * line up and a live one visibly jitters as it counts — which is the whole reason the metric
 * styles exist.
 */
@Composable
fun BpmStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    tone: Color? = null,
    size: BpmStatSize = BpmStatSize.MEDIUM
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = when (size) {
                    BpmStatSize.LARGE -> MetricLarge
                    BpmStatSize.MEDIUM -> MetricMedium
                    BpmStatSize.SMALL -> MetricSmall
                },
                color = tone ?: MaterialTheme.colorScheme.onSurface
            )
            unit?.let {
                Spacer(Modifier.size(3.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}

/**
 * A reading on its own, where the surrounding text already says what it is.
 *
 * The same tabular figures as [BpmStatTile] without the label, for a card that has its own heading.
 */
@Composable
fun BpmMetricText(
    value: String,
    modifier: Modifier = Modifier,
    tone: Color? = null,
    size: BpmStatSize = BpmStatSize.LARGE
) {
    Text(
        value,
        modifier = modifier,
        style = when (size) {
            BpmStatSize.LARGE -> MetricLarge
            BpmStatSize.MEDIUM -> MetricMedium
            BpmStatSize.SMALL -> MetricSmall
        },
        color = tone ?: MaterialTheme.colorScheme.onSurface
    )
}

/**
 * The spacing scale.
 *
 * Ad-hoc values were in use — 2, 3, 6, 10, 14, 18 — chosen per call site, which is why nothing
 * quite lined up between two screens. Everything new uses one of these.
 */
object BpmSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val XLarge = 24.dp
}
