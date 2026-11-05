package inga.bpmetrics.ui.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Translates between Material's date pickers and the days a filter means.
 *
 * The pickers speak in UTC midnights: "Mar 14" comes back as 00:00 UTC on the 14th, which in the
 * Americas is the evening of the 13th. Used as an instant, that range began the evening before and
 * ended the afternoon of the 14th, so it showed as "Mar 13 – Mar 14" and dropped the evening shows
 * that were the reason for picking the 14th. A filter's range is local: from the start of the first
 * day to the last millisecond of the last, wherever the phone is.
 */
object PickerDays {

    /** The picker's selection as a local range, from the first day's start to the last day's end. */
    fun toRange(startUtcMs: Long, endUtcMs: Long?, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val first = dayOf(startUtcMs)
        // A single day is a legal range: the end falls back to the start.
        val last = endUtcMs?.let(::dayOf) ?: first
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start to end
    }

    /** A stored local range as the picker's UTC midnights, so reopening it shows the same days. */
    fun toPicker(range: Pair<Long, Long>, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        pickerMs(range.first, zone) to pickerMs(range.second, zone)

    private fun dayOf(utcMs: Long): LocalDate = Instant.ofEpochMilli(utcMs).atOffset(ZoneOffset.UTC).toLocalDate()

    private fun pickerMs(localMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(localMs).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
