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
    val excludedByFlag: Boolean = false
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
     * What to list on the refinement sheet: the events inside the scope, and any recording sitting
     * loose in it.
     *
     * With a root, this is **one level** — its children. A festival with forty sets would otherwise
     * open forty-odd rows to scroll, when the question is nearly always "not that day" or "not that
     * set".
     *
     * Without one it is every event the records mention, flat. That is the shape for a collection
     * (whose members are unrelated branches), a filter, and a saved analysis (whose rows are a
     * snapshot and whose events may since have been rearranged away). In none of those is there a
     * level to take — so rather than guessing at one, the absence of a root *is* the signal.
     */
    fun entriesFor(
        events: List<EventEntity>,
        records: List<ScopeItem>,
        rootId: Long?,
        exclusions: ScopeExclusions = ScopeExclusions()
    ): List<ScopeEntry> {
        val byId = events.associateBy { it.eventId }

        val eventRows = if (rootId == null) {
            records.mapNotNull { it.eventId }.distinct().map { eventId ->
                val inIt = records.filter { it.eventId == eventId }
                val event = byId[eventId]
                val byFlag = event?.excludedFromParentAnalysis == true &&
                    eventId !in exclusions.includedDespiteFlag
                val excludedHere = eventId in exclusions.excludedEventIds
                ScopeEntry(
                    eventId = eventId,
                    recordId = null,
                    label = inIt.firstOrNull()?.eventLabel?.takeIf { it.isNotBlank() }
                        ?: event?.displayName.orEmpty(),
                    depth = 0,
                    recordCount = inIt.size,
                    isIncluded = !excludedHere && !byFlag,
                    excludedByFlag = byFlag && !excludedHere
                )
            }.sortedBy { it.label }
        } else {
            events.filter { it.parentId == rootId }
                .sortedBy { it.windowStart ?: it.createdAt }
                .map { event ->
                    val subtree = EventTree.descendantsOf(events, event.eventId)
                    val excludedHere = event.eventId in exclusions.excludedEventIds
                    val byFlag = event.excludedFromParentAnalysis &&
                        event.eventId !in exclusions.includedDespiteFlag
                    ScopeEntry(
                        eventId = event.eventId,
                        recordId = null,
                        label = records.firstOrNull { it.eventId == event.eventId }
                            ?.eventLabel
                            ?.takeIf { it.isNotBlank() }
                            ?: event.displayName,
                        depth = 0,
                        recordCount = records.count { it.eventId in subtree },
                        isIncluded = !excludedHere && !byFlag,
                        excludedByFlag = byFlag && !excludedHere
                    )
                }
        }

        val loose = records
            .filter { it.eventId == rootId }
            .sortedBy { it.startTime }
            .map { record ->
                ScopeEntry(
                    eventId = null,
                    recordId = record.recordId,
                    label = record.title,
                    depth = 0,
                    recordCount = 1,
                    isIncluded = record.recordId !in exclusions.excludedRecordIds
                )
            }

        return eventRows + loose
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
