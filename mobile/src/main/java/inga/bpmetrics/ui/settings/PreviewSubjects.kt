package inga.bpmetrics.ui.settings

import inga.bpmetrics.library.BpmDataPointEntity
import inga.bpmetrics.library.BpmRecord
import inga.bpmetrics.library.BpmRecordEntity
import inga.bpmetrics.library.PersonColors
import kotlin.math.sin

/**
 * Something to look at while setting up a preset.
 *
 * A preset is edited in Settings with no export in progress, so there is nothing to draw it on.
 * Without a preview the editor is guesswork: the placement chips, the panel opacity and the colour
 * settings all describe a picture nobody can see.
 *
 * Real recordings are preferred — judging a look against your own data is the point — and the
 * synthetic pair exists so the editor still works on a fresh install, which is exactly when
 * someone is most likely to be arranging a preset.
 *
 * Two subjects rather than one, because the two cases genuinely look different: a lone curve is
 * drawn as a blue-to-red gradient, and several take a colour each with a legend. A preset that
 * reads well for one can be unreadable for the other.
 */
object PreviewSubjects {

    /** A sample curve is an hour long, which is about the length of a set. */
    private const val SAMPLE_DURATION_MS = 60 * 60_000L
    private const val SAMPLE_INTERVAL_MS = 15_000L

    /**
     * The most recent recording, or a made-up one.
     *
     * Most recent rather than longest or busiest: it is the one whose shape the user will
     * recognise, which is what makes the preview worth having over a synthetic curve.
     */
    fun onePerson(library: List<BpmRecord>): List<BpmRecord> =
        library.filter { it.dataPoints.size > 2 }
            .maxByOrNull { it.metadata.startTime }
            ?.let { listOf(it) }
            ?: listOf(synthetic(id = -1, personId = -1, seed = 0.0, restingBpm = 68.0))

    /**
     * Several people over one stretch, or a made-up trio.
     *
     * Taken from an event where more than one person was recording, since that is the case the
     * multi-lane look exists for. Falls back to whatever overlaps if no event qualifies.
     */
    fun severalPeople(library: List<BpmRecord>): List<BpmRecord> {
        val byEvent = library
            .filter { it.metadata.eventId != null && it.dataPoints.size > 2 }
            .groupBy { it.metadata.eventId }
            .values
            .filter { group -> group.mapNotNull { it.metadata.personId }.distinct().size > 1 }
            .maxByOrNull { group -> group.maxOf { it.metadata.startTime } }

        // Capped at three: a preview crowded with eight lanes says more about the sample than
        // about the preset.
        if (byEvent != null) return byEvent.take(3)

        return listOf(
            synthetic(id = -1, personId = -1, seed = 0.0, restingBpm = 66.0),
            synthetic(id = -2, personId = -2, seed = 1.7, restingBpm = 74.0),
            synthetic(id = -3, personId = -3, seed = 3.1, restingBpm = 81.0)
        )
    }

    /** Colours for whatever the subject turned out to be, real people included. */
    fun coloursFor(
        records: List<BpmRecord>,
        people: Map<Long, inga.bpmetrics.library.PersonEntity>
    ): Map<Long, Int> = records.mapIndexed { index, record ->
        record.metadata.recordId to PersonColors.colorFor(record.metadata.personId, people, index)
    }.toMap()

    /**
     * A plausible hour: a warm-up, two climbs, and a comedown.
     *
     * Deterministic, not random. A preview that redrew differently on every recomposition would
     * make it impossible to tell whether a change to the preset had done anything.
     */
    private fun synthetic(
        id: Long,
        personId: Long,
        seed: Double,
        restingBpm: Double
    ): BpmRecord {
        // A fixed instant rather than "now": the sample is not a recording of anything, and
        // stamping it with the current time would put a real-looking date on the preview.
        val start = 1_700_000_000_000L
        val points = (0..(SAMPLE_DURATION_MS / SAMPLE_INTERVAL_MS)).map { step ->
            val at = step * SAMPLE_INTERVAL_MS
            val progress = at.toDouble() / SAMPLE_DURATION_MS

            // Two broad climbs, a faster ripple over the top, and a lift towards the end.
            val arc = sin(progress * Math.PI * 2 + seed) * 26.0
            val ripple = sin(progress * Math.PI * 14 + seed * 2) * 7.0
            val build = progress * 34.0

            BpmDataPointEntity(
                recordOwnerId = id,
                timestamp = at,
                bpm = (restingBpm + arc + ripple + build).coerceIn(50.0, 195.0)
            )
        }

        return BpmRecord(
            metadata = BpmRecordEntity(
                recordId = id,
                title = "Sample",
                date = start,
                startTime = start,
                endTime = start + SAMPLE_DURATION_MS,
                durationMs = SAMPLE_DURATION_MS,
                personId = personId
            ),
            dataPoints = points,
            minDataPoint = points.minByOrNull { it.bpm },
            maxDataPoint = points.maxByOrNull { it.bpm }
        )
    }
}
