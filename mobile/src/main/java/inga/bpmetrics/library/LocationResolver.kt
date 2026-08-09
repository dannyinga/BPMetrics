package inga.bpmetrics.library

/** Where a recording's location came from. Mirrors [TagSource] and [CoverSource]. */
enum class PlaceSource {
    /** Set on this recording. The exception, for the one that genuinely differs. */
    DIRECT,

    /** Set on the event it is filed under. */
    EVENT,

    /** Set on an event further up the tree — the festival rather than the set. */
    ANCESTOR
}

/** A location as it applies to something, and why. */
data class EffectivePlace(val location: LocationEntity, val source: PlaceSource) {
    val isInherited: Boolean get() = source != PlaceSource.DIRECT
}

/**
 * Which venue, and so which clock, applies to a recording.
 *
 * Deliberately the same shape as [CoverResolver] and [EffectiveTagsResolver], walking the same
 * [EventTree]: a venue is a property of the occasion, so it is set once on the festival and every
 * recording under it inherits. Setting it per recording would mean once per watch per night.
 *
 * A location is a *reference*, so this resolves an id and then looks it up — unlike covers and tags
 * there is nothing to merge field by field. Renaming the Gorge, or correcting its zone, changes
 * every event pointing at it, which is the whole reason it is a registry entry rather than free
 * text on each event.
 *
 * Nothing is written downward. A recording arriving late from a watch picks up its event's clock
 * the moment it is filed, with nothing to clean up.
 */
object LocationResolver {

    /**
     * The venue for a recording.
     *
     * @param directLocationId A location set on the recording itself, overriding what it inherits.
     */
    fun forRecording(
        directLocationId: Long?,
        eventId: Long?,
        events: List<EventEntity>,
        locations: Map<Long, LocationEntity>
    ): EffectivePlace? {
        directLocationId?.let { id ->
            locations[id]?.let { return EffectivePlace(it, PlaceSource.DIRECT) }
        }

        val ancestry = eventId?.let { EventTree.ancestryOf(events, it) }.orEmpty()
        ancestry.forEachIndexed { depth, event ->
            event.locationId?.let { id ->
                locations[id]?.let {
                    return EffectivePlace(
                        it,
                        if (depth == 0) PlaceSource.EVENT else PlaceSource.ANCESTOR
                    )
                }
            }
        }
        return null
    }

    /** The venue for an event, which is its own or the nearest one above it. */
    fun forEvent(
        eventId: Long,
        events: List<EventEntity>,
        locations: Map<Long, LocationEntity>
    ): EffectivePlace? = forRecording(null, eventId, events, locations)

    /**
     * The zone to read a recording in, always answering something.
     *
     * Falls back to the reader's own zone, which is what every screen did before locations existed.
     * A null here would mean each call site inventing its own fallback, and the ones that picked
     * UTC would be silently hours out.
     */
    fun zoneFor(
        directLocationId: Long?,
        eventId: Long?,
        events: List<EventEntity>,
        locations: Map<Long, LocationEntity>,
        default: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): java.util.TimeZone =
        forRecording(directLocationId, eventId, events, locations)?.location?.zone ?: default

    /**
     * The zone id to freeze onto a recording, or null where nobody has said.
     *
     * Deliberately not [zoneFor], which always answers. Storing the fallback would record the
     * device's zone at reconcile time as though somebody had chosen it, and a later reader
     * somewhere else would have no way to tell a real answer from a guess made on a train.
     */
    fun resolvedZoneId(
        directLocationId: Long?,
        eventId: Long?,
        events: List<EventEntity>,
        locations: Map<Long, LocationEntity>
    ): String? = forRecording(directLocationId, eventId, events, locations)?.location?.timeZoneId
}

/**
 * The clock this recording is read in.
 *
 * Straight off the stored column, with no tree walk — which is the entire reason
 * `reconcileTimeZones` freezes it there. A render happens per row per frame while scrolling, and
 * resolving inheritance at that point would put a walk of the event tree inside the scroll loop.
 *
 * Falls back to the reader's own zone, which is what every screen did before venues existed.
 */
val BpmRecordEntity.clock: java.time.ZoneId
    get() = timeZoneId
        ?.takeIf { it in java.util.TimeZone.getAvailableIDs() }
        ?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        ?: java.time.ZoneId.systemDefault()

/** See [BpmRecordEntity.clock]. */
val BpmRecord.clock: java.time.ZoneId get() = metadata.clock

/**
 * Whether this recording reads in a different clock from the person looking at it.
 *
 * What a screen uses to decide whether to say which zone a time is in. Printing "PDT" beside every
 * timestamp in a library that never leaves one zone is noise; omitting it on the one recording made
 * three time zones away is a wrong number with no warning.
 */
val BpmRecordEntity.clockDiffersFromReader: Boolean
    get() = clock.id != java.time.ZoneId.systemDefault().id

/**
 * The clock a set of recordings is read in.
 *
 * The first one's, because a screen showing several at once — a same-time analysis, an export — is
 * showing one occasion, and one occasion is in one place. Where they genuinely disagree there is no
 * single right answer to print on a shared axis, and picking the first is at least stable rather
 * than depending on sort order changing under it.
 */
val List<BpmRecord>.clock: java.time.ZoneId
    get() = firstOrNull()?.clock ?: java.time.ZoneId.systemDefault()

/**
 * The same, for a recording carrying its readings.
 *
 * Both forms delegate to [BpmRecordEntity.clock], so a chart and the tile above it read the same
 * clock. Separate extensions rather than one over an interface: the two types differ only in
 * whether the readings came along, and an interface for that would be ceremony around a join.
 */
val BpmRecordWithPoints.clock: java.time.ZoneId get() = metadata.clock

@get:JvmName("clockOfRecordsWithPoints")
val List<BpmRecordWithPoints>.clock: java.time.ZoneId
    get() = firstOrNull()?.clock ?: java.time.ZoneId.systemDefault()
