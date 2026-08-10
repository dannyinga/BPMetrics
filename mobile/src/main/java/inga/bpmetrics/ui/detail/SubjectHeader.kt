package inga.bpmetrics.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.Cover
import inga.bpmetrics.ui.components.CoverBackground
import inga.bpmetrics.ui.components.CoverScrim
import inga.bpmetrics.ui.components.overCover
import inga.bpmetrics.ui.theme.BpmAvg
import inga.bpmetrics.ui.theme.BpmHigh
import inga.bpmetrics.ui.theme.BpmLow

/**
 * What this page is of, over the picture chosen for it.
 *
 * One header, not three. A detail page used to carry the cover as its own block, the name in the
 * app bar, the name *again* underneath it, and a card of counts below that — four places saying
 * what you were already looking at, before a single number appeared.
 *
 * So the cover is the header rather than a thing above it. The name, when and where, the low /
 * average / high, and the tags all sit on it. With no cover the same content sits on plain surface
 * and the page simply starts higher up.
 *
 * The three figures are **information here, not a control**. They were a pinned selector at the top
 * of every analysis page, which is where a sort control does not belong — that moved down to the
 * comparisons it actually sorts.
 *
 * @param onOpenAncestor Tapping a step of the trail walks up. Empty trail draws nothing rather than
 *   an empty row: an absence stated is worse than an absence.
 */
@Composable
fun SubjectHeader(
    title: String,
    subtitle: String,
    cover: Cover?,
    trail: List<Pair<Long, String>> = emptyList(),
    lowBpm: Int? = null,
    avgBpm: Int? = null,
    highBpm: Int? = null,
    /** A line of counts — "12 recordings · 4 people · 3h 20m". */
    counts: String? = null,
    onOpenAncestor: (Long) -> Unit = {},
    tags: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Over a photo, flat text disappears into whatever is behind it. The same shadow the library
    // tiles use — one definition, so a title reads the same way on the card that opened this page
    // and on the page itself.
    val over = cover != null

    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (trail.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trail.forEachIndexed { index, (id, name) ->
                        if (index > 0) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium.overCover(over),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clickable { onOpenAncestor(id) }
                                .padding(vertical = 2.dp, horizontal = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }

            Text(
                title,
                style = MaterialTheme.typography.headlineSmall.overCover(over),
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.overCover(over),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            counts?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.overCover(over),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (lowBpm != null || avgBpm != null || highBpm != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Figure("Low", lowBpm, BpmLow, over)
                    Figure("Avg", avgBpm, BpmAvg, over)
                    Figure("High", highBpm, BpmHigh, over)
                }
            }

            tags?.let {
                Spacer(Modifier.height(8.dp))
                it()
            }
        }
    }

    if (cover == null) {
        Box(modifier.fillMaxWidth()) { content() }
    } else {
        CoverBackground(
            cover = cover,
            modifier = modifier.fillMaxWidth().heightIn(min = 150.dp),
            // Bottom-weighted: everything written here sits low, and the top of a picture usually
            // has the thing worth seeing in it.
            scrim = CoverScrim.HEADER
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) { content() }
        }
    }
}

/** One of the three figures. Dashes when there is nothing to say, rather than a zero. */
@Composable
private fun Figure(label: String, value: Int?, colour: Color, overCover: Boolean) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.overCover(overCover),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value?.toString() ?: "–",
            style = MaterialTheme.typography.titleMedium.overCover(overCover),
            fontWeight = FontWeight.Bold,
            color = colour
        )
    }
}

/**
 * Choosing the picture, from inside an editor.
 *
 * The cover *is* the header now, so it is part of what a subject looks like and belongs with the
 * rest of the editing rather than behind a menu. It was reachable from neither on a detail page —
 * only from the library's card menu — which meant the picture you were looking at could not be
 * changed from the page it was on.
 */
@Composable
fun CoverRow(
    hasCover: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.OutlinedButton(onClick = onPick) {
            Text(if (hasCover) "Change photo" else "Add photo")
        }
        if (hasCover) {
            androidx.compose.material3.TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}
