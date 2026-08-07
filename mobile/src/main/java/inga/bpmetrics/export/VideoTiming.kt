package inga.bpmetrics.export

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Works out the wall clock instant a video started filming.
 *
 * Everything about overlaying a heart rate on footage rests on this one number. A recording knows
 * exactly when it began — the watch stamps the press itself — so if the video's start is wrong by
 * `n` seconds, the curve is wrong by `n` seconds, and no amount of care elsewhere recovers it.
 *
 * The trouble is that phones do not agree on what they are stamping. `DATE_TAKEN` comes from the
 * MP4 `creation_time` box, and whether that marks the first frame or the last depends on the camera
 * app: most write it when the muxer starts, but some encoders — screen recorders, anything built on
 * ffmpeg, a few OEM camera apps — write it when the file is finalised. A third group writes local
 * time into a field defined as UTC, which lands the clip a whole timezone away.
 *
 * Rather than keep a list of which phone does what, this corroborates the stamp against the file's
 * modification time. A video's mtime is set when its last byte is written, which is the end of
 * filming give or take the muxing. So of the two readings of `DATE_TAKEN` — *this is the first
 * frame* and *this is the last* — the right one is whichever puts the end of the clip where the
 * filesystem says the file was finished. That is a per-file measurement, so it needs no model
 * names and it keeps working on a phone nobody has tested.
 *
 * Deliberately free of Android types: the judgement is arithmetic, and arithmetic is worth testing
 * without a device.
 */
object VideoTiming {

    /**
     * How far past the last frame a file may be finished and still corroborate a stamp.
     *
     * Muxing a long clip takes real time, and the scanner does not run instantly. Beyond a minute
     * the mtime is describing something other than the end of filming — the file was copied,
     * trimmed or re-encoded — and it stops being evidence.
     */
    private const val FLUSH_TOLERANCE_MS = 60_000L

    /**
     * The slack allowed when blaming a timezone.
     *
     * A stamp written in the wrong zone is out by a whole number of quarter hours and nothing else,
     * so what is left over after correcting should be seconds. Tight on purpose: a file that merely
     * happens to have been copied three hours later would otherwise be diagnosed as a zone error
     * and shifted three hours, which is worse than leaving it alone.
     */
    private const val ZONE_TOLERANCE_MS = 15_000L

    /** Every real UTC offset is a multiple of this. */
    private const val ZONE_STEP_MS = 15 * 60_000L

    /** The largest real UTC offset, so an unrelated gap is never mistaken for one. */
    private const val MAX_ZONE_SHIFT_MS = 14 * 60 * 60_000L

    /** What the stamp on a file turned out to mean. */
    enum class Basis {
        /** It marked the first frame, corroborated by the file's mtime. */
        STARTED,

        /** It marked the last frame, so filming began a duration earlier. */
        FINISHED,

        /** Nothing corroborated it, and it was taken at face value. */
        ASSUMED
    }

    /**
     * When a clip began, and how much that is worth believing.
     *
     * @property startedAtMs Wall clock instant of the first frame.
     * @property zoneShiftMs Correction applied for a file stamped in the wrong timezone, if any.
     */
    data class Stamp(
        val startedAtMs: Long,
        val basis: Basis,
        val zoneShiftMs: Long = 0L
    ) {
        /** Whether the file's own mtime agreed. An assumed stamp is a guess worth surfacing. */
        val corroborated: Boolean get() = basis != Basis.ASSUMED
    }

    /**
     * Resolves when filming started.
     *
     * @param dateTakenMs the stamp on the file, whatever it turns out to mean.
     * @param durationMs how long the clip runs.
     * @param dateModifiedMs when the file was last written, or 0 if unknown. Milliseconds —
     *   `MediaStore` reports this column in *seconds*, so callers must scale it.
     * @return the resolved start, or null when there is no stamp to work from at all.
     */
    fun resolve(dateTakenMs: Long, durationMs: Long, dateModifiedMs: Long): Stamp? {
        if (dateTakenMs <= 0L) return null
        val duration = durationMs.coerceAtLeast(0L)

        // With nothing to check against, read the stamp as the start. That is what MediaStore
        // documents `DATE_TAKEN` to be and what current camera apps write, and it is the reading
        // the rest of the app has always used when listing which clips overlap a recording.
        val assumed = Stamp(dateTakenMs, Basis.ASSUMED)
        if (dateModifiedMs <= 0L) return assumed

        // Each reading predicts when the file should have been finished. Score them against when
        // it actually was, and let the filesystem settle it.
        val candidates = buildList {
            for (basis in listOf(Basis.STARTED, Basis.FINISHED)) {
                val start = if (basis == Basis.STARTED) dateTakenMs else dateTakenMs - duration
                val error = (start + duration) - dateModifiedMs

                add(Triple(Stamp(start, basis), abs(error), FLUSH_TOLERANCE_MS))

                val shift = nearestZoneShift(error)
                if (shift != 0L) {
                    add(
                        Triple(
                            Stamp(start - shift, basis, shift),
                            abs(error - shift),
                            ZONE_TOLERANCE_MS
                        )
                    )
                }
            }
        }

        return candidates
            .filter { (_, error, tolerance) -> error <= tolerance }
            .minByOrNull { it.second }
            ?.first
            ?: assumed
    }

    /**
     * The timezone offset that would explain [errorMs], or 0 if none plausibly does.
     *
     * Capped at the largest offset any zone actually uses, so a file copied hours or days after it
     * was filmed is left alone rather than dragged across the world to make the numbers agree.
     */
    private fun nearestZoneShift(errorMs: Long): Long {
        val shift = (errorMs.toDouble() / ZONE_STEP_MS).roundToLong() * ZONE_STEP_MS
        return if (shift != 0L && abs(shift) <= MAX_ZONE_SHIFT_MS) shift else 0L
    }
}
