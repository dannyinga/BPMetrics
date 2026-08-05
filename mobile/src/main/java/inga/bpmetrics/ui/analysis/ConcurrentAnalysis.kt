package inga.bpmetrics.ui.analysis

import inga.bpmetrics.export.ImageExporter
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.WatchEntity
import kotlin.math.roundToInt

/**
 * One heart rate reading placed on the wall clock.
 *
 * Records store timestamps relative to their own start, which is useless for comparing people —
 * these are absolute, so two wearers' readings line up only if they really happened together.
 */
data class TimedBpm(val wallClockMs: Long, val bpm: Double)

/**
 * One wearer's curve within a concurrent analysis.
 *
 * @property label Who this is: the wearer frozen on the record, else the watch.
 * @property normalisedAt Position of a reading within *this wearer's own* range, 0..1.
 */
data class ConcurrentSeries(
    val recordId: Long,
    /** Who was wearing it, as frozen onto the record. */
    val label: String,
    /**
     * Which watch they were wearing, resolved live from the registry.
     *
     * Shown alongside the wearer rather than instead of them: with several people on identical
     * hardware, the name alone does not say whose curve is whose if two share a name, and the
     * watch alone does not say who was wearing it.
     */
    val watchLabel: String?,
    val colorArgb: Int,
    val points: List<TimedBpm>,
    val minBpm: Double,
    val maxBpm: Double
) {
    private val span get() = (maxBpm - minBpm).coerceAtLeast(1.0)

    fun normalisedAt(bpm: Double): Float = ((bpm - minBpm) / span).coerceIn(0.0, 1.0).toFloat()

    /** Reading at [wallClockMs], interpolated, or null if this wearer was not recording then. */
    fun bpmAt(wallClockMs: Long): Double? {
        if (points.isEmpty()) return null
        if (wallClockMs < points.first().wallClockMs || wallClockMs > points.last().wallClockMs) return null

        val index = points.binarySearch { it.wallClockMs.compareTo(wallClockMs) }
        if (index >= 0) return points[index].bpm

        val after = -index - 1
        if (after == 0 || after >= points.size) return null
        val before = points[after - 1]
        val next = points[after]

        // A gap wider than the record model's own threshold means the sensor was not delivering,
        // so interpolating across it would invent readings.
        if (next.wallClockMs - before.wallClockMs > GAP_THRESHOLD_MS) return null

        val ratio = (wallClockMs - before.wallClockMs).toDouble() /
                (next.wallClockMs - before.wallClockMs).toDouble()
        return before.bpm + ratio * (next.bpm - before.bpm)
    }

    private companion object {
        const val GAP_THRESHOLD_MS = 10_000L
    }
}

/**
 * How the group as a whole was doing at one instant.
 *
 * @property intensity Mean of each participant's position within their own range, 0..1.
 * @property participants How many wearers were recording at this instant.
 */
data class GroupMoment(
    val wallClockMs: Long,
    val intensity: Float,
    val participants: Int
)

/**
 * Several wearers' recordings laid over one wall clock, for finding what they did together.
 *
 * The existing analysis aggregates a *set* of recordings — the whole festival. This answers a
 * different question: during this one set, what happened to everybody at once?
 *
 * @property windowStartMs First instant any participant was recording.
 * @property windowEndMs Last such instant.
 * @property intensity Group intensity sampled evenly across the window.
 * @property peaks The moments worth looking at, strongest first.
 */
data class ConcurrentAnalysis(
    val series: List<ConcurrentSeries> = emptyList(),
    val windowStartMs: Long = 0L,
    val windowEndMs: Long = 0L,
    val intensity: List<GroupMoment> = emptyList(),
    val peaks: List<GroupMoment> = emptyList()
) {
    val isEmpty: Boolean get() = series.isEmpty()
    val durationMs: Long get() = (windowEndMs - windowStartMs).coerceAtLeast(0L)

    /** Whether any two wearers were ever recording at the same time. */
    val hasOverlap: Boolean get() = intensity.any { it.participants > 1 }

    companion object {
        /** How finely the group curve is sampled. Fine enough to catch a drop, cheap enough to plot. */
        private const val SAMPLE_INTERVAL_MS = 2_000L

        /** Peaks closer together than this are the same event, and only the strongest is kept. */
        private const val PEAK_SEPARATION_MS = 60_000L

        private const val MAX_PEAKS = 8

        /**
         * Builds the analysis from records that overlap in time.
         *
         * Intensity is deliberately *normalised per wearer* rather than compared as raw BPM. One
         * person's 140 is their limit and another's is a brisk walk; averaging the raw numbers
         * would just rank people by fitness. Normalising asks the useful question instead — how
         * worked up was each of them, for them — so a shared spike means the group reacted
         * together.
         *
         * @param window Restricts the analysis to part of the span, for looking at a single set.
         */
        fun from(
            records: List<BpmRecord>,
            watches: List<WatchEntity> = emptyList(),
            window: LongRange? = null
        ): ConcurrentAnalysis {
            val watchNames = watches.associate { it.watchId to it.displayName }

            val series = records.mapIndexedNotNull { index, record ->
                val points = record.dataPoints
                    .map { TimedBpm(record.metadata.startTime + it.timestamp, it.bpm) }
                    .filter { window == null || it.wallClockMs in window }
                    .sortedBy { it.wallClockMs }

                if (points.isEmpty()) return@mapIndexedNotNull null

                ConcurrentSeries(
                    recordId = record.metadata.recordId,
                    label = labelFor(record, watchNames),
                    watchLabel = watchLabelFor(record, watchNames),
                    colorArgb = colourFor(record, index, watches),
                    points = points,
                    minBpm = points.minOf { it.bpm },
                    maxBpm = points.maxOf { it.bpm }
                )
            }

            if (series.isEmpty()) return ConcurrentAnalysis()

            val start = window?.first ?: series.minOf { it.points.first().wallClockMs }
            val end = window?.last ?: series.maxOf { it.points.last().wallClockMs }

            val intensity = sampleIntensity(series, start, end)

            return ConcurrentAnalysis(
                series = series,
                windowStartMs = start,
                windowEndMs = end,
                intensity = intensity,
                peaks = findPeaks(intensity)
            )
        }

        /**
         * Finds the records that were made at the same time as [record].
         *
         * What turns "the whole festival" into "this one set": pick a recording and this gathers
         * everyone else who was wearing a watch at the time.
         */
        /**
         * Whether any two of these recordings were running at the same moment.
         *
         * There is nothing to compare across recordings made on different days, so this gates the
         * action rather than letting it open onto a chart of unrelated curves.
         */
        fun anyOverlap(records: List<BpmRecord>): Boolean {
            if (records.size < 2) return false
            val spans = records
                .map { it.metadata.startTime to it.metadata.startTime + it.metadata.durationMs }
                .sortedBy { it.first }
            // Sorted by start, an overlap exists if any recording begins before the furthest end
            // seen so far.
            var furthestEnd = spans.first().second
            spans.drop(1).forEach { (start, end) ->
                if (start <= furthestEnd) return true
                furthestEnd = maxOf(furthestEnd, end)
            }
            return false
        }

        fun overlapping(record: BpmRecord, candidates: List<BpmRecord>): List<BpmRecord> {
            val start = record.metadata.startTime
            val end = start + record.metadata.durationMs
            return candidates.filter { other ->
                val otherStart = other.metadata.startTime
                val otherEnd = otherStart + other.metadata.durationMs
                otherStart <= end && otherEnd >= start
            }
        }

        /**
         * Samples group intensity in buckets, taking each wearer's *strongest* reading in each.
         *
         * Reading the value at each bucket boundary instead would miss anything that happened
         * between two boundaries — and because the boundaries never line up with the data, a real
         * spike gets averaged into the slope on either side of it and disappears. Taking the
         * bucket maximum means a moment registers wherever inside the bucket it fell.
         */
        private fun sampleIntensity(
            series: List<ConcurrentSeries>,
            start: Long,
            end: Long
        ): List<GroupMoment> {
            if (end <= start) return emptyList()

            val steps = ((end - start) / SAMPLE_INTERVAL_MS).toInt().coerceIn(1, 5_000)
            val step = ((end - start) / steps).coerceAtLeast(1L)

            return (0..steps).map { i ->
                val bucketStart = start + i * step
                val bucketEnd = bucketStart + step

                val readings = series.mapNotNull { s ->
                    val inBucket = s.points
                        .filter { it.wallClockMs in bucketStart until bucketEnd }
                        .maxOfOrNull { it.bpm }
                    // Falling back to the boundary keeps sparse stretches represented rather than
                    // dropping the wearer out of the count entirely.
                    val bpm = inBucket ?: s.bpmAt(bucketStart)
                    bpm?.let { s.normalisedAt(it) }
                }

                GroupMoment(
                    wallClockMs = bucketStart,
                    intensity = if (readings.isEmpty()) 0f else readings.average().toFloat(),
                    participants = readings.size
                )
            }
        }

        /**
         * Picks out the moments the group reacted to.
         *
         * Only instants with more than one participant count — a spike from one person is that
         * person having a moment, not the group sharing one. Peaks within [PEAK_SEPARATION_MS]
         * of a stronger one are dropped, so a single drop reports as one moment rather than a
         * cluster of near-identical rows.
         *
         * A moment must also be above average for the window. Without that, a stretch where
         * everybody was calm still gets ranked and reported — and since calm stretches are long,
         * they survive the separation rule and crowd out the moments that actually mattered. It
         * also means a recording where nothing happened reports nothing, rather than inventing
         * highlights out of flat data.
         */
        private fun findPeaks(intensity: List<GroupMoment>): List<GroupMoment> {
            val shared = intensity.filter { it.participants > 1 }
            if (shared.isEmpty()) return emptyList()

            val mean = shared.map { it.intensity }.average().toFloat()
            val candidates = shared.filter { it.intensity > mean }
            if (candidates.isEmpty()) return emptyList()

            val kept = mutableListOf<GroupMoment>()
            for (moment in candidates.sortedByDescending { it.intensity }) {
                if (kept.size >= MAX_PEAKS) break
                val tooClose = kept.any {
                    kotlin.math.abs(it.wallClockMs - moment.wallClockMs) < PEAK_SEPARATION_MS
                }
                if (!tooClose) kept += moment
            }
            return kept.sortedBy { it.wallClockMs }
        }

        private fun labelFor(record: BpmRecord, watchNames: Map<String, String>): String =
            record.metadata.wearerName.takeIf { it.isNotBlank() }
                ?: record.metadata.watchId?.let { watchNames[it] }
                ?: record.metadata.deviceId.takeIf { it.isNotBlank() }
                ?: record.metadata.title

        /**
         * The watch, or null when it would only repeat what the wearer label already says — a
         * recording with no wearer already falls back to naming the watch.
         */
        private fun watchLabelFor(record: BpmRecord, watchNames: Map<String, String>): String? {
            if (record.metadata.wearerName.isBlank()) return null
            return record.metadata.watchId?.let { watchNames[it] }
                ?: record.metadata.deviceId.takeIf { it.isNotBlank() }
        }

        /**
         * The colour for a wearer's curve, preferring the one stored against their watch so a
         * person keeps the same colour between this and an exported video.
         */
        private fun colourFor(record: BpmRecord, index: Int, watches: List<WatchEntity>): Int {
            val stored = record.metadata.watchId
                ?.let { id -> watches.firstOrNull { it.watchId == id }?.colorArgb }
            return stored
                ?: ImageExporter.MULTI_WATCH_PALETTES[index % ImageExporter.MULTI_WATCH_PALETTES.size][0]
        }
    }
}

/** Group intensity as a percentage, for display. */
val GroupMoment.intensityPercent: Int get() = (intensity * 100).roundToInt()
