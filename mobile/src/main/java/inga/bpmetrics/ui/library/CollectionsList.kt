package inga.bpmetrics.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import inga.bpmetrics.ui.components.overCover

/**
 * The collections view: arbitrary sets, listed alongside the timeline rather than inside it.
 *
 * A set has no place in a chronology — "Festivals" spans two weekends four months apart and is not
 * *at* either of them — which is exactly why it needs a view of its own rather than a row in the
 * timeline. Nothing here moves anything: every event and recording a set names is still sitting
 * where it always was, and deleting a set removes a grouping and no data.
 */
@Composable
fun CollectionsList(
    collections: List<CollectionSummary>,
    covers: Map<Long, inga.bpmetrics.library.Cover>,
    onCreate: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (CollectionSummary) -> Unit,
    onDelete: (CollectionSummary) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New collection")
            }
        }

        if (collections.isEmpty()) {
            item {
                inga.bpmetrics.ui.components.BpmEmptySection(
                    "No collections yet",
                    "A collection groups events and recordings that belong together but did not " +
                        "happen together — every festival, or everything with Kyle. They stay " +
                        "where they are on the timeline."
                )
            }
        }

        items(collections, key = { "collection-${it.collection.collectionId}" }) { summary ->
            CollectionCard(
                summary = summary,
                cover = covers[summary.collection.collectionId],
                onOpen = { onOpen(summary.collection.collectionId) },
                onRename = { onRename(summary) },
                onDelete = { onDelete(summary) }
            )
        }
    }
}

/**
 * A set, described by what it resolves to today.
 *
 * The counts come from the same tree walk the export scope uses, so "2 events · 47 recordings" and
 * an export of this set contain the same forty-seven. Deriving them here from anything else would
 * be a second answer to one question, which is how this app has gone wrong repeatedly.
 */
@Composable
private fun CollectionCard(
    summary: CollectionSummary,
    cover: inga.bpmetrics.library.Cover?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        inga.bpmetrics.ui.components.CoverBackground(
            cover = cover,
            modifier = Modifier.fillMaxWidth(),
            scrim = inga.bpmetrics.ui.components.CoverScrim.TILE
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        summary.collection.displayName,
                        style = MaterialTheme.typography.titleMedium.overCover(cover != null),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        // The span rather than a date: a set covers whatever its members do, and
                        // "Aug 2025 – Jan 2026" is the honest description of a set of festivals.
                        formatSpan(summary.span),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (summary.people.isNotEmpty()) {
                            PersonFaces(summary.people)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            buildString {
                                if (summary.eventCount > 0) {
                                    append(countLabelPublic(summary.eventCount, "event"))
                                    append("  ·  ")
                                }
                                append(countLabelPublic(summary.recordCount, "recording"))
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { open = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete collection") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { open = false; onDelete() }
                    )
                }
            }
        }
    }
}

/** "3 events", "1 recording". Shared with the event cards so the phrasing does not drift. */
internal fun countLabelPublic(count: Int, noun: String) =
    "$count $noun${if (count == 1) "" else "s"}"
