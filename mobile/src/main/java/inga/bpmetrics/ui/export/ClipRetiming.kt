package inga.bpmetrics.ui.export

import inga.bpmetrics.export.VideoExporter
import inga.bpmetrics.export.VideoTiming
import inga.bpmetrics.library.BpmRecordWithPoints

/**
 * Telling a clip when it was really filmed.
 *
 * ## Why one number
 *
 * A clip is two facts — when it started, and how long it runs — and the length is the file's. So
 * the only thing that can be wrong is the start, and once it is right, [VideoExporter.VideoClip.windowOn]
 * works out exactly which stretch of the recording belongs over the footage: the graph starts where
 * the video starts and runs for as long as the video does. Asking separately for a graph start and a
 * duration would be asking for those facts a second and third time, and let them disagree with the
 * first — a five-minute graph over a three-minute video, cut off or running out early.
 *
 * ## Why per clip
 *
 * When something was filmed is a fact about one video. Putting it in the preset's Time section would
 * apply one clip's timestamp to every clip in a batch and save it into a look meant to be reused —
 * the category error that had every export captioned in the time zone a preset happened to be made
 * in.
 *
 * ## What it replaces
 *
 * The v1.0 export dialog had start and end fields for a chosen video. The stepped utility that
 * replaced it did not carry them, and a video picked by hand has since been laid over the whole
 * recording from its first second, with no way to say where it belonged. Screen recordings of old
 * footage were the case that surfaced it: filmed today, of an evening a month ago, so nothing lined up
 * and nothing could be made to.
 *
 * Pure, so the rules are pinned by tests rather than by trying it on a phone.
 */
object ClipRetiming {

    /**
     * [selection] as filmed at [startedAtMs], its BPM offset kept on top (see [shift]).
     *
     * Everything that depends on when the clip happened is recomputed here rather than left for a
     * consumer to notice: who was recording during it, and whether it can still be ticked. The
     * sparkline is cleared, because it drew the old stretch of the recording and would otherwise
     * sit there showing the wrong evening until it was redrawn.
     */
    fun retime(
        selection: ClipSelection,
        startedAtMs: Long,
        records: List<BpmRecordWithPoints>
    ): ClipSelection = moveTo(selection, startedAtMs + selection.offsetMs, selection.offsetMs, records)

    /**
     * [selection] with a BPM offset of [offsetMs] from when it was filmed, replacing the one it had.
     * Positive starts the clip later on the recordings, so the heart rate leads the picture.
     */
    fun shift(
        selection: ClipSelection,
        offsetMs: Long,
        records: List<BpmRecordWithPoints>
    ): ClipSelection = moveTo(selection, filmedAt(selection) + offsetMs, offsetMs, records)

    /** When [selection] was filmed: the file's time, or the one set by hand, before the offset. */
    fun filmedAt(selection: ClipSelection): Long = selection.clip.startedAtMs - selection.offsetMs

    /** When the file says the clip was filmed, whatever it's been set to since. */
    fun fileTime(selection: ClipSelection): Long = (selection.originalClip ?: selection.clip).startedAtMs

    /**
     * Whether the clip is filmed at the file's own time, so the video and the BPM are in sync with
     * it. The BPM offset doesn't count: a clip starts at its filming time plus its offset, and the
     * offset is a deliberate shift on top of being in sync, not a departure from it.
     */
    fun onFileTime(selection: ClipSelection): Boolean = filmedAt(selection) == fileTime(selection)

    /** Filmed at the file's own time again, the BPM offset kept. */
    fun syncToFile(selection: ClipSelection, records: List<BpmRecordWithPoints>): ClipSelection =
        retime(selection, fileTime(selection), records)

    private fun moveTo(
        selection: ClipSelection,
        startedAtMs: Long,
        offsetMs: Long,
        records: List<BpmRecordWithPoints>
    ): ClipSelection {
        // Always from the file's own clip, so correcting twice does not compound, and "use the
        // file's time" always has the original to go back to.
        val original = selection.originalClip ?: selection.clip
        // Back where the file put it, with no offset, is the file's time, not a correction that
        // happens to match.
        if (startedAtMs == original.startedAtMs && offsetMs == 0L) return reset(selection, records)
        val moved = original.copy(startedAtMs = startedAtMs, basis = VideoTiming.Basis.SET_BY_HAND)
        return applyClip(selection.copy(offsetMs = offsetMs), moved, originalClip = original, records = records)
    }

    /** Back to the time the file gave, with no offset. A no-op for a clip that was never corrected. */
    fun reset(selection: ClipSelection, records: List<BpmRecordWithPoints>): ClipSelection {
        val original = selection.originalClip ?: return selection.copy(offsetMs = 0L)
        return applyClip(selection.copy(offsetMs = 0L), original, originalClip = null, records = records)
    }

    /**
     * How far from a clip someone's recording may be and still put them on it: the same show, not
     * the festival. Two hours either side covers a set someone joined late, and leaves out another
     * day's recordings when a whole festival is the source.
     */
    const val CAST_MARGIN_MS = 2 * 60 * 60_000L

    /**
     * Everyone who could be on [clip] (S14B.6).
     *
     * Every recording running while it was filmed, and for each other person at the show, their
     * recording nearest to it, within [CAST_MARGIN_MS]. Only people who were recording used to be
     * offered, so a clip filmed before Tyler started at Boogie T offered four of the five. Someone
     * offered without readings for the clip is shown as such (see [hasReadings]); ticked, their
     * pill reads "--" and their curve starts when their watch does.
     *
     * Ordered with the people who were recording first, then by recording.
     */
    fun candidatesFor(
        clip: VideoExporter.VideoClip,
        records: List<BpmRecordWithPoints>
    ): List<BpmRecordWithPoints> {
        val during = records.filter { clip.overlaps(it) }
        val covered = during.map(::personKey).toSet()
        val others = records
            .filter { !clip.overlaps(it) && personKey(it) !in covered && distanceMs(clip, it) <= CAST_MARGIN_MS }
            .groupBy(::personKey)
            .values
            .map { theirs -> theirs.minBy { distanceMs(clip, it) } }
            .sortedBy { it.metadata.startTime }
        return during + others
    }

    /** Whether [record] has readings while [clip] was filming. */
    fun hasReadings(clip: VideoExporter.VideoClip, record: BpmRecordWithPoints): Boolean = clip.overlaps(record)

    /** Who [record] belongs to, so one person with three recordings that night is offered once. */
    private fun personKey(record: BpmRecordWithPoints): String =
        record.metadata.personId?.let { "person:$it" }
            ?: record.metadata.wearerName.trim().lowercase().takeIf { it.isNotEmpty() }?.let { "name:$it" }
            ?: "record:${record.metadata.recordId}"

    /** How far apart [clip] and [record] are in time: zero when they overlap. */
    private fun distanceMs(clip: VideoExporter.VideoClip, record: BpmRecordWithPoints): Long {
        val start = record.metadata.startTime
        val end = start + record.metadata.durationMs
        return maxOf(0L, start - clip.endedAtMs, clip.startedAtMs - end)
    }

    /**
     * Who is on [clip], keeping a deliberate choice where it still holds.
     *
     * The people recording while it was filmed, to begin with. The others offered (see
     * [candidatesFor]) are there to tick, but not ticked: someone whose watch started after the
     * clip has a pill reading "--" and no curve under the footage, which is rarely what's wanted.
     *
     * The people ticked before a correction are kept while one of them is recording at the new
     * time, so nudging a time by a few seconds doesn't throw away someone having been unticked, or
     * ticked, on purpose. Otherwise the clip now covers a different stretch, and the people
     * recording there are the right starting point.
     *
     * Where nobody was recording, everyone offered: the clip draws nothing and isn't exported
     * (see [drawsSomething]), but its preview still has curves to correct its time against.
     */
    fun castFor(
        clip: VideoExporter.VideoClip,
        records: List<BpmRecordWithPoints>,
        previous: Set<Long>
    ): Set<Long> {
        val candidates = candidatesFor(clip, records)
        val offered = candidates.map { it.metadata.recordId }.toSet()
        val recording = candidates.filter { hasReadings(clip, it) }.map { it.metadata.recordId }.toSet()
        val kept = previous intersect offered
        return when {
            kept.any { it in recording } -> kept
            recording.isNotEmpty() -> recording
            else -> offered
        }
    }

    /**
     * Whether a clip with this cast has anything to draw: at least one person on it was recording
     * while it was filmed. A clip that only has people offered from either side of it would render
     * footage under an empty graph, so it can't be ticked.
     */
    fun drawsSomething(clip: VideoExporter.VideoClip, cast: Set<Long>, records: List<BpmRecordWithPoints>): Boolean =
        records.any { it.metadata.recordId in cast && clip.overlaps(it) }

    /**
     * The clock [clip]'s times are shown in: the one its render prints. The venue's, else the
     * offset the file was filmed at, else the phone's ([inga.bpmetrics.library.exportZoneOf]).
     *
     * The clip card and "Filmed at" used to skip the file's offset and fall to the phone's zone, so
     * a clip filmed at 8:03 PM at the Gorge, edited on a phone set to Central time, said 10:03 PM
     * while the render printed 8:03. The time underneath was right; the label invited a "fix" that
     * moved the clip two hours off its own recordings.
     */
    fun clockFor(clip: VideoExporter.VideoClip, records: List<BpmRecordWithPoints>): java.time.ZoneId =
        java.time.ZoneId.of(inga.bpmetrics.library.exportZoneOf(records.map { it.metadata }, clip.zoneId))

    /**
     * Where a video picked by hand should start before anyone corrects it.
     *
     * Its own time where that lands on a recording — a clip filmed at the event by someone else's
     * phone is usually right as it is. Otherwise the first recording's start, rather than the file's
     * time: a screen recording made today lands on nothing, and a clip on nothing has no one to draw
     * and nothing to preview, which gives the person correcting it nothing to correct against.
     */
    fun startingPoint(
        fileClip: VideoExporter.VideoClip,
        records: List<BpmRecordWithPoints>
    ): Long =
        if (records.any { fileClip.overlaps(it) }) fileClip.startedAtMs
        else records.minOfOrNull { it.metadata.startTime } ?: fileClip.startedAtMs

    private fun applyClip(
        selection: ClipSelection,
        clip: VideoExporter.VideoClip,
        originalClip: VideoExporter.VideoClip?,
        records: List<BpmRecordWithPoints>
    ): ClipSelection {
        val cast = castFor(clip, records, selection.recordIds)
        val wanted = selection.selected || selection.untickedByTime
        val draws = drawsSomething(clip, cast, records)
        return selection.copy(
            clip = clip,
            originalClip = originalClip,
            recordIds = cast,
            // A clip nobody was recording during cannot be exported with a graph, so it cannot
            // stay ticked — the same rule the Contents step already applies. Remembered, so a
            // correction back into range ticks it again.
            selected = wanted && draws,
            untickedByTime = wanted && !draws,
            sparks = emptyList(),
            bpmScale = null
        )
    }
}
