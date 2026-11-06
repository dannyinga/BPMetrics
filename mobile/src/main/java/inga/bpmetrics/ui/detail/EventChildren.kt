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
import inga.bpmetrics.library.EventEntity
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
    EventCards(stateKey = "inside-$parentId", libraryViewModel = libraryViewModel, onOpen = onOpen) {
            tree, starts, expanded ->
        // Only what is under this event, walked *from* this event. Both halves matter: the subtree
        // keeps the rest of the library out of the cycle-recovery pass, and `rootParentId` is what
        // makes the walk start here — without it every row came back at depth zero with no
        // children, because a subtree has no event whose parent is null for the walk to begin at.
        //
        // The parent itself is dropped: `descendantsOf` includes the event it is asked about, and
        // it is the page you are already standing on.
        val within = EventTree.descendantsOf(tree, parentId) - parentId
        EventTree.flatten(
            all = tree.filter { it.eventId in within },
            expanded = expanded,
            // Oldest first, which for the inside of a festival is simply the order it happened in:
            // Day 0, Day 1, Day 2, Day 3. The library's timeline opens newest-first because it is a
            // history and the thing you want is usually last night; a festival's days are a
            // program, and reading one backwards is nobody's idea of it.
            newestFirst = false,
            startBy = starts,
            rootParentId = parentId
        ).map { "${it.event.eventId}" to it }
    }
}

/**
 * The events a collection's recordings were filed in, as the library draws them.
 *
 * A collection does not own events the way an event owns its days, but it is still made of them:
 * "Concerts" is seventy-odd nights, and the only way into one of them was a wall of names in the
 * header. This is the same list an event's Events tab is, so a collection reads like one.
 *
 * Newest first, unlike the inside of an event: a collection is a history, as the library is.
 */
@Composable
fun CollectionEvents(
    collectionId: Long,
    eventIds: List<Long>,
    libraryViewModel: LibraryViewModel,
    onOpen: (Long) -> Unit
) {
    EventCards(
        stateKey = "collection-$collectionId",
        libraryViewModel = libraryViewModel,
        onOpen = onOpen,
        inputs = eventIds
    ) { tree, starts, expanded ->
        CollectionEventRows.rows(tree, starts, eventIds, expanded)
    }
}

/**
 * A list of [EventCard]s, each opening its event and expanding into what is inside it.
 *
 * @param inputs Whatever [rows] reads besides the tree, so the list is worked out again when it
 *   changes.
 * @param rows The rows to draw, each with a key unique in the list. Keyed by the caller rather
 *   than by event, because one event can appear twice in a collection's list: once as a night it
 *   holds, and again inside a festival it also holds.
 */
@Composable
private fun EventCards(
    stateKey: String,
    libraryViewModel: LibraryViewModel,
    onOpen: (Long) -> Unit,
    inputs: Any? = null,
    rows: (tree: List<EventEntity>, starts: Map<Long, Long>, expanded: Set<Long>) -> List<Pair<String, EventTree.Node>>
) {
    val summaries by libraryViewModel.eventSummariesById.collectAsStateWithLifecycle()
    val covers by libraryViewModel.coversByEvent.collectAsStateWithLifecycle()
    val places by libraryViewModel.placeNamesByEvent.collectAsStateWithLifecycle()
    val options by libraryViewModel.filterOptions.collectAsStateWithLifecycle()

    // Survives a rotation and a trip into a child and back, so a festival opened three levels deep
    // is not collapsed again on return.
    var expanded by rememberSaveable(stateKey) { mutableStateOf(emptySet<Long>()) }

    val shown = remember(options.eventTree, options.eventStarts, expanded, inputs) {
        rows(options.eventTree, options.eventStarts, expanded)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(shown, key = { it.first }) { (_, node) ->
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

/** What [CollectionEvents] lists, worked out apart from drawing it. */
object CollectionEventRows {

    /**
     * Each of [eventIds] at the top level, newest first, and beneath any that is expanded,
     * everything inside it in the order it happened.
     *
     * Keys are paths ("12/40"), so an event listed in its own right and again inside another
     * listed one is two rows rather than a crash on a duplicate key.
     */
    fun rows(
        tree: List<EventEntity>,
        starts: Map<Long, Long>,
        eventIds: List<Long>,
        expanded: Set<Long>
    ): List<Pair<String, EventTree.Node>> {
        val byId = tree.associateBy { it.eventId }
        val tops = eventIds.distinct().mapNotNull { byId[it] }
            .sortedByDescending { starts[it.eventId] ?: it.windowStart ?: it.createdAt }
        val parents = tree.mapNotNullTo(mutableSetOf()) { it.parentId }

        return tops.flatMap { top ->
            val head = "${top.eventId}" to EventTree.Node(top, 0, top.eventId in parents)
            if (top.eventId !in expanded) return@flatMap listOf(head)
            val within = EventTree.descendantsOf(tree, top.eventId) - top.eventId
            listOf(head) + EventTree.flatten(
                all = tree.filter { it.eventId in within },
                expanded = expanded,
                newestFirst = false,
                startBy = starts,
                rootParentId = top.eventId
            ).map { "${top.eventId}/${it.event.eventId}" to it.copy(depth = it.depth + 1) }
        }
    }
}
