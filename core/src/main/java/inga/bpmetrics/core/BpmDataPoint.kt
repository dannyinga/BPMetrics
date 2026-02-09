package inga.bpmetrics.core

import androidx.room.Entity

/**
 * A single data point for a BPM reading : (timestamp: Long, bpm: Double)
 */
@Entity()
data class BpmDataPoint(
    val timestamp: Long, // time since start of recording
    val bpm: Double
) : Comparable<BpmDataPoint> {

    init {
        validateParams()
    }

    private fun validateParams() {
        if (timestamp < 0)
            throw IllegalArgumentException("BPM Data Point can't be created with negative timestamp")

        if (bpm < 0 || bpm > 250)
            throw IllegalArgumentException("BPM Data Point can't be created with bpm: $bpm\n" +
                                        "BPM must be between 0 and 250 inclusive")
    }

    /**
     * Sort data points based on their timestamp (ascending)
     */
    override fun compareTo(other: BpmDataPoint): Int {
        return this.timestamp.compareTo(other.timestamp)
    }

    /**
     * Formats into "Timestamp: #m #s #ms, BPM: #"
     */
    override fun toString(): String {
        val milliseconds = timestamp % 1000
        val seconds = timestamp / 1000 % 60
        val minutes = timestamp / (1000 * 60) % 60
        val formattedTimeStamp = "${minutes}m ${seconds}s ${milliseconds}ms"
        return "Timestamp: $formattedTimeStamp, BPM: $bpm"
    }
}
