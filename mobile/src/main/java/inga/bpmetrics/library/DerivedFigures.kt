package inga.bpmetrics.library

/**
 * The two numbers a summary needs that only the readings can answer.
 *
 * Everything else a recording is summarised by — its minimum, its average, its peak — is already a
 * column on `bpm_records`. These two were not, so working out "how long was this, and how much of
 * it was spent above 160" meant loading every reading in the library. See §9 of the product doc.
 *
 * Computed once at ingest and stored. A recording's readings never change: they are written when it
 * arrives, and merge and split create new records rather than editing old ones. So this is a fact
 * about the recording, not a cache of one — there is nothing to invalidate.
 *
 * @property activeDurationMs Measured time with dropouts removed.
 * @property zonesEncoded Time per band, as `name:ms` one per line.
 */
data class DerivedFigures(
    val activeDurationMs: Long,
    val zonesEncoded: String
) {
    companion object {

        /**
         * Works both out in one pass over the readings.
         *
         * @param points In time order, timestamps relative to the recording's start.
         * @param startTime The recording's wall-clock start, because the bands are split on a
         *   shared clock while the readings are stored relative.
         * @param durationMs How long the recording ran, which closes the final interval.
         */
        fun of(
            points: List<BpmDataPointEntity>,
            startTime: Long,
            durationMs: Long
        ): DerivedFigures {
            if (points.isEmpty()) return DerivedFigures(0L, "")

            // The same two walks as before, still against the same two functions — this moves
            // *when* they run, not what they compute. A reimplementation here would be a second
            // definition of the gap rule, which is exactly what §9 is trying to stop needing.
            val active = activeDurationOf(points, durationMs)
            val zones = BpmZones.split(points.map { (startTime + it.timestamp) to it.bpm })

            return DerivedFigures(active, encodeZones(zones.map { SnapshotZone(it.zone.name, it.durationMs) }))
        }

        /**
         * Measured time with dropouts removed.
         *
         * Lifted out of [BpmRecord] so ingest and the backfill can call it without assembling a
         * whole [BpmRecord] first; [BpmRecord.calculateActiveDurationMs] now delegates here, so
         * there is still one implementation.
         */
        fun activeDurationOf(points: List<BpmDataPointEntity>, durationMs: Long): Long {
            if (points.isEmpty()) return 0L
            var total = 0L
            for (i in points.indices) {
                val next = if (i < points.size - 1) points[i + 1].timestamp else durationMs
                val dt = next - points[i].timestamp
                if (dt <= BpmRecord.GAP_THRESHOLD_MS) total += dt
            }
            return total
        }

        /**
         * Bands to text, and back.
         *
         * One encoding, shared by `bpm_records.zonesEncoded` and the frozen rows in
         * `saved_analysis_records` — the two places that store this for the same reason, that
         * neither has readings to recompute from at the moment it is read.
         *
         * A colon in a band name would break the split, so it is stripped rather than escaped: the
         * names are the app's own constants, and an escape scheme for a case that cannot arise is
         * more to get wrong than it is worth.
         */
        fun encodeZones(zones: List<SnapshotZone>): String =
            zones.joinToString("\n") { "${it.name.replace(":", "")}:${it.durationMs}" }

        fun decodeZones(encoded: String): List<SnapshotZone> {
            if (encoded.isBlank()) return emptyList()
            return encoded.lineSequence().mapNotNull { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val ms = parts[1].toLongOrNull() ?: return@mapNotNull null
                SnapshotZone(name = parts[0], durationMs = ms)
            }.toList()
        }

        /**
         * Stored bands back into the shape the analysis works in.
         *
         * Matched to [BpmZones.DEFAULT] by name, so a band the app no longer defines is dropped
         * rather than shown under a heading nothing else uses. Shares are recomputed from the
         * durations rather than stored, because a share is only meaningful against a total and
         * storing both invites them to disagree.
         */
        fun zoneTimes(encoded: String): List<ZoneTime> {
            val byName = decodeZones(encoded).associate { it.name to it.durationMs }
            val measured = BpmZones.DEFAULT.sumOf { byName[it.name] ?: 0L }
            return BpmZones.DEFAULT.map { zone ->
                val ms = byName[zone.name] ?: 0L
                ZoneTime(zone, ms, if (measured > 0L) ms.toFloat() / measured else 0f)
            }
        }
    }
}
