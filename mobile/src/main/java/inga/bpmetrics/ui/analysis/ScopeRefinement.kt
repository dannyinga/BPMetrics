package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.EventTree

/**
 * What a given analysis leaves out.
 *
 * Two different things, kept apart on purpose:
 *
 * - [excludedEventIds] and [excludedRecordIds] are choices made *about this analysis*. "The merch
 *   queue is not part of what I want to look at right now" is a fact about the question being
 *   asked, not about the queue, so it lives here and — unless the analysis is saved — dies with it.
 * - [includedDespiteFlag] overrides [EventEntity.excludedFromParentAnalysis] the other way: an
 *   event marked as never counting toward its parent, counted anyway, just this once.
 */
data class ScopeExclusions(
    val excludedEventIds: Set<Long> = emptySet(),
    val excludedRecordIds: Set<Long> = emptySet(),
    val includedDespiteFlag: Set<Long> = emptySet()
) {
    val isEmpty: Boolean
        get() = excludedEventIds.isEmpty() &&
            excludedRecordIds.isEmpty() &&
            includedDespiteFlag.isEmpty()
}

/** One row of "what is in this analysis", for the screen to list and let you untick. */
data class ScopeEntry(
    val eventId: Long?,
    val recordId: Long?,
    val label: String,
    val depth: Int,
    val recordCount: Int,
    val isIncluded: Boolean,
    /**
     * True when this is out because of [EventEntity.excludedFromParentAnalysis] rather than because
     * someone unticked it here. Worth distinguishing: one is a standing decision about the event,
     * the other is about this analysis, and a screen that showed them identically would leave
     * people wondering why a box keeps unticking itself.
     */
    val excludedByFlag: Boolean = false,
    /**
     * True when something above this row is out, so this is out with it.
     *
     * Excluding an event has always excluded its subtree — that is the rule, and it is what the
     * numbers do. The sheet did not say so: unticking Day 1 left its six sets and their recordings
     * sitting there ticked, describing a scope that did not exist. A row in this state shows
     * unticked and cannot be ticked back on its own, because ticking it would be a lie about what
     * the analysis covers; the way back is to tick its parent.
     */
    val excludedByAncestor: Boolean = false
)

/**
 * Which recordings an analysis actually covers, once exclusions are applied.
 *
 * Pure, and pointed at the same [EventTree] as everything else. Scope is decided in one place and
 * refined in one place; the alternative — each screen subtracting its own idea of what to leave out
 * — is how a header comes to disagree with the rows beneath it.
 */
object ScopeRefinement {

    /**
     * The recordings in scope, exclusions honoured.
     *
     * **Excluding an event excludes its subtree.** Leaving out Day 1 but keeping the six sets inside
     * it would be a scope nobody asked for, and the alternative — making people untick seven things
     * — is worse than the rule.
     *
     * [EventEntity.excludedFromParentAnalysis] applies only when analysing something *above* the
     * event. Analysing the merch queue directly still shows the merch queue; the flag says it is not
     * part of the day's average, not that it does not exist. That is also why it never touches the
     * library, the timeline or an export.
     */
    fun recordsIn(
        events: List<EventEntity>,
        records: List<BpmRecordEntity>,
        rootId: Long?,
        exclusions: ScopeExclusions = ScopeExclusions()
    ): List<BpmRecordEntity> {
        val within = if (rootId == null) {
            // A whole-library or filter-based analysis: no root, so every event is in play.
            events.mapTo(mutableSetOf()) { it.eventId } + setOf<Long?>(null)
        } else {
            EventTree.descendantsOf(events, rootId)
        }

        val dropped = mutableSetOf<Long>()
        exclusions.excludedEventIds.forEach { dropped += EventTree.descendantsOf(events, it) }

        events.forEach { event ->
            val flagged = event.excludedFromParentAnalysis &&
                event.eventId != rootId &&
                event.eventId !in exclusions.includedDespiteFlag
            if (flagged) dropped += EventTree.descendantsOf(events, event.eventId)
        }

        return records.filter { record ->
            record.eventId in within &&
                record.eventId !in dropped &&
                record.recordId !in exclusions.excludedRecordIds
        }
    }

    /**
     * One thing the refinement sheet can list, whatever it came from.
     *
     * A live analysis has library rows; a saved one has a frozen snapshot with no entities behind
     * it. Both reduce to this, so the sheet is built once rather than twice — two implementations
     * of "what is in scope" is the shape of every bug this rework exists to remove.
     *
     *  label How the row reads. The *qualified* event name where there is one, because a
     * sheet is where branches sit side by side and two events called "Subtronics" have to be
     * distinguishable.
     */
    data class ScopeItem(
        val recordId: Long,
        val eventId: Long?,
        val title: String,
        val startTime: Long = 0L,
        val eventLabel: String = ""
    )

    /**
     * The scope as a tree: every event under it, nested, with the recordings inside each.
     *
     * Flat was wrong. The list showed one level and stopped, so a festival offered its days and no
     * way to reach the set that actually needs leaving out — and a recording filed three levels
     * down could not be reached at all.
     *
     * Depth is real nesting rather than a two-level fallback, and both kinds of row are here:
     * unticking an event takes its whole subtree, unticking a recording takes only itself. Rows
     * stay on screen when unticked — a row that vanished when you excluded it could never be
     * put back.
     *
     * @param rootId The event everything hangs under, or null for a scope with no single root — a
     *   collection holding unrelated branches, a filter, a hand-picked set. With no root the
     *   top-level rows are whichever events have no parent *inside this scope*, which is what
     *   makes a filtered scope readable rather than a flat list of forty sets.
     */
    fun entriesFor(
        events: List<EventEntity>,
        records: List<ScopeItem>,
        rootId: Long?,
        exclusions: ScopeExclusions = ScopeExclusions()
    ): List<ScopeEntry> {
        val inScope = records.mapNotNull { it.eventId }.toSet()

        // Every event that either holds something in scope or lies between two that do. Without
        // the middle ones a day whose recordings all sit in its sets would be missing from its own
        // festival's list, and the sets would appear to hang off nothing.
        val relevant = buildSet {
            inScope.forEach { id -> addAll(EventTree.ancestryOf(events, id).map { it.eventId }) }
        }

        val byParent = events
            .filter { it.eventId in relevant }
            .groupBy { it.parentId }

        // With no root, start from the events whose parent is not itself in scope. Those are the
        // tops of whatever branches this scope reaches, which is not the same as the tops of the
        // library's tree.
        val roots = if (rootId != null) byParent[rootId].orEmpty() else {
            events.filter { it.eventId in relevant && it.parentId !in relevant }
        }

        val out = mutableListOf<ScopeEntry>()

        fun walk(event: EventEntity, depth: Int, seen: MutableSet<Long>, ancestorOut: Boolean) {
            if (!seen.add(event.eventId)) return

            val subtree = EventTree.descendantsOf(events, event.eventId)
            val excludedHere = event.eventId in exclusions.excludedEventIds
            val byFlag = event.excludedFromParentAnalysis &&
                event.eventId !in exclusions.includedDespiteFlag

            out += ScopeEntry(
                eventId = event.eventId,
                recordId = null,
                label = records.firstOrNull { it.eventId == event.eventId }
                    ?.eventLabel
                    ?.takeIf { it.isNotBlank() }
                    ?: event.displayName,
                depth = depth,
                recordCount = records.count { it.eventId in subtree },
                isIncluded = !ancestorOut && !excludedHere && !byFlag,
                excludedByFlag = byFlag && !excludedHere && !ancestorOut,
                excludedByAncestor = ancestorOut
            )

            // Everything under this row is out with it. The rule was always in the numbers — see
            // [recordsIn] — but the sheet drew the children ticked, which described a scope that
            // did not exist.
            val subtreeOut = ancestorOut || excludedHere || byFlag

            // Its own recordings first, directly beneath it, then the events inside it. A row's
            // children should follow the row — putting the recordings after four nested sets
            // leaves them looking like they belong to the last of those rather than to this.
            records.filter { it.eventId == event.eventId }
                .sortedBy { it.startTime }
                .forEach { record ->
                    out += ScopeEntry(
                        eventId = null,
                        recordId = record.recordId,
                        label = record.title,
                        depth = depth + 1,
                        recordCount = 1,
                        isIncluded = !subtreeOut &&
                            record.recordId !in exclusions.excludedRecordIds,
                        excludedByAncestor = subtreeOut
                    )
                }

            byParent[event.eventId].orEmpty()
                .sortedBy { it.windowStart ?: it.createdAt }
                .forEach { walk(it, depth + 1, seen, subtreeOut) }
        }

        val seen = mutableSetOf<Long>()
        roots.sortedBy { it.windowStart ?: it.createdAt }
            .forEach { walk(it, 0, seen, ancestorOut = false) }

        // Events the tree does not know about, from the records' own labels.
        //
        // A frozen selection has no live tree — its rows are a snapshot, and the events may since
        // have been rearranged or deleted. Grouping by the label the snapshot carries is the only
        // structure available, and it is enough to tick a set off.
        records
            .filter { it.eventId != null && it.eventId != rootId && it.eventId !in relevant }
            .groupBy { it.eventId!! }
            .toList()
            .sortedBy { (_, its) -> its.firstOrNull()?.eventLabel.orEmpty() }
            .forEach { (eventId, its) ->
                val eventOut = eventId in exclusions.excludedEventIds
                out += ScopeEntry(
                    eventId = eventId,
                    recordId = null,
                    label = its.firstOrNull()?.eventLabel?.takeIf { it.isNotBlank() }
                        ?: "Event $eventId",
                    depth = 0,
                    recordCount = its.size,
                    isIncluded = !eventOut
                )
                its.sortedBy { it.startTime }.forEach { record ->
                    out += ScopeEntry(
                        eventId = null,
                        recordId = record.recordId,
                        label = record.title,
                        depth = 1,
                        recordCount = 1,
                        isIncluded = !eventOut &&
                            record.recordId !in exclusions.excludedRecordIds,
                        excludedByAncestor = eventOut
                    )
                }
            }

        // Genuinely loose: filed under the root itself, or filed nowhere.
        records
            .filter { it.eventId == rootId }
            .sortedBy { it.startTime }
            .forEach { record ->
                out += ScopeEntry(
                    eventId = null,
                    recordId = record.recordId,
                    label = record.title,
                    depth = 0,
                    recordCount = 1,
                    isIncluded = record.recordId !in exclusions.excludedRecordIds
                )
            }

        return out
    }

    /** Ticks or unticks one row, returning the exclusions that result. */
    fun toggle(exclusions: ScopeExclusions, entry: ScopeEntry, include: Boolean): ScopeExclusions =
        when {
            entry.recordId != null -> exclusions.copy(
                excludedRecordIds = if (include) {
                    exclusions.excludedRecordIds - entry.recordId
                } else {
                    exclusions.excludedRecordIds + entry.recordId
                }
            )
            entry.eventId != null -> exclusions.copy(
                excludedEventIds = if (include) {
                    exclusions.excludedEventIds - entry.eventId
                } else {
                    exclusions.excludedEventIds + entry.eventId
                },
                // Ticking an event that is out because of its own flag has to override the flag,
                // or the box would spring back and look broken.
                includedDespiteFlag = if (include && entry.excludedByFlag) {
                    exclusions.includedDespiteFlag + entry.eventId
                } else if (!include) {
                    exclusions.includedDespiteFlag - entry.eventId
                } else {
                    exclusions.includedDespiteFlag
                }
            )
            else -> exclusions
        }
}
