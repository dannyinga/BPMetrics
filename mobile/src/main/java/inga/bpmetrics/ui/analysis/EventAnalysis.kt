package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.PersonColors
import inga.bpmetrics.library.PersonEntity
import inga.bpmetrics.library.WatchEntity

/**
 * An event's recordings laid out as one lane per person.
 *
 * The difference from [ConcurrentAnalysis] is what a curve *is*. There, a curve is a recording — so
 * someone whose watch dropped out and restarted appears twice, in two colours, as though two people
 * were there. Here a curve is a person: their recordings merge into a single lane, in a single
 * colour, with the quiet stretch between them left as a gap rather than stitched over.
 *
 * That is the whole reason events exist. A split recording is a fact about the hardware, not about
 * the evening, and the analysis should describe the evening.
 *
 * Everything downstream — group intensity, the moments, the chart, the readout legend — is
 * [ConcurrentAnalysis]'s, reached through [ConcurrentAnalysis.of]. Only the lanes are built here.
 */
object EventAnalysis {

    /**
     * Builds the analysis for an event.
     *
     * @param records Everything filed under the event, in any order.
     * @param window Restricts to part of the span, for looking at one stretch.
     */
    fun from(
        records: List<BpmRecord>,
        watches: List<WatchEntity> = emptyList(),
        people: List<PersonEntity> = emptyList(),
        window: LongRange? = null
    ): ConcurrentAnalysis {
        if (records.isEmpty()) return ConcurrentAnalysis()

        val watchNames = watches.associate { it.watchId to it.displayName }
        val peopleById = people.associateBy { it.personId }

        // Recordings with nobody attached each keep their own lane. Merging them would claim they
        // were the same person, which is exactly the thing not known about them.
        val (attributed, unattributed) = records.partition { it.metadata.personId != null }

        val personLanes = attributed
            .groupBy { it.metadata.personId!! }
            .map { (personId, its) -> personId to its }

        val lanes = buildList {
            personLanes.forEach { (personId, its) ->
                add(
                    LaneSource(
                        id = "person-$personId",
                        records = its,
                        label = peopleById[personId]?.displayName
                            ?: its.firstNotNullOfOrNull {
                                it.metadata.wearerName.takeIf(String::isNotBlank)
                            }
                            ?: "Unknown",
                        watchLabel = watchLabelFor(its, watchNames),
                        personId = personId
                    )
                )
            }
            unattributed.forEach { record ->
                add(
                    LaneSource(
                        id = "record-${record.metadata.recordId}",
                        records = listOf(record),
                        label = record.metadata.wearerName.takeIf(String::isNotBlank)
                            ?: record.metadata.watchId?.let { watchNames[it] }
                            ?: record.metadata.deviceId.takeIf(String::isNotBlank)
                            ?: record.metadata.title,
                        watchLabel = null,
                        personId = null
                    )
                )
            }
        }

        val series = lanes
            // By when each lane starts, so the legend reads in the order the curves enter the
            // chart. Index also seeds the fallback colour, and a stable order keeps that stable.
            .sortedBy { lane ->
                lane.records.minOfOrNull { it.metadata.startTime } ?: Long.MAX_VALUE
            }
            .mapIndexedNotNull { index, lane ->
                val points = mergePoints(lane.records, window)
                if (points.isEmpty()) return@mapIndexedNotNull null

                ConcurrentSeries(
                    id = lane.id,
                    recordIds = lane.records.map { it.metadata.recordId },
                    label = lane.label,
                    watchLabel = lane.watchLabel,
                    colorArgb = PersonColors.colorFor(lane.personId, peopleById, index),
                    points = points,
                    minBpm = points.minOf { it.bpm },
                    maxBpm = points.maxOf { it.bpm }
                )
            }

        return ConcurrentAnalysis.of(series, window)
    }

    /**
     * One person's recordings flattened onto a single wall clock.
     *
     * Readings that land on the same instant are averaged rather than both kept. Two watches on one
     * person, or an overlap where a recording was split, would otherwise leave the lane
     * non-monotonic — and a chart drawing two y-values at one x produces a vertical spike that
     * looks like a heart rate event.
     */
    private fun mergePoints(records: List<BpmRecord>, window: LongRange?): List<TimedBpm> {
        val all = records
            .flatMap { record ->
                record.dataPoints.map { TimedBpm(record.metadata.startTime + it.timestamp, it.bpm) }
            }
            .filter { window == null || it.wallClockMs in window }
            .sortedBy { it.wallClockMs }

        if (all.isEmpty()) return emptyList()

        val merged = ArrayList<TimedBpm>(all.size)
        var runStart = 0
        for (i in 1..all.size) {
            if (i == all.size || all[i].wallClockMs != all[runStart].wallClockMs) {
                merged += if (i - runStart == 1) {
                    all[runStart]
                } else {
                    TimedBpm(
                        all[runStart].wallClockMs,
                        all.subList(runStart, i).sumOf { it.bpm } / (i - runStart)
                    )
                }
                runStart = i
            }
        }
        return merged
    }

    /**
     * Which watch the lane was recorded on, or how many when it changed hands mid-event.
     *
     * Naming only the first would be wrong in a way that is hard to notice, so a lane that spans
     * two watches says so instead.
     */
    private fun watchLabelFor(records: List<BpmRecord>, watchNames: Map<String, String>): String? {
        val ids = records.mapNotNull { it.metadata.watchId }.distinct()
        return when (ids.size) {
            0 -> records.firstNotNullOfOrNull {
                it.metadata.deviceId.takeIf(String::isNotBlank)
            }
            1 -> watchNames[ids.first()] ?: records.first().metadata.deviceId.takeIf(String::isNotBlank)
            else -> "${ids.size} watches"
        }
    }

    private data class LaneSource(
        val id: String,
        val records: List<BpmRecord>,
        val label: String,
        val watchLabel: String?,
        val personId: Long?
    )
}
