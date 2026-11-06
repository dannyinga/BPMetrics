package inga.bpmetrics.library

/**
 * Which venue, and so which clock, applies to a recording.
 *
 * A venue is a property of the occasion, so it is set once on the festival and every recording
 * under it inherits. Setting it per recording would mean once per watch per night. The walk is
 * [nearest], the same one covers and tags use; this only says what to pick.
 *
 * A location is a *reference*, so this resolves an id and then looks it up. Renaming the Gorge, or
 * correcting its zone, changes every event pointing at it, which is the whole reason it is a
 * registry entry rather than free text on each event.
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
    ): Inherited<LocationEntity>? = nearest(
        own = directLocationId?.let(locations::get),
        ancestry = eventId?.let { EventTree.ancestryOf(events, it) }.orEmpty()
    ) { event -> event.locationId?.let(locations::get) }

    /**
     * The venue for an event, which is its own or the nearest one above it. Its own counts as
     * [Source.EVENT], as it always has: what's asked is the event's venue, not a recording's.
     */
    fun forEvent(
        eventId: Long,
        events: List<EventEntity>,
        locations: Map<Long, LocationEntity>
    ): Inherited<LocationEntity>? = forRecording(null, eventId, events, locations)

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
 * The zone these recordings were resolved to from a venue, or null where none of them has one.
 *
 * Distinct from [clock], which always answers *something*: it falls back to the reader's own zone.
 * An export takes its answer from [exportZoneOf], which falls back the same way.
 *
 * The first recording *with* a zone rather than the first recording. One export is one occasion in
 * one place, and a single unfiled wearer at the front of the list should not decide the clock for
 * everyone else who was plainly at the venue.
 */
fun resolvedZoneOf(records: List<BpmRecordEntity>): String? =
    records.firstNotNullOfOrNull { record ->
        record.timeZoneId?.takeIf { it in java.util.TimeZone.getAvailableIDs() }
    }

/**
 * The clock an export is drawn in:
 * 1. the venue's zone, which someone set on purpose;
 * 2. else the offset the footage says it was filmed at ([inga.bpmetrics.export.ClipZone]), which
 *    is what the clock in the room said;
 * 3. else the phone's zone, the clock every screen shows these recordings in.
 *
 * The fallback used to be the preset's zone, the phone's zone frozen into a preset when it was
 * made. Every preset on a phone set up in Pacific time said "America/Los_Angeles", so a set with no
 * venue, shown as 12:23 AM in Contents, rendered as 10:23 PM. Presets no longer carry a zone.
 */
fun exportZoneOf(
    records: List<BpmRecordEntity>,
    clipZoneId: String? = null,
    phoneZone: java.time.ZoneId = java.time.ZoneId.systemDefault()
): String = resolvedZoneOf(records)
    ?: clipZoneId?.takeIf { runCatching { java.time.ZoneId.of(it) }.isSuccess }
    ?: phoneZone.id

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
