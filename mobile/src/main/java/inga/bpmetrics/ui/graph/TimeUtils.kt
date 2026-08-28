package inga.bpmetrics.ui.graph

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Utility functions for formatting and parsing time strings used in the graph.
 */
object TimeUtils {
    private val clockFormatter = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.US)
    private val clockFormatter24 = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    /**
     * Formats milliseconds into a string: "H:MM:SS.mmm" if hours exist, otherwise "MM:SS.mmm".
     * Supports negative values by prepending a minus sign.
     */
    fun formatMs(ms: Long): String {
        val isNegative = ms < 0
        val absMs = kotlin.math.abs(ms)
        val totalSeconds = absMs / 1000
        val millis = absMs % 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        
        val timeStr = if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, millis)
        } else {
            String.format(Locale.US, "%02d:%02d.%03d", m, s, millis)
        }
        
        return if (isNegative) "-$timeStr" else timeStr
    }

    /**
     * Parses a string into milliseconds relative to 0. Supports:
     * - "[-]H:M:S.mmm"
     * - "[-]M:S.mmm"
     * - "[-]S.mmm"
     * - "[-]H:M:S"
     * - "[-]M:S"
     * - "[-]S"
     * Returns null if the input is invalid.
     */
    fun parseToMs(input: String): Long? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        
        val isNegative = trimmed.startsWith("-")
        val cleanInput = if (isNegative) trimmed.substring(1) else trimmed
        val parts = cleanInput.split(":").map { it.trim() }
        
        fun parseLastPart(lastPart: String): Pair<Int, Int> {
            val subParts = lastPart.split(".")
            val seconds = subParts[0].toIntOrNull() ?: 0
            val millis = if (subParts.size > 1) {
                val msStr = subParts[1].padEnd(3, '0').take(3)
                msStr.toIntOrNull() ?: 0
            } else 0
            return Pair(seconds, millis)
        }

        return try {
            val result = when (parts.size) {
                3 -> {
                    val h = parts[0].toInt()
                    val m = parts[1].toInt()
                    val (s, ms) = parseLastPart(parts[2])
                    (h * 3600L + m * 60L + s) * 1000L + ms
                }
                2 -> {
                    val m = parts[0].toInt()
                    val (s, ms) = parseLastPart(parts[1])
                    (m * 60L + s) * 1000L + ms
                }
                1 -> {
                    val (s, ms) = parseLastPart(parts[0])
                    s * 1000L + ms
                }
                else -> null
            }
            
            if (result != null) {
                if (isNegative) -result else result
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A clock time on the graph, in the reader's chosen clock.
     *
     * Both formatters existed and only the 12-hour one was ever used, so a library set to 24 hours
     * still read "10:30:00 AM" here. Seconds stay: this labels a scrub position on a chart, where
     * the second is the whole point — [parseClockTimeToRelativeMs] reads back what this writes.
     */
    fun formatClockTime(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val formatter = if (inga.bpmetrics.ui.util.StringFormatHelpers.use24Hour) {
            clockFormatter24
        } else {
            clockFormatter
        }
        return Instant.ofEpochMilli(epochMs)
            .atZone(zoneId)
            .format(formatter)
    }

    /**
     * Every shape a clock time is written in around here, widest net first.
     *
     * **One pattern per shape, with single-letter hours.** A count of two — `hh` — is a *fixed
     * width* field when parsing, so a formatter built from "hh:mm:ss a" rejects "9:04:00 PM"
     * outright. That is what it was, and what [inga.bpmetrics.ui.util.StringFormatHelpers] prints
     * on a 12-hour clock is "h:mm:ss a" — so nine times out of twelve the app could not read back
     * the time it had just written into the field, and the dialog called it invalid. Single-letter
     * accepts both widths.
     *
     * Seconds are optional because a time someone types by hand rarely has them, and the 12-hour
     * forms come first: they need the AM/PM marker to match at all, so they cannot swallow a
     * 24-hour time by accident.
     */
    private val clockPatterns: List<DateTimeFormatter> = buildList {
        val locales = listOf(Locale.US, Locale.getDefault()).distinct()
        listOf("h:mm:ss a", "h:mm a").forEach { pattern ->
            locales.forEach { add(DateTimeFormatter.ofPattern(pattern, it)) }
        }
        add(DateTimeFormatter.ofPattern("H:mm:ss", Locale.US))
        add(DateTimeFormatter.ofPattern("H:mm", Locale.US))
    }

    /**
     * A time of day, however it was written.
     *
     * Case and spacing are normalised before anything is tried. CLDR puts a narrow no-break space
     * in front of "PM" on some platforms and an ordinary one on others, and a field someone typed
     * into has neither reliably — none of which is a reason to call a time invalid.
     */
    fun parseClockTime(input: String): LocalTime? {
        val cleaned = input.trim()
            // Every flavour of space CLDR and a soft keyboard between them can produce.
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace(Regex("\\s+"), " ")
            .uppercase(Locale.US)
        if (cleaned.isEmpty()) return null

        clockPatterns.forEach { formatter ->
            try {
                return LocalTime.parse(cleaned, formatter)
            } catch (_: Exception) {
                // Next shape.
            }
        }
        return null
    }

    /**
     * A clock time, as an offset from an instant.
     *
     * **The occurrence nearest the anchor, not the one on the anchor's date.** A time of day names
     * a moment on every day there has ever been, and the recordings this reads times for are
     * evening ones: a set that begins at 23:40 and ends at 00:20 had its end read as *twenty-three
     * hours and twenty minutes before it started*, which every caller then refused as a range
     * running backwards. Anything after midnight was unsplittable.
     *
     * Nearest rather than "roll forward when negative", because the anchor is not always the thing
     * being extended — asking for a start a few minutes earlier than the anchor is an ordinary
     * thing to do, and rolling forward would answer it with tomorrow.
     *
     * Built through [ZonedDateTime] rather than by adding milliseconds, so an hour that a daylight
     * saving change deleted or repeated resolves the way the rest of `java.time` resolves it.
     */
    fun parseClockTimeToRelativeMs(
        input: String,
        baseEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val localTime = parseClockTime(input) ?: return null

        val base = Instant.ofEpochMilli(baseEpochMs).atZone(zoneId)
        val onBaseDay = base.toLocalDate().atTime(localTime).atZone(zoneId)

        return listOf(onBaseDay.minusDays(1), onBaseDay, onBaseDay.plusDays(1))
            .map { it.toInstant().toEpochMilli() - baseEpochMs }
            .minByOrNull { kotlin.math.abs(it) }
    }
}
