package inga.bpmetrics.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import inga.bpmetrics.library.EventTree
import inga.bpmetrics.ui.library.EventCard
import inga.bpmetrics.ui.library.LibraryViewModel

/**
 * What is nested inside one event, as the library draws it.
 *
 * The way *down* the tree. An event's header has carried a breadcrumb up to its parents since
 * Sprint 5, and there was no matching route inward: reaching a set inside a day inside a festival
 * meant going back to the library and finding it there, even while standing on the festival.
 *
 * The same [EventCard] the library uses, deliberately — covers, faces, counts and peaks, in the
 * same shapes. A second, plainer list of events would be a second thing to keep looking like the
 * first, and this app has been bitten by that often enough to have a section about it. What it does
 * *not* carry is the overflow: renaming and deleting belong on the page you land on, not in a list
 * you are passing through, and `EventCard` draws no menu when given no actions.
 *
 * Nesting goes all the way down rather than one level. A festival's days are what you want most of
 * the time, but the reason this exists at all is the set three levels in.
 */
@Composable
fun EventChildren(
    parentId: Long,
    libraryViewModel: LibraryViewModel,
    onOpen: (Long) -> Unit
) {
    val summaries by libraryViewModel.eventSummariesById.collectAsStateWithLifecycle()
    val covers by libraryViewModel.coversByEvent.collectAsStateWithLifecycle()
    val places by libraryViewModel.placeNamesByEvent.collectAsStateWithLifecycle()
    val options by libraryViewModel.filterOptions.collectAsStateWithLifecycle()

    // Survives a rotation and a trip into a child and back, so a festival opened three levels deep
    // is not collapsed again on return.
    var expanded by rememberSaveable(parentId) { mutableStateOf(emptySet<Long>()) }

    // Only what is under this event, walked *from* this event. Both halves matter: the subtree
    // keeps the rest of the library out of the cycle-recovery pass, and `rootParentId` is what
    // makes the walk start here — without it every row came back at depth zero with no children,
    // because a subtree has no event whose parent is null for the walk to begin at.
    //
    // The parent itself is dropped: `descendantsOf` includes the event it is asked about, and it is
    // the page you are already standing on.
    val rows = remember(options.eventTree, options.eventStarts, expanded, parentId) {
        val within = EventTree.descendantsOf(options.eventTree, parentId) - parentId
        EventTree.flatten(
            all = options.eventTree.filter { it.eventId in within },
            expanded = expanded,
            // Oldest first, which for the inside of a festival is simply the order it happened in:
            // Day 0, Day 1, Day 2, Day 3. The library's timeline opens newest-first because it is a
            // history and the thing you want is usually last night; a festival's days are a
            // programme, and reading one backwards is nobody's idea of it.
            newestFirst = false,
            startBy = options.eventStarts,
            rootParentId = parentId
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(rows, key = { it.event.eventId }) { node ->
            val summary = summaries[node.event.eventId] ?: return@items

            // Indented by depth, as the timeline and the export picker are. `EventCard` takes no
            // modifier, so the inset goes on a box around it rather than on the card.
            Box(Modifier.padding(start = (node.depth.coerceAtMost(4) * 16).dp)) {
                EventCard(
                    summary = summary,
                    groupName = null,
                    placeName = places[node.event.eventId],
                    cover = covers[node.event.eventId],
                    expanded = node.event.eventId in expanded,
                    expandable = node.hasChildren,
                    onOpen = { onOpen(node.event.eventId) },
                    onToggleExpand = {
                        expanded = if (node.event.eventId in expanded) expanded - node.event.eventId
                        else expanded + node.event.eventId
                    }
                    // No overflow. See the note above: editing belongs on the page you land on.
                )
            }
        }
    }
}
