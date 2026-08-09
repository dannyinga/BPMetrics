package inga.bpmetrics.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Helper object providing utility functions for formatting raw timestamps and durations
 * into human-readable strings for the UI.
 */
/**
 * The device's own clock, for times that genuinely belong to the reader.
 *
 * Named rather than passed as `ZoneId.systemDefault()` so the two cases are distinguishable in
 * review: "this is the reader's time" is a claim, and it should look like one.
 */
val ReaderClock: ZoneId get() = ZoneId.systemDefault()

object StringFormatHelpers {

    /**
     * Converts a duration in milliseconds into a formatted string (e.g., "1h 2m 3s").
     *
     * @param durationMs The duration to format in milliseconds.
     * @return A human-readable string representing the hours, minutes, and seconds.
     */
    fun getDurationString(durationMs: Long) : String {
        val durationHour = durationMs / (1000 * 60 * 60)
        val durationHourText = if (durationHour <= 0) ""
        else "${durationHour}h "

        val durationMin = (durationMs / (1000 * 60)) % 60
        val durationMinText = if (durationMin <= 0) ""
        else "${durationMin}m "

        val durationSec = (durationMs / 1000) % 60
        val durationSecText = if (durationSec <= 0) ""
        else "${durationSec}s "

        // Removed ms from recording duration display
        val durationString = (durationHourText + durationMinText + durationSecText).trim()

        return if (durationString.isEmpty()) "0s" else durationString
    }

    /**
     * Converts a duration in milliseconds into a clock-style string (e.g., "01:05:23").
     *
     * @param durationMs The duration to format in milliseconds.
     * @return A formatted string as HH:mm:ss or mm:ss if hours are 0.
     */
    fun getElapsedTimeString(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Converts a wall-clock timestamp into a formatted date string (MM/dd/yyyy).
     *
     * @param date The timestamp in milliseconds (epoch time).
     * @return A string representing the date in the local time zone.
     */
    /**
     * How dates and times are written, app-wide.
     *
     * A mutable holder rather than a parameter on every call site, because the alternative was
     * threading two preferences through several hundred `getDateString` calls — including ones
     * inside renderers that have no business knowing about DataStore. Set once at startup and
     * whenever the setting changes; read everywhere.
     *
     * The cost is a global, and the honest reason it is acceptable is that this genuinely is one:
     * there is no sense in which two parts of the app should disagree about what today looks like.
     */
    @Volatile var datePattern: String = "MM/dd/yyyy"
    @Volatile var use24Hour: Boolean = false

    /**
     * A date, in a stated clock.
     *
     * **The zone is required.** It defaulted to the device, which is correct for "exported five
     * minutes ago" and wrong for every recording made anywhere else — a set watched at 21:00 at the
     * Gorge reads as the next day once you fly home, for ever. A default made that the silent
     * outcome at thirty call sites; requiring the argument makes each one a decision.
     *
     * Pass [ReaderClock] where the time genuinely belongs to whoever is looking — a render finished,
     * a backup written — and the recording's own zone everywhere else.
     */
    fun getDateString(date: Long, zoneId: ZoneId) : String {
        // Rebuilt per call rather than cached: a DateTimeFormatter is cheap to make, and caching
        // one would need invalidating every time the preference changed.
        val dateFormatter = runCatching { DateTimeFormatter.ofPattern(datePattern) }
            .getOrElse { DateTimeFormatter.ofPattern("MM/dd/yyyy") }
        val dateText = Instant.ofEpochMilli(date).atZone(zoneId).format(dateFormatter)
        return dateText
    }

    /**
     * Converts a wall-clock timestamp into a formatted time string (hh:mm:ss a) in the specified time zone.
     *
     * @param time The timestamp in milliseconds (epoch time).
     * @param zoneId The time zone to format the time in. Defaults to the system default time zone.
     * @return A string representing the time (e.g., "10:30:00 AM") in the selected time zone.
     */
    /**
     * A time, in a stated clock. See [getDateString] for why the zone is not optional.
     */
    fun getTimeString(time: Long, zoneId: ZoneId) : String {
        val pattern = if (use24Hour) "HH:mm:ss" else "hh:mm:ss a"
        val timeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        val timeString = "${
            Instant.ofEpochMilli(time)
            .atZone(zoneId)
            .format(timeFormatter)}"

        return timeString
    }
}
