package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.AnalysisSnapshotRecord
import inga.bpmetrics.library.AnalysisSnapshotTag
import inga.bpmetrics.library.BpmZones
import inga.bpmetrics.library.SnapshotZone
import inga.bpmetrics.library.ZoneTime
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.RecordNameFormatter
import inga.bpmetrics.library.WatchEntity

/**
 * A tag as it applied to a record at the moment an analysis was taken.
 *
 * Carries the category name as well as its id so a saved analysis stays readable after the
 * category has been renamed or deleted. A frozen analysis is a statement about the past, and
 * should not change because the library moved on.
 */
data class AnalysisTag(
    val tagName: String,
    val categoryId: Long,
    val categoryName: String
)

/**
 * Everything an analysis needs to know about one recording.
 *
 * Deliberately not [BpmRecord]: an analysis uses only these few values, and saving them is what
 * lets a stored analysis survive its recordings being edited or deleted. Both the live path and a
 * saved snapshot produce this same shape, so the screen and the statistics do not care which they
 * are looking at.
 */
data class AnalysisRecord(
    val recordId: Long,
    val title: String,
    val date: Long,
    val minBpm: Double?,
    val avgBpm: Double?,
    val maxBpm: Double?,
    val activeDurationMs: Long,
    val tags: List<AnalysisTag> = emptyList(),
    /** Who was wearing the watch, as frozen onto the record when it arrived. */
    val wearerName: String = "",
    /** The watch it came from, by its device name. */
    val watchName: String = "",
    /**
     * Who and what event, by id.
     *
     * Captured into the snapshot as well as resolved live, so a frozen analysis colours its people
     * and offers the Event tab. Rows saved before schema 14 have neither and fall back to grouping
     * by name, which is what every saved analysis did until then.
     */
    val personId: Long? = null,
    val personColorArgb: Int? = null,
    val eventId: Long? = null,
    val eventName: String = "",
    /**
     * What kind of thing the event was — "Concert", "Raid", "Run".
     *
     * A fourth comparison axis, not decoration: "do gaming sessions or concerts wind me up more" is
     * Spiderman vs Hulk one level up, and falls out of the same machinery.
     */
    val eventType: String = "",
    /**
     * The event named by where it sits — "Subtronics | Day 1 | Griztronics at the Gorge".
     *
     * Carried alongside the short name because an analysis compares across branches, and two
     * events both called "Subtronics" from different weekends are two different nights. Frozen
     * into a saved snapshot for the same reason every other label is: the analysis is a statement
     * about a moment, and the tree can be rearranged afterwards.
     */
    val eventQualifiedName: String = "",
    /** Where it happened, by identity and by name — see [SplitAxis.Place]. */
    val locationId: Long? = null,
    val locationName: String = "",
    /**
     * Measured time in each heart rate band.
     *
     * Carried per record so any grouping — a person, a tag, an event, the whole scope — is the sum
     * of its records rather than a separate calculation. Captured into the snapshot too, because a
     * saved analysis has no data points left to recompute it from.
     */
    val zoneTimes: List<ZoneTime> = emptyList()
) {
    /**
     * How to name this recording's event where branches sit side by side.
     *
     * The qualified name when there is one, falling back to the short one — a snapshot saved before
     * qualified names existed has only the short name, and no label at all is worse than an
     * ambiguous one.
     */
    val eventLabel: String get() = eventQualifiedName.ifBlank { eventName }

    companion object {
        /**
         * Reduces a library record to the values an analysis uses.
         *
         * @param categoryNames Category id to name, so tags carry a readable category.
         */
        fun from(
            record: BpmRecord,
            categoryNames: Map<Long, String>,
            watchNames: Map<String, String> = emptyMap(),
            peopleNames: Map<Long, String> = emptyMap(),
            personColors: Map<Long, Int> = emptyMap(),
            eventNames: Map<Long, String> = emptyMap(),
            eventTypes: Map<Long, String> = emptyMap(),
            eventPaths: Map<Long, String> = emptyMap(),
            eventLocations: Map<Long, inga.bpmetrics.library.LocationEntity> = emptyMap(),
            /**
             * Tags including what the recording inherits from its event and group.
             *
             * Passing them means a festival tagged once at the group level becomes a category
             * everything in it can be ranked by — see §2.5. Null falls back to the recording's own
             * tags, which is what a snapshot has.
             */
            effectiveTags: List<EffectiveTag>? = null
        ): AnalysisRecord =
            AnalysisRecord(
                recordId = record.metadata.recordId,
                title = RecordNameFormatter.displayName(
                    record.metadata,
                    wearerName = record.metadata.personId?.let { peopleNames[it] },
                    watchName = record.metadata.watchId?.let { watchNames[it] }
                ),
                date = record.metadata.date,
                minBpm = record.minDataPoint?.bpm,
                avgBpm = record.metadata.avg,
                maxBpm = record.maxDataPoint?.bpm,
                // Computed now rather than stored, because it depends on the data points and a
                // saved analysis will not have them.
                activeDurationMs = record.calculateActiveDurationMs(),
                tags = (effectiveTags?.map { it.tag } ?: record.tags).map { tag ->
                    AnalysisTag(
                        tagName = tag.name,
                        categoryId = tag.parentCategoryId,
                        categoryName = categoryNames[tag.parentCategoryId] ?: "Uncategorized"
                    )
                },
                // The profile's current name, so grouping collapses onto one person rather than
                // one bucket per spelling their name was ever entered as.
                wearerName = record.metadata.personId?.let { peopleNames[it] }
                    ?: record.metadata.wearerName,
                // The watch's current name, falling back to the model it reported. A record whose
                // watch is no longer registered still shows where it came from.
                watchName = record.metadata.watchId?.let { watchNames[it] }
                    ?: record.metadata.deviceId,
                personId = record.metadata.personId,
                personColorArgb = record.metadata.personId?.let { personColors[it] },
                eventId = record.metadata.eventId,
                eventName = record.metadata.eventId?.let { eventNames[it] }.orEmpty(),
                eventType = record.metadata.eventId?.let { eventTypes[it] }.orEmpty(),
                eventQualifiedName = record.metadata.eventId
                    ?.let { eventPaths[it] }
                    .orEmpty(),
                // The *resolved* venue, so a set inside a festival reports the festival's rather
                // than nothing — inheritance is what makes the axis usable at every depth.
                locationId = record.metadata.eventId?.let { eventLocations[it] }?.locationId,
                locationName = record.metadata.eventId
                    ?.let { eventLocations[it] }
                    ?.displayName
                    .orEmpty(),
                // Same walk over the data points that produced the active duration, and the same
                // gap rule, so the bands and the measured total always agree.
                zoneTimes = BpmZones.split(
                    record.dataPoints.map {
                        (record.metadata.startTime + it.timestamp) to it.bpm
                    }
                )
            )

        /** Convenience for mapping a whole list against the category, watch and event tables. */
        fun from(
            records: List<BpmRecord>,
            categories: List<CategoryEntity>,
            watches: List<WatchEntity> = emptyList(),
            people: List<PersonEntity> = emptyList(),
            events: List<EventEntity> = emptyList(),
            effectiveTags: Map<Long, List<EffectiveTag>> = emptyMap(),
            locations: List<inga.bpmetrics.library.LocationEntity> = emptyList()
        ): List<AnalysisRecord> {
            val names = categories.associate { it.categoryId to it.name }
            val watchNames = watches.associate { it.watchId to it.displayName }
            val peopleNames = people.associate { it.personId to it.displayName }
            val personColors = people.associate { it.personId to it.colorArgb }
            val eventNames = events.associate { it.eventId to it.displayName }
            val eventTypes = events.associate { it.eventId to it.type.orEmpty() }
            // Resolved once for the library rather than per record: a festival with four hundred
            // recordings would otherwise walk the same three links four hundred times.
            val eventPaths = events.associate {
                it.eventId to inga.bpmetrics.library.EventTree.qualifiedNameOf(events, it.eventId)
            }
            // The venue each event *resolves* to, inheritance included, so a set reports its
            // festival's rather than nothing.
            val byId = locations.associateBy { it.locationId }
            val eventLocations = events.mapNotNull { event ->
                inga.bpmetrics.library.LocationResolver.forEvent(event.eventId, events, byId)
                    ?.let { event.eventId to it.location }
            }.toMap()
            return records.map {
                from(
                    it, names, watchNames, peopleNames, personColors, eventNames, eventTypes,
                    eventPaths, eventLocations, effectiveTags[it.metadata.recordId]
                )
            }
        }

        /** Reads back a record captured when an analysis was saved. */
        fun from(snapshot: AnalysisSnapshotRecord): AnalysisRecord = AnalysisRecord(
            recordId = snapshot.recordId,
            title = snapshot.title,
            date = snapshot.date,
            minBpm = snapshot.minBpm,
            avgBpm = snapshot.avgBpm,
            maxBpm = snapshot.maxBpm,
            activeDurationMs = snapshot.activeDurationMs,
            tags = snapshot.tags.map {
                AnalysisTag(tagName = it.tagName, categoryId = it.categoryId, categoryName = it.categoryName)
            },
            wearerName = snapshot.wearerName,
            watchName = snapshot.watchName,
            personId = snapshot.personId,
            personColorArgb = snapshot.personColorArgb,
            eventId = snapshot.eventId,
            eventName = snapshot.eventName,
            // Matched back to the current band definitions by name. A band that has since been
            // renamed drops out rather than being attributed to the wrong one — the snapshot is a
            // statement about the past, and guessing which band it meant would be inventing one.
            zoneTimes = snapshot.zones.mapNotNull { stored ->
                BpmZones.DEFAULT.firstOrNull { it.name == stored.name }
                    ?.let { ZoneTime(it, stored.durationMs, 0f) }
            }.let { restored ->
                val total = restored.sumOf { it.durationMs }
                restored.map {
                    it.copy(share = if (total > 0L) it.durationMs.toFloat() / total else 0f)
                }
            }
        )
    }

    /** Captures this record for storage alongside a saved analysis. */
    fun toSnapshot(): AnalysisSnapshotRecord = AnalysisSnapshotRecord(
        recordId = recordId,
        title = title,
        date = date,
        minBpm = minBpm,
        avgBpm = avgBpm,
        maxBpm = maxBpm,
        activeDurationMs = activeDurationMs,
        tags = tags.map {
            AnalysisSnapshotTag(tagName = it.tagName, categoryId = it.categoryId, categoryName = it.categoryName)
        },
        wearerName = wearerName,
        watchName = watchName,
        personId = personId,
        personColorArgb = personColorArgb,
        eventId = eventId,
        eventName = eventName,
        zones = zoneTimes.map { SnapshotZone(it.zone.name, it.durationMs) }
    )
}
