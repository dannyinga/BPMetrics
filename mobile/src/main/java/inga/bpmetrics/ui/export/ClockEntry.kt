package inga.bpmetrics.ui.export

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A clock time typed into an image's time range, read the way the app shows times.
 *
 * The fields were fixed to "HH:mm" whatever the 12/24-hour setting said, and anything they
 * couldn't read was dropped without a word (Audit §3 Export). This shows times in the user's
 * format, reads either format back, and says what's wrong when it can't.
 */
object ClockEntry {

    sealed interface Result {
        /** A time, or null for "the whole range" (an empty field). */
        data class Value(val ms: Long?) : Result
        data class Error(val message: String) : Result
    }

    fun pattern(withDate: Boolean, use24Hour: Boolean): String =
        (if (withDate) "yyyy-MM-dd " else "") + (if (use24Hour) "HH:mm" else "h:mm a")

    fun format(ms: Long, zone: ZoneId, withDate: Boolean, use24Hour: Boolean): String =
        DateTimeFormatter.ofPattern(pattern(withDate, use24Hour), Locale.US)
            .format(Instant.ofEpochMilli(ms).atZone(zone))

    private val DATE_THEN_TIME = Regex("""^(\d{4}-\d{1,2}-\d{1,2})[\sT]+(.+)$""")
    private val TIME = Regex("""^(\d{1,2})(?:[:.](\d{2}))?\s*([aApP])?\.?\s*[mM]?\.?$""")

    /**
     * Reads [text] as a time within [span], the recordings' own start and end.
     *
     * @param withDate whether the span crosses midnight, so a time alone doesn't say which day.
     */
    fun parse(
        text: String,
        span: Pair<Long, Long>,
        zone: ZoneId,
        withDate: Boolean,
        use24Hour: Boolean
    ): Result {
        val typed = text.trim()
        if (typed.isEmpty()) return Result.Value(null)
        val example = format(span.first, zone, withDate, use24Hour)

        val dated = DATE_THEN_TIME.find(typed)
        val day: LocalDate
        val timeText: String
        if (dated != null) {
            day = runCatching {
                val (y, m, d) = dated.groupValues[1].split('-').map { it.toInt() }
                LocalDate.of(y, m, d)
            }.getOrNull() ?: return Result.Error("That date can't be read. Try $example.")
            timeText = dated.groupValues[2]
        } else {
            if (withDate) return Result.Error("This spans more than one day, so include the date, like $example.")
            day = Instant.ofEpochMilli(span.first).atZone(zone).toLocalDate()
            timeText = typed
        }

        val match = TIME.find(timeText.trim()) ?: return Result.Error("Type a time like $example.")
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        val half = match.groupValues[3].lowercase()
        if (minute > 59) return Result.Error("Type a time like $example.")
        // An hour alone, like "9", is too easily a half-typed time to guess at.
        if (match.groupValues[2].isEmpty() && half.isEmpty()) return Result.Error("Type a time like $example.")

        val hours: List<Int> = when {
            half == "a" || half == "p" -> {
                if (hour !in 1..12) return Result.Error("Type a time like $example.")
                listOf(hour % 12 + if (half == "p") 12 else 0)
            }
            hour > 23 -> return Result.Error("Type a time like $example.")
            // "21:15", or "0:30": only one way to read it.
            hour == 0 || hour > 12 || use24Hour -> listOf(hour)
            // "9:15" with a 12-hour clock: whichever of morning and evening the recordings cover.
            else -> listOf(hour % 12, hour % 12 + 12)
        }

        val candidates = hours.map {
            day.atTime(LocalTime.of(it, minute)).atZone(zone).toInstant().toEpochMilli()
        }
        // Whole minutes either side: a set starting at 21:15:40 can still be cropped "from 21:15".
        val minuteMs = 60_000L
        val window = Math.floorDiv(span.first, minuteMs) * minuteMs..
            Math.floorDiv(span.second + minuteMs - 1, minuteMs) * minuteMs
        val inside = candidates.filter { it in window }
        val chosen = when {
            inside.size == 1 -> inside.first()
            inside.size > 1 -> return Result.Error("Add AM or PM.")
            else -> candidates.first()
        }
        if (chosen !in window) {
            val from = format(span.first, zone, withDate, use24Hour)
            val to = format(span.second, zone, withDate, use24Hour)
            return Result.Error("Outside the recording ($from – $to).")
        }
        return Result.Value(chosen)
    }
}
