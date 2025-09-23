package inga.bpmetrics.core

import java.sql.Date
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.Instant
import java.time.ZoneId

/**
 * A record as created by the watch. Contains only the data, data points, start time, end time,
 * and duration. The phone will receive this record and give it additional info (title, tags,
 * statistics, etc.)
 */
data class BpmWatchRecord(
    val date: Date,
    val dataPoints: List<BpmDataPoint>,
    val startTime: Long,
    val endTime: Long,
) : Comparable<BpmWatchRecord> {
    val duration: Long = endTime - startTime

    init {
        validateParams()
    }

    private fun validateParams() {
        if (endTime <= startTime || startTime < 0)
            throw IllegalArgumentException("Can't construct record with start time: $startTime and end time: $endTime")
    }

    /**
     * Default comparison is based on date
     */
    override fun compareTo(other: BpmWatchRecord): Int {
        return date.compareTo(other.date)
    }
    /**
     * Returns a nicely formatted string of the record's information
     */
    override fun toString(): String {
        val outputBuilder = StringBuilder()
        outputBuilder.appendLine("Date: $date")

        val formatter = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.getDefault())
        outputBuilder.appendLine("Start Time: ${Instant.ofEpochMilli(startTime)
                                                        .atZone(ZoneId.systemDefault())
                                                        .format(formatter)}")
        outputBuilder.appendLine("End Time: ${Instant.ofEpochMilli(endTime)
                                                        .atZone(ZoneId.systemDefault())
                                                        .format(formatter)}")

        val durationMin = duration / (1000 * 60) % 60
        val durationSec = duration / 1000 % 60
        val durationMillis = duration % 1000
        outputBuilder.appendLine("Duration: ${durationMin}m ${durationSec}s ${durationMillis}ms")
        outputBuilder.appendLine()
        outputBuilder.appendLine("Raw Data")

        for (dataPoint in dataPoints) {
            outputBuilder.appendLine(dataPoint)
        }

        return outputBuilder.toString();
    }
}