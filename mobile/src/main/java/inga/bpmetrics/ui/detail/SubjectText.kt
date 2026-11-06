package inga.bpmetrics.ui.detail

import inga.bpmetrics.ui.util.StringFormatHelpers.getDateString
import inga.bpmetrics.ui.util.StringFormatHelpers.getTimeString
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The lines under a detail page's title, for a recording, an event or a collection (S15.2).
 *
 * Each page built its own with `buildString`, so they drifted: an event gave only its first day, so
 * a three-day festival read "Aug 1 · 2:00 PM–11:30 PM"; a recording named its time zone and an event
 * never did; a collection's counts came from the library while its figures came from the analysis.
 * One builder, so the three say things the same way.
 */
object SubjectText {

    /**
     * The clock's name: "Los Angeles" for a region, "UTC−7" for a bare offset like a clip's.
     *
     * The city rather than an abbreviation. "PDT" means nothing to a lot of people, and two zones
     * share some abbreviations.
     */
    fun zoneLabel(zone: ZoneId): String {
        if (zone is ZoneOffset || !zone.id.contains('/')) {
            val offset = zone.rules.getOffset(Instant.now())
            val seconds = offset.totalSeconds
            if (seconds == 0) return "UTC"
            val sign = if (seconds < 0) "−" else "+"
            val hours = kotlin.math.abs(seconds) / 3600
            val minutes = (kotlin.math.abs(seconds) % 3600) / 60
            return "UTC$sign$hours" + if (minutes != 0) ":%02d".format(minutes) else ""
        }
        return zone.id.substringAfterLast('/').replace('_', ' ')
    }

    /**
     * When something ran, from [startMs] to [endMs], in [zone].
     *
     * One day: "August 1, 2026 · 2:00 PM–11:30 PM". Several: both ends with their dates, so a
     * festival reads as the three days it was (S15.4). Named "Los Angeles time" when [zone] isn't
     * the reader's own, the rule the recording page already had: naming the zone on every time in a
     * library that never leaves home is noise, and leaving it off the one three zones away is a
     * wrong number with no warning.
     */
    fun span(startMs: Long, endMs: Long, zone: ZoneId, reader: ZoneId): String {
        val startDay = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val endDay = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        val text = if (startDay == endDay) {
            getDateString(startMs, zone) + " · " + getTimeString(startMs, zone) + "–" + getTimeString(endMs, zone)
        } else {
            getDateString(startMs, zone) + ", " + getTimeString(startMs, zone) + " – " +
                getDateString(endMs, zone) + ", " + getTimeString(endMs, zone)
        }
        return if (zone.id != reader.id) "$text ${zoneLabel(zone)} time" else text
    }

    /** A recording: when it started, in its own clock, then who wore it and where. */
    fun recording(startMs: Long, zone: ZoneId, reader: ZoneId, wearer: String?, place: String?): String =
        buildString {
            append(getDateString(startMs, zone))
            append(" · ")
            append(getTimeString(startMs, zone))
            if (zone.id != reader.id) append(" ${zoneLabel(zone)} time")
            wearer?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            place?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }

    /**
     * An event: its type, when it ran, and where.
     *
     * @param startMs null while it holds nothing, which says so rather than showing no time at all.
     */
    fun event(type: String?, startMs: Long?, endMs: Long?, zone: ZoneId, reader: ZoneId, place: String?): String =
        buildString {
            type?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
            if (startMs != null && endMs != null) append(span(startMs, endMs, zone, reader))
            else append("Nothing in it yet")
            place?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }

    /**
     * A collection: what kind it is, briefly. It has no window, place or clock of its own.
     *
     * Short, because the library card already explains what "smart" means and the page repeating
     * "whatever matches, whenever you look" under every one read as boilerplate.
     */
    fun collection(isFrozen: Boolean, isSmart: Boolean): String = when {
        isFrozen -> "Frozen collection: its numbers stay as they were"
        isSmart -> "Smart collection"
        else -> "Collection"
    }

    /**
     * How much is here: "11 recordings · 5 people · 3d 3h long".
     *
     * From the analysis, so it agrees with the figures beside it once people are filtered out or
     * parts left out (S15.4). Each part is left off when it says nothing: one person, no events.
     *
     * @param length already formatted, with what kind of length it is ("3d 3h long", "4h recorded").
     */
    fun counts(recordings: Int, people: Int, events: Int? = null, length: String? = null): String =
        buildString {
            if (events != null && events > 0) append(plural(events, "event")).append(" · ")
            append(plural(recordings, "recording"))
            if (people > 1) append(" · ").append(plural(people, "person", "people"))
            length?.let { append(" · ").append(it) }
        }

    fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
}
