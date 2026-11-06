package inga.bpmetrics.export

import android.graphics.Bitmap
import android.net.Uri
import inga.bpmetrics.library.BpmRecordWithPoints

/**
 * What an export is *of* (Model and Export Consolidation §6).
 *
 * The other half is [ExportPreset], which is how it *looks*. They used to be one config with thirty
 * fields of both, filled by hand-copying a preset into it in four places, and the copies drifted:
 * a queued video restored after the app was killed lost its readout pills, clock and wordmark,
 * because the copy back out of the config didn't know about them (Audit #25). Now nothing is
 * copied. A renderer reads the look from the preset and everything else from here.
 *
 * @property startMs where the export starts on the recordings' shared timeline, in ms from its
 *   origin. See [ImageExporter.timelineFor].
 * @property endMs where it ends, likewise.
 * @property title the caption drawn on it, or null for none.
 * @property colours each recording's curve colour, by record id.
 * @property photos each recording's wearer, cropped, by record id. Not persisted: a restored job
 *   draws initials until the photos are looked up again.
 * @property clipZoneId the UTC offset the footage says it was filmed at, if it says. See
 *   [ClipZone] and [inga.bpmetrics.library.exportZoneOf].
 */
data class ExportContent(
    val records: List<BpmRecordWithPoints>,
    val startMs: Long,
    val endMs: Long,
    val title: String? = null,
    val colours: Map<Long, Int> = emptyMap(),
    val photos: Map<Long, Bitmap> = emptyMap(),
    val clipZoneId: String? = null
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1_000L)
}

/** One export to make: what it's of, how it looks, and what kind of thing it is. */
sealed interface ExportRequest {
    val content: ExportContent
    val preset: ExportPreset

    /**
     * A video: the curves moving over footage, or over a plain background without any.
     *
     * [preset] is fully resolved for this one video: the clip's own framing is baked in and the
     * per-cast pins cleared, so [ExportPreset.frameFor] answers the frame this video uses. Stored as
     * it is, a queued job comes back exactly as queued.
     *
     * @property clip the footage, or null for a plain background.
     * @property clipStartedAtMs when the footage started filming, as the clip picker resolved it, so
     *   the render lines up exactly as the preview did.
     */
    data class Video(
        override val content: ExportContent,
        override val preset: ExportPreset,
        val clip: Uri? = null,
        val clipStartedAtMs: Long? = null
    ) : ExportRequest
}
