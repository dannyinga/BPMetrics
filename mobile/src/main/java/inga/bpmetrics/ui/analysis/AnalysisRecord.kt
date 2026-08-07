package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.AnalysisSnapshotRecord
import inga.bpmetrics.library.AnalysisSnapshotTag
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.CategoryEntity
import inga.bpmetrics.library.EffectiveTag
import inga.bpmetrics.library.EventEntity
import inga.bpmetrics.library.PersonEntity
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
     * Who and what event, by id — live only.
     *
     * `saved_analysis_records` stores names, not ids, so these are null for a frozen analysis. That
     * is why grouping by wearer works on a saved analysis (it groups by name) while the Event tab
     * and a person's own colour do not appear there: the information was never captured. Persisting
     * them is a schema change, deliberately left for the sprint that already has a migration.
     */
    val personId: Long? = null,
    val personColorArgb: Int? = null,
    val eventId: Long? = null,
    val eventName: String = ""
) {
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
                title = record.metadata.title,
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
                eventName = record.metadata.eventId?.let { eventNames[it] }.orEmpty()
            )

        /** Convenience for mapping a whole list against the category, watch and event tables. */
        fun from(
            records: List<BpmRecord>,
            categories: List<CategoryEntity>,
            watches: List<WatchEntity> = emptyList(),
            people: List<PersonEntity> = emptyList(),
            events: List<EventEntity> = emptyList(),
            effectiveTags: Map<Long, List<EffectiveTag>> = emptyMap()
        ): List<AnalysisRecord> {
            val names = categories.associate { it.categoryId to it.name }
            val watchNames = watches.associate { it.watchId to it.displayName }
            val peopleNames = people.associate { it.personId to it.displayName }
            val personColors = people.associate { it.personId to it.colorArgb }
            val eventNames = events.associate { it.eventId to it.displayName }
            return records.map {
                from(
                    it, names, watchNames, peopleNames, personColors, eventNames,
                    effectiveTags[it.metadata.recordId]
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
            watchName = snapshot.watchName
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
        watchName = watchName
    )
}
