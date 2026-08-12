package inga.bpmetrics.library

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * A recording, **without its readings**.
 *
 * What the library is made of, and what almost everything wants: a list, a tile, a filter, a count,
 * a summary analysis. None of them draw a curve, and none of them need the tens of thousands of
 * rows behind one.
 *
 * The readings used to be joined onto this, which meant the always-on library stream held every
 * reading in the library and Room rebuilt all of them on any write. Everything a summary needs is
 * now a column: minimum and maximum by reference, average, and — since §9 of the product doc —
 * active duration and the zone split.
 *
 * When a curve genuinely is being drawn, ask for [BpmRecordWithPoints] by scope. That it is a
 * different type is the point: an empty reading list would draw a flat line and say nothing, so
 * the absence is made a compile error rather than a blank chart.
 *
 * @property metadata The recording itself — title, date, duration, who wore what, and the derived
 *   figures.
 * @property minDataPoint The lowest reading, by stored reference.
 * @property maxDataPoint The highest reading, by stored reference.
 */
data class BpmRecord(
    @Embedded val metadata: BpmRecordEntity,

    @Relation(
        parentColumn = "minId",
        entityColumn = "dataPointId"
    )
    val minDataPoint: BpmDataPointEntity?,

    @Relation(
        parentColumn = "maxId",
        entityColumn = "dataPointId"
    )
    val maxDataPoint: BpmDataPointEntity?,

    @Relation(
    parentColumn = "recordId",
    entityColumn = "tagId",
    associateBy = Junction(RecordTagCrossRef::class)
    )
    val tags: List<TagEntity> = emptyList()
) {

    /**
     * Measured time with the dropouts taken out.
     *
     * Read from the column, which is where it has been stored since ingest. Zero on a row written
     * before the column existed and not yet reached by
     * [LibraryRepository.backfillDerivedFigures] — a window of seconds on one launch.
     */
    val activeDurationMs: Long get() = metadata.activeDurationMs ?: 0L

    /** Time in each heart rate band, from the column. */
    val zoneTimes: List<ZoneTime> get() = DerivedFigures.zoneTimes(metadata.zonesEncoded)

    /**
     * Returns a string representation of the complete BPM record,
     * including its metadata and key analysis results (Max, Avg, Min).
     */
    override fun toString(): String {
        val outputBuilder = StringBuilder()

        outputBuilder.appendLine(metadata)

        outputBuilder.appendLine("Max: $maxDataPoint")
        outputBuilder.appendLine("Avg: ${metadata.avg}")
        outputBuilder.appendLine("Min: $minDataPoint")

        return outputBuilder.toString()
    }

    companion object {
        const val GAP_THRESHOLD_MS = 10000L // 10 seconds
    }
}


/**
 * A recording **with** its readings.
 *
 * What draws a curve: the recording chart, the event overlay, the same-time overlay, and an export.
 * Loaded per scope, on demand, by [LibraryRepository.recordsWithPoints] — never as part of the
 * library stream, because a list of two hundred recordings is a list of hundreds of thousands of
 * readings and nothing on that screen looks at one.
 *
 * Deliberately a distinct type from [BpmRecord] rather than the same one with a sometimes-empty
 * list. An empty list draws a flat line and reports zero, which looks like an answer; a missing
 * type does not compile, which looks like what it is.
 */
data class BpmRecordWithPoints(
    @Embedded val metadata: BpmRecordEntity,

    @Relation(
        parentColumn = "recordId",
        entityColumn = "recordOwnerId"
    )
    val dataPoints: List<BpmDataPointEntity>,

    @Relation(
        parentColumn = "minId",
        entityColumn = "dataPointId"
    )
    val minDataPoint: BpmDataPointEntity?,

    @Relation(
        parentColumn = "maxId",
        entityColumn = "dataPointId"
    )
    val maxDataPoint: BpmDataPointEntity?,

    @Relation(
        parentColumn = "recordId",
        entityColumn = "tagId",
        associateBy = Junction(RecordTagCrossRef::class)
    )
    val tags: List<TagEntity> = emptyList()
) {
    /**
     * The same recording as the rest of the app sees it.
     *
     * So a chart screen can hand its record to anything summary-shaped — a tile, a header, a
     * filter — without every one of those growing a second overload.
     */
    val summary: BpmRecord get() = BpmRecord(metadata, minDataPoint, maxDataPoint, tags)

    /**
     * Measured time with the dropouts taken out, computed from the readings in hand.
     *
     * Agrees with [BpmRecord.activeDurationMs] because both come from
     * [DerivedFigures.activeDurationOf]. Useful where the readings have just been edited and the
     * column has not caught up yet.
     */
    fun calculateActiveDurationMs(): Long =
        DerivedFigures.activeDurationOf(dataPoints, metadata.durationMs)
}

/**
 * Whether this recording has readings behind it, without loading any.
 *
 * Read from the stored figures: a recording with nothing measured has a zero active duration. What
 * used to be `dataPoints.size > 2` — a question that pulled every reading in the library in order
 * to reject most of them.
 */
val BpmRecord.hasReadings: Boolean get() = (metadata.activeDurationMs ?: 0L) > 0L
