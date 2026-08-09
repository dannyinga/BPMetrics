package inga.bpmetrics.library

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What to call a recording, in one place.
 *
 * Recordings are auto-named "Untitled 4" at ingest, because the phone knows nothing about them
 * when they arrive. By the time anyone looks at one it knows who wore the watch and when — which
 * is what makes a recording identifiable in a list of forty. "Untitled 4" identifies nothing.
 *
 * A title the user typed always wins. The generated name is a fallback for the ones nobody has
 * named, which is nearly all of them.
 */
object RecordNameFormatter {

    /**
     * Names a recording: the user's title if they gave one, else `{wearer} · {time}`.
     *
     * Falls back through wearer → watch → the stored title, so something useful appears even for a
     * recording that arrived before profiles existed or from a watch that was never registered.
     *
     * @param wearerName Who wore it, resolved live from their profile by the caller.
     * @param watchName The watch's given name, for recordings with nobody attached.
     */
    fun displayName(
        record: BpmRecordEntity,
        wearerName: String? = null,
        watchName: String? = null
    ): String {
        // A title someone typed is theirs and is never second-guessed. Auto-generated ones are
        // recognised by shape rather than by a flag, because there is no flag — they have been
        // written straight into `title` since long before this existed.
        if (record.title.isNotBlank() && !isPlaceholder(record.title)) return record.title

        val who = wearerName?.takeIf { it.isNotBlank() }
            ?: record.wearerName.takeIf { it.isNotBlank() }
            ?: watchName?.takeIf { it.isNotBlank() }
            ?: record.deviceId.takeIf { it.isNotBlank() }

        val at = timeFormat.format(Date(record.startTime))

        // Reaching here means the title was blank or a placeholder, so falling back to it would
        // undo the whole point. When nothing is known about who or what, the time still is.
        return if (who != null) "$who · $at" else at
    }

    /**
     * Whether a title is the app's own placeholder.
     *
     * Deliberately only `Untitled` and `Untitled 4`. Auto-naming also produces titles from a tag —
     * "Spiderman 2" — but the user picked that tag, so it means something and is left alone.
     *
     * Matching on shape instead ("a word or two then a number") was tried and rejected: it
     * swallows "Coachella 2026", "Set 2" and "Day 3", replacing a title someone typed with one
     * the app invented. Guessing wrong in that direction is worse than leaving a placeholder.
     */
    fun isPlaceholder(title: String): Boolean = PLACEHOLDER.matches(title.trim())

    private val PLACEHOLDER = Regex("""^Untitled(?: \d+)?$""", RegexOption.IGNORE_CASE)

    private val timeFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
}

/**
 * The name to show for this recording, given who wore it and what it was recorded on.
 *
 * Extension rather than a method so the entity stays a plain Room row with no opinion about
 * presentation.
 */
fun BpmRecord.displayName(wearerName: String? = null, watchName: String? = null): String =
    RecordNameFormatter.displayName(metadata, wearerName, watchName)

/**
 * The same, for a recording carrying its readings.
 *
 * Both delegate to [RecordNameFormatter.displayName] over the row, so a chart's title and the tile
 * that opened it cannot disagree. A forwarder rather than an interface: the two types differ only
 * in whether the readings came along, which is a fact about the query, not about the recording.
 */
fun BpmRecordWithPoints.displayName(wearerName: String? = null, watchName: String? = null): String =
    RecordNameFormatter.displayName(metadata, wearerName, watchName)
