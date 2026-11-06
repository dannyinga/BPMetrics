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
 * When the camera named the file after the moment filming began (`20260524_200337.mp4`), that name
 * is better evidence than any stamp, and it is preferred whenever it agrees with one. A Samsung
 * stamp marks the end of saving rather than the last frame, so a stamp alone lands a clip late by
 * however long saving took; the name was written at the press and does not have that problem.
 *
 * Best of all is a camera that wrote the moment down with its zone, as an iPhone does. That beats
 * the name and the stamp both, and it is the only thing that survives the clip being shared: the
 * copy is stamped, and often named, when it was made.
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

    /**
     * How long after the last frame a camera that stamps at the end writes that stamp.
     *
     * Measured on 88 clips from a Galaxy S23 Ultra: the stamp less the clip's length landed 0.8 s
     * after the time in the file's name on average, the same for a 5 second clip as a 5 minute one
     * and the same at 1080p as at 4K. It is the time the phone takes to finish the file, not
     * something that grows with it.
     */
    private const val SAVE_DELAY_MS = 800L

    /**
     * Where in its second a time written to the whole second really fell, on average.
     *
     * Stamps and file names are both cut to the second, so the instant they describe is anywhere up
     * to a second after what they say: half a second later is the best single guess.
     */
    private const val WITHIN_SECOND_MS = 500L

    /**
     * How far a file name may disagree with the stamp and still be believed.
     *
     * Honest disagreement is the save delay and rounding — under three seconds in everything
     * measured. A name further out than this describes some other moment: the clip was trimmed
     * after filming (which keeps the name and rewrites the stamp), or the file was renamed.
     */
    private const val NAME_TOLERANCE_MS = 5_000L

    /**
     * A camera's file name: the date and time filming began, to the second or the millisecond.
     *
     * Samsung writes `20260524_200337.mp4` (and `…(1).mp4`, `…_01.mp4` for duplicates), older
     * Android `VID_20260524_200337.mp4`, Pixels `PXL_20260524_200337123.mp4`. Anchored at the start,
     * so a name that merely contains a date somewhere — a screen recording, an edit — is not read
     * as one.
     */
    private val CAMERA_NAME = Regex("""^(?:VID_|PXL_)?(\d{8})_(\d{6})(\d{3})?(?!\d)""")

    /** What the stamp on a file turned out to mean. */
    enum class Basis {
        /**
         * The camera wrote down when filming began, zone and all, in the file's own tags (an
         * iPhone's creation date). Preferred over everything worked out from the stamp: a shared
         * or re-encoded copy is stamped when the copy was made, hours or days later, and only this
         * survives the copying. See [ClipZone.capturedAt].
         */
        CAPTURED,

        /** It marked the first frame, corroborated by the file's mtime. */
        STARTED,

        /** It marked the last frame, so filming began a duration earlier. */
        FINISHED,

        /**
         * The camera's file name said when filming began, and the stamp agreed with it.
         *
         * Preferred over either reading of the stamp, because the name was written at the press and
         * the stamp only when saving finished.
         */
        NAMED,

        /** Nothing corroborated it, and it was taken at face value. */
        ASSUMED,

        /**
         * Somebody said when it was filmed, and that is the only answer that counts.
         *
         * For footage whose stamp is not the moment it was filmed at all — a screen recording made
         * today of a set from a month ago, a clip sent through a messaging app that rewrote it. No
         * amount of corroborating the file against itself can recover a time the file never held.
         */
        SET_BY_HAND
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
     * @param fileName the file's display name, if known, for cameras that name a file after when
     *   filming began.
     * @param capturedAtMs when the file's own tags say filming began, if they say. Wins outright.
     * @return the resolved start, or null when there is no stamp to work from at all.
     */
    fun resolve(
        dateTakenMs: Long,
        durationMs: Long,
        dateModifiedMs: Long,
        fileName: String? = null,
        capturedAtMs: Long? = null
    ): Stamp? {
        if (capturedAtMs != null && capturedAtMs > 0L) {
            // Written to the second, so the press was half a second after it on average.
            val rounding = if (capturedAtMs % 1000L == 0L) WITHIN_SECOND_MS else 0L
            return Stamp(capturedAtMs + rounding, Basis.CAPTURED)
        }
        val fromStamp = resolveStamp(dateTakenMs, durationMs, dateModifiedMs) ?: return null
        return fileName?.let { named(it, dateTakenMs, durationMs, fromStamp) } ?: fromStamp
    }

    /**
     * The start the camera's file name gives, if it has one and the stamp backs it up.
     *
     * The name is local time in a zone nobody wrote down, so it is only ever compared within a
     * quarter hour: the whole quarter hours between it and the stamp are the zone, and what is left
     * over is how far apart they really are. The stamp supplies the date and the zone; the name
     * supplies the second.
     *
     * It is checked against both readings of the stamp, not only the one the file's mtime chose.
     * A clip copied back onto a phone has an mtime of the day it was copied, which corroborates
     * neither reading; the name can still say which one is right.
     */
    private fun named(fileName: String, dateTakenMs: Long, durationMs: Long, fromStamp: Stamp): Stamp? {
        val match = CAMERA_NAME.find(fileName) ?: return null
        val (date, time, millis) = match.destructured
        val local = runCatching {
            java.time.LocalDateTime.parse(
                date + time,
                java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT)
            )
        }.getOrNull() ?: return null
        val pressed = local.toInstant(java.time.ZoneOffset.UTC).toEpochMilli() +
            (millis.toLongOrNull() ?: WITHIN_SECOND_MS)

        val stamp = dateTakenMs - fromStamp.zoneShiftMs
        val readings = listOf(stamp, stamp - durationMs.coerceAtLeast(0L))
        return readings.firstNotNullOfOrNull { reading ->
            val zone = ((pressed - reading).toDouble() / ZONE_STEP_MS).roundToLong() * ZONE_STEP_MS
            val apart = pressed - zone - reading
            if (abs(zone) <= MAX_ZONE_SHIFT_MS && abs(apart) <= NAME_TOLERANCE_MS) {
                Stamp(reading + apart, Basis.NAMED, fromStamp.zoneShiftMs)
            } else null
        }
    }

    /** [resolve] from the stamp and mtime alone. */
    private fun resolveStamp(dateTakenMs: Long, durationMs: Long, dateModifiedMs: Long): Stamp? {
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

        val chosen = candidates
            .filter { (_, error, tolerance) -> error <= tolerance }
            .minByOrNull { it.second }
            ?.first
            ?: return assumed

        // A stamp written when saving finished is late by the save as well as the clip. Cut to the
        // second, it has also lost half a second on average, which partly makes up for that.
        if (chosen.basis != Basis.FINISHED) return chosen
        val rounding = if (dateTakenMs % 1000L == 0L) WITHIN_SECOND_MS else 0L
        return chosen.copy(startedAtMs = chosen.startedAtMs - SAVE_DELAY_MS + rounding)
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
