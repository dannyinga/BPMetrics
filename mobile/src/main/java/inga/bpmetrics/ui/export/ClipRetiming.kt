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
     * [selection] as filmed at [startedAtMs].
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
    ): ClipSelection {
        // Always from the file's own clip, so correcting twice does not compound, and "use the
        // file's time" always has the original to go back to.
        val original = selection.originalClip ?: selection.clip
        val moved = original.copy(startedAtMs = startedAtMs, basis = VideoTiming.Basis.SET_BY_HAND)
        return applyClip(selection, moved, originalClip = original, records = records)
    }

    /** Back to the time the file gave. A no-op for a clip that was never corrected. */
    fun reset(selection: ClipSelection, records: List<BpmRecordWithPoints>): ClipSelection {
        val original = selection.originalClip ?: return selection
        return applyClip(selection, original, originalClip = null, records = records)
    }

    /**
     * Who was recording while [clip] was filming, keeping a deliberate choice where it still holds.
     *
     * The people ticked before a correction are kept if any of them still overlap — so nudging a
     * time by a few seconds does not throw away someone having been unticked on purpose. Where none
     * of them do, the clip now covers a different stretch entirely, and everyone recording during it
     * is the right starting point.
     */
    fun castFor(
        clip: VideoExporter.VideoClip,
        records: List<BpmRecordWithPoints>,
        previous: Set<Long>
    ): Set<Long> {
        val overlapping = records.filter { clip.overlaps(it) }.map { it.metadata.recordId }.toSet()
        val kept = previous intersect overlapping
        return kept.ifEmpty { overlapping }
    }

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
        return selection.copy(
            clip = clip,
            originalClip = originalClip,
            recordIds = cast,
            // A clip nobody was recording during cannot be exported with a graph, so it cannot
            // stay ticked — the same rule the Contents step already applies. Remembered, so a
            // correction back into range ticks it again.
            selected = wanted && cast.isNotEmpty(),
            untickedByTime = wanted && cast.isEmpty(),
            sparks = emptyList(),
            bpmScale = null
        )
    }
}
