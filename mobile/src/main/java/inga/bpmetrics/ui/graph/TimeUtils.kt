package inga.bpmetrics.ui.graph

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
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
     * Formats epoch milliseconds into a clock time string (e.g., "10:30:00 AM").
     */
    fun formatClockTime(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(clockFormatter)
    }

    /**
     * Parses a clock time string and converts it to milliseconds relative to a base start time.
     * Supports "hh:mm:ss a" (e.g., 10:30:00 AM) or "HH:mm:ss" (e.g., 22:30:00).
     */
    fun parseClockTimeToRelativeMs(input: String, baseEpochMs: Long): Long? {
        return try {
            val localTime = try {
                LocalTime.parse(input.trim().uppercase(), clockFormatter)
            } catch (e: Exception) {
                LocalTime.parse(input.trim(), clockFormatter24)
            }
            
            val baseDateTime = Instant.ofEpochMilli(baseEpochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
            
            val targetDateTime = baseDateTime.with(localTime)
            
            // If the record spans across midnight, this might need more complex logic,
            // but for now, we assume it's on the same day as the start or very close.
            val targetEpochMs = targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            targetEpochMs - baseEpochMs
        } catch (e: Exception) {
            null
        }
    }
}
