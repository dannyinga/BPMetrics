package inga.bpmetrics.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Helper object providing utility functions for formatting raw timestamps and durations
 * into human-readable strings for the UI.
 */
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
    fun getDateString(date: Long) : String {
        val dateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
        val dateText = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).format(dateFormatter)
        return dateText
    }

    /**
     * Converts a wall-clock timestamp into a formatted time string (hh:mm:ss a).
     *
     * @param time The timestamp in milliseconds (epoch time).
     * @return A string representing the time (e.g., "10:30:00 AM") in the local time zone.
     */
    fun getTimeString(time: Long) : String {
        val timeFormatter = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.getDefault())
        val timeString = "${
            Instant.ofEpochMilli(time)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)}"

        return timeString
    }
}
