package inga.bpmetrics.ui.analysis

import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.ZoneTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A sustained rise in heart rate.
 *
 * The interesting shape in a single recording: a number going up and staying up says more about
 * what happened than any one reading does.
 */
data class Climb(
    val startMs: Long,
    val endMs: Long,
    val fromBpm: Double,
    val toBpm: Double
) {
    val riseBpm: Double get() = toBpm - fromBpm
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * How this recording sits against the rest of that person's.
 *
 * A peak of 178 means nothing on its own. "Their highest of 23" and "12% above their average" are
 * what make one recording worth opening rather than a number to glance at.
 */
data class PersonComparison(
    val peakRank: Int,
    val totalRecordings: Int,
    /** This recording's average against the person's, as a percentage. Negative is below. */
    val percentVsAverage: Int
) {
    /** Only worth saying when there is something to compare against. */
    val isMeaningful: Boolean get() = totalRecordings > 1
}

/**
 * Everything a single recording's page says about itself beyond min, average and max.
 */
data class RecordInsights(
    val peaks: List<TimedBpm> = emptyList(),
    val longestClimb: Climb? = null,
    val zoneTimes: List<ZoneTime> = emptyList(),
    val gaps: List<TimeGap> = emptyList(),
    val activeDurationMs: Long = 0L,
    val comparison: PersonComparison? = null
) {
    val missingMs: Long get() = gaps.sumOf { it.durationMs }
}

/**
 * Reads one recording as an analysis in its own right.
 *
 * The event page merges people onto a shared clock and asks what they did together. This asks the
 * narrower question a single recording can answer: when did *this* heart rate do something, how
 * much of the time was actually measured, and is this a big one for the person who made it.
 *
 * Built on the same [ConcurrentSeries] the event chart draws, so a single recording is genuinely
 * the one-lane case rather than a parallel implementation — the gap threshold, the active duration
 * and the zone split are all the same code answering the same way.
 */
object RecordAnalysis {

    /** A spike closer than this to a stronger one is the same moment. */
    private const val PEAK_SEPARATION_MS = 60_000L

    private const val MAX_PEAKS = 5

    /** A rise has to hold for this long to count as a climb rather than a twitch. */
    private const val MIN_CLIMB_MS = 20_000L

    /**
     * @param series This recording as a lane, from [EventAnalysis].
     * @param theirOtherRecords Every other recording by the same person, for context. Empty when
     *   the recording belongs to nobody, in which case there is nothing to compare it against.
     */
    fun from(
        series: ConcurrentSeries?,
        record: BpmRecord,
        theirOtherRecords: List<BpmRecord> = emptyList()
    ): RecordInsights {
        if (series == null || series.points.isEmpty()) return RecordInsights()

        return RecordInsights(
            peaks = findPeaks(series),
            longestClimb = findLongestClimb(series),
            zoneTimes = series.zoneTimes,
            gaps = series.gaps,
            activeDurationMs = series.activeDurationMs,
            comparison = compare(record, theirOtherRecords)
        )
    }

    /**
     * The moments this heart rate did something.
     *
     * Only readings above the recording's own mean count, and only the strongest within a minute
     * of each other. Without the mean test a flat recording still reports "highlights", which is
     * inventing drama out of a walk to the shops; without the separation one spike reports as a
     * cluster of near-identical rows.
     */
    private fun findPeaks(series: ConcurrentSeries): List<TimedBpm> {
        if (series.points.size < 3) return emptyList()

        val mean = series.avgBpm
        val candidates = series.points.filter { it.bpm > mean }
        if (candidates.isEmpty()) return emptyList()

        val kept = mutableListOf<TimedBpm>()
        for (point in candidates.sortedByDescending { it.bpm }) {
            if (kept.size >= MAX_PEAKS) break
            val tooClose = kept.any { abs(it.wallClockMs - point.wallClockMs) < PEAK_SEPARATION_MS }
            if (!tooClose) kept += point
        }
        return kept.sortedBy { it.wallClockMs }
    }

    /**
     * The biggest sustained rise, measured from the foot of the climb.
     *
     * The foot is the last reading at or below everything since — a flat hour before a spike is
     * not part of the climb, and counting it produced "+40 in 1m 41s" for a two-second jump. A dip
     * along the way is allowed, because a real climb is not monotonic; a dip below the foot simply
     * makes a new foot.
     *
     * Ranked by how far it rose rather than how long it took: a slow drift over an hour is not
     * what anyone means by a climb. The duration floor is what keeps a twitch out.
     */
    private fun findLongestClimb(series: ConcurrentSeries): Climb? {
        val points = series.points
        if (points.size < 2) return null

        var best: Climb? = null
        var footIndex = 0

        for (i in 1 until points.size) {
            // A break in the data ends any climb: what happened across it is unknown, and joining
            // the two sides would invent a rise through a stretch nothing was measured in.
            val brokeOff = points[i].wallClockMs - points[i - 1].wallClockMs >
                ConcurrentSeries.GAP_THRESHOLD_MS
            if (brokeOff || points[i].bpm <= points[footIndex].bpm) {
                footIndex = i
                continue
            }

            val candidate = Climb(
                startMs = points[footIndex].wallClockMs,
                endMs = points[i].wallClockMs,
                fromBpm = points[footIndex].bpm,
                toBpm = points[i].bpm
            )
            if (candidate.durationMs >= MIN_CLIMB_MS &&
                (best == null || candidate.riseBpm > best!!.riseBpm)
            ) {
                best = candidate
            }
        }
        return best
    }

    /**
     * Where this recording sits among that person's.
     *
     * Rank is by peak, since that is the number people quote. The average comparison is against
     * the mean of their recordings' averages rather than a time-weighted figure — this is a
     * one-line "was this a big one", and weighting it would make it precise about the wrong thing.
     */
    private fun compare(record: BpmRecord, theirOthers: List<BpmRecord>): PersonComparison? {
        val thisPeak = record.maxDataPoint?.bpm ?: return null
        val all = theirOthers + record

        val peaks = all.mapNotNull { it.maxDataPoint?.bpm }
        if (peaks.isEmpty()) return null
        val rank = peaks.count { it > thisPeak } + 1

        val averages = all.mapNotNull { it.metadata.avg }
        val thisAverage = record.metadata.avg
        val percent = if (averages.size > 1 && thisAverage != null) {
            val theirMean = averages.average()
            if (theirMean > 0) (((thisAverage - theirMean) / theirMean) * 100).roundToInt() else 0
        } else 0

        return PersonComparison(
            peakRank = rank,
            totalRecordings = all.size,
            percentVsAverage = percent
        )
    }
}
