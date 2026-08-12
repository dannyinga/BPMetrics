package inga.bpmetrics.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import inga.bpmetrics.library.Cover
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.ui.components.CoverBackground
import inga.bpmetrics.ui.components.CoverScrim
import inga.bpmetrics.ui.components.overCover
import inga.bpmetrics.ui.tags.EffectiveTagChip
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
    /**
     * What it is tagged with, effective set included.
     *
     * Data rather than a slot. The header has to be able to *count* them to collapse the list, and
     * it could not while each subject handed down a finished row of chips — which was also two
     * copies of the same `FlowRow`.
     */
    tags: List<EffectiveTag> = emptyList(),
    onRemoveTag: (Long) -> Unit = {},
    onExplainTag: (String) -> Unit = {},
    /**
     * Opens the cover editor, from the corner of the cover.
     *
     * On the picture rather than in the edit modal, because the picture is the thing being edited
     * and the header is where all of it is on screen — the crop, what the writing does to it, how
     * bright it is. A button in a dialog three taps away asks someone to remember all of that.
     *
     * Bottom right: the writing is bottom left, so the corner opposite it is the one piece of a
     * header that is reliably empty.
     */
    onEditCover: (() -> Unit)? = null,
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

            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HeaderTags(tags, onRemoveTag, onExplainTag)
            }
        }
    }

    val editButton: @Composable BoxScope.() -> Unit = {
        onEditCover?.let { edit ->
            FilledTonalIconButton(
                onClick = edit,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp)
            ) {
                Icon(
                    if (cover == null) Icons.Default.AddAPhoto else Icons.Default.Edit,
                    contentDescription = if (cover == null) "Add a photo" else "Edit the photo",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (cover == null) {
        Box(modifier.fillMaxWidth()) {
            content()
            editButton()
        }
    } else {
        CoverBackground(
            cover = cover,
            modifier = modifier.fillMaxWidth().heightIn(min = 150.dp),
            // Bottom-weighted: everything written here sits low, and the top of a picture usually
            // has the thing worth seeing in it.
            scrim = CoverScrim.HEADER
        ) {
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().align(Alignment.BottomStart),
                    contentAlignment = Alignment.BottomStart
                ) { content() }
                editButton()
            }
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

/** How many tags a header shows before it stops. */
private const val TAGS_SHOWN = 4

/**
 * The tags, up to a few, and a way to see the rest.
 *
 * A header is a fixed amount of space at the top of a page, and tags are the one thing on it with
 * no upper bound — a recording that inherits its set's, its day's and its festival's can easily
 * carry a dozen, and a dozen chips wrap to four rows and push the whole analysis off the screen.
 * Worse over a cover, where the header is a photograph the chips are then covering up.
 *
 * So: four, then a chip saying how many more. Expanded in place rather than in a dialog, because
 * they are only chips and the point is to *see* them — and it collapses again from the same chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderTags(
    tags: List<EffectiveTag>,
    onRemove: (Long) -> Unit,
    onExplain: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) tags else tags.take(TAGS_SHOWN)
    val hidden = tags.size - shown.size

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        shown.forEach { effective ->
            EffectiveTagChip(
                effective = effective,
                onRemove = { onRemove(effective.tag.tagId) },
                onExplain = onExplain
            )
        }
        if (hidden > 0 || expanded) {
            AssistChip(
                onClick = { expanded = !expanded },
                label = { Text(if (expanded) "Show fewer" else "+$hidden more") }
            )
        }
    }
}

